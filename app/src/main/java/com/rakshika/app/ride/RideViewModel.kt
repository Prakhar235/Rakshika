package com.rakshika.app.ride

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rakshika.app.alerts.AlertMessages
import com.rakshika.app.alerts.ContactsStore
import com.rakshika.app.alerts.SmsAlerts
import com.rakshika.app.data.model.ContactStatus
import com.rakshika.app.geo.geoPointAt
import com.rakshika.app.live.LiveShareConfig
import com.rakshika.app.live.LiveShareRepository
import com.rakshika.app.location.DeviceLocation
import com.rakshika.app.rag.RagRouteEngine
import com.rakshika.app.rag.SafetyDatasets
import com.rakshika.app.routing.ValhallaRouting
import com.rakshika.app.search.PlaceSearch
import com.rakshika.app.ui.mapkit.ROUTE_B
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RideViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(RideState())
    val state: StateFlow<RideState> = _state

    private val engine = RagRouteEngine()
    private val live = LiveShareRepository(viewModelScope)
    private val contactsStore = ContactsStore(app)
    private var rideJob: Job? = null
    private var assessJob: Job? = null
    private var searchJob: Job? = null

    /** Search-bias / routing origin: the device's real location once found, else the demo's fixed one. */
    private var originGeo: DoubleArray = LiveShareConfig.ORIGIN_GEO

    /** Firebase publish state + path, for the "Sharing live" chip on the ride screen. */
    val liveStatus = live.status
    val liveTripUrl = live.tripUrl

    /** Tries the device's last-known location (if permission was granted); falls back to the demo default. */
    fun refreshDeviceLocation() {
        val loc = DeviceLocation.lastKnown(getApplication())
        if (loc != null) {
            originGeo = loc
            Log.i(TAG, "Using device location for search/routing: ${loc[0]},${loc[1]}")
        } else {
            Log.i(TAG, "No device location available (permission denied or no fix yet) — using the demo default origin")
        }
        _state.update { it.copy(usingDeviceLocation = loc != null) }
    }

    /** Debounced free-text place search via Photon (OSM geocoder), biased to [originGeo]. */
    fun updateQuery(text: String) {
        _state.update { it.copy(query = text) }
        searchJob?.cancel()
        if (text.isBlank()) {
            _state.update { it.copy(searchResults = null, searching = false) }
            return
        }
        // Mark searching immediately (not after the debounce) so the UI can't show the
        // coordinate-less local fallback list mid-typing — see RideState.suggestions.
        _state.update { it.copy(searching = true, searchResults = null) }
        searchJob = viewModelScope.launch {
            delay(300)
            val results = PlaceSearch.search(text, originGeo[0], originGeo[1])
            if (isActive) _state.update { it.copy(searchResults = results, searching = false) }
        }
    }

    /** Pick which safety dataset the on-device RAG retrieves from; re-runs live if a route is up. */
    fun selectDataset(id: String) {
        if (_state.value.selectedDatasetId == id) return
        _state.update { it.copy(selectedDatasetId = id) }
        _state.value.destination?.let { runAssessment(it, keepStep = true) }
    }

    fun selectDestination(place: Place) {
        _state.update { it.copy(destination = place) }
        runAssessment(place, keepStep = false)
    }

    fun selectRoute(safe: Boolean) {
        _state.update { it.copy(safeSelected = safe) }
    }

    fun backToSearch() {
        rideJob?.cancel()
        assessJob?.cancel()
        live.endTrip()
        _state.update { RideState(query = it.query, selectedDatasetId = it.selectedDatasetId) }
    }

    private fun currentChoice() = _state.value.routes?.let {
        if (_state.value.safeSelected) it.safe else it.fast
    }

    /** The real `[lat, lng]` path the current choice walks — the live routed road, or the mock-map stand-in. */
    private fun chosenGeoPath(): List<DoubleArray> =
        currentChoice()?.resolvedGeoPath() ?: LiveShareConfig.toGeoPath(ROUTE_B)

    private fun runAssessment(place: Place, keepStep: Boolean) {
        assessJob?.cancel()
        assessJob = viewModelScope.launch {
            _state.update { it.copy(assessing = true) }
            val dataset = SafetyDatasets.byId(_state.value.selectedDatasetId)

            // The on-device RAG safety scoring and the real-road lookup run concurrently —
            // one's a small delay for UX, the other a real network call that may fail or be slow.
            val ragDeferred = async {
                // Small pause so the "embedding query · retrieving notes" step is visible.
                delay(if (keepStep) 280L else 460L)
                engine.assess("${ORIGIN.name} ${ORIGIN.area}", "${place.name} ${place.area}", dataset)
            }
            val geoDeferred = async {
                val lat = place.lat
                val lng = place.lng
                if (lat != null && lng != null) {
                    Log.i(TAG, "Requesting live routes for \"${place.name}\" @ $lat,$lng")
                    ValhallaRouting.findRoutes(originGeo[0], originGeo[1], lat, lng)
                } else {
                    Log.w(TAG, "\"${place.name}\" has no coordinates — skipping live routing, using the mock route")
                    null
                }
            }

            val result = ragDeferred.await()
            val geoRoutes = geoDeferred.await()

            _state.update {
                it.copy(
                    step = if (keepStep) it.step else RideStep.ROUTES,
                    assessing = false,
                    rag = result,
                    routes = routePairFrom(result, geoRoutes),
                    safeSelected = true
                )
            }
        }
    }

    /** Starts the ride: contacts are notified immediately, then the dot walks the chosen route in real time. */
    fun startRide() {
        val routes = _state.value.routes ?: return
        val chosen = if (_state.value.safeSelected) routes.safe else routes.fast

        _state.update {
            it.copy(
                step = RideStep.RIDING,
                rideProgress = 0f,
                etaMinutesLeft = chosen.minutes,
                sharingLive = true,
                contactAmma = ContactStatus.NOTIFIED,
                contactRohan = ContactStatus.NOTIFIED,
                sosActive = false
            )
        }

        val path = chosenGeoPath()
        val destination = _state.value.destination
        if (destination != null) {
            live.startTrip(ORIGIN, destination, chosen, _state.value.safeSelected, path, chosen.minutes)
            val d = path.last()
            SmsAlerts.send(
                getApplication(),
                contactsStore.recipients(),
                AlertMessages.rideStarted(destination.name, chosen.label, chosen.minutes, d[0], d[1])
            )
        }

        rideJob = viewModelScope.launch {
            val totalSteps = 90
            val stepMs = 120L
            var step = 0
            while (isActive && step <= totalSteps) {
                val progress = step / totalSteps.toFloat()
                val minutesLeft = (chosen.minutes * (1 - progress)).toInt().coerceAtLeast(0)
                _state.update { it.copy(rideProgress = progress, etaMinutesLeft = minutesLeft) }
                if (progress >= 0.4f && _state.value.contactAmma == ContactStatus.NOTIFIED) {
                    _state.update { it.copy(contactAmma = ContactStatus.SEEN, contactRohan = ContactStatus.SEEN) }
                }
                // Push a location fix a few times a second so another app can follow along live.
                if (step % 2 == 0) live.updateLocation(path, progress, minutesLeft)
                delay(stepMs)
                step++
            }
            live.updateLocation(path, 1f, 0)
            live.arrive()
            _state.value.destination?.let {
                SmsAlerts.send(getApplication(), contactsStore.recipients(), AlertMessages.arrived(it.name))
            }
            _state.update { it.copy(step = RideStep.ARRIVED, rideProgress = 1f, etaMinutesLeft = 0) }
        }
    }

    fun triggerSos() {
        live.setSos(true)
        val path = chosenGeoPath()
        val here = geoPointAt(path, _state.value.rideProgress)
        val destination = _state.value.destination?.name ?: "my destination"
        SmsAlerts.send(
            getApplication(),
            contactsStore.recipients(),
            AlertMessages.sosRide(destination, here[0], here[1])
        )
        _state.update { it.copy(sosActive = true, contactRohan = ContactStatus.RESPONDING) }
    }

    fun newRide() {
        rideJob?.cancel()
        assessJob?.cancel()
        live.endTrip()
        _state.update { RideState(selectedDatasetId = it.selectedDatasetId) }
    }

    override fun onCleared() {
        rideJob?.cancel()
        assessJob?.cancel()
        searchJob?.cancel()
        super.onCleared()
    }

    private companion object { const val TAG = "RideViewModel" }
}
