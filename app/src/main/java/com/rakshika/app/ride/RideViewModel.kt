package com.rakshika.app.ride

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rakshika.app.alerts.AlertMessages
import com.rakshika.app.alerts.ContactsStore
import com.rakshika.app.alerts.SmsAlerts
import com.rakshika.app.data.model.ContactStatus
import com.rakshika.app.live.LiveShareConfig
import com.rakshika.app.live.LiveShareRepository
import com.rakshika.app.rag.RagRouteEngine
import com.rakshika.app.rag.RouteCorridor
import com.rakshika.app.rag.SafetyDatasets
import com.rakshika.app.search.PlaceSearch
import com.rakshika.app.ui.mapkit.ROUTE_A
import com.rakshika.app.ui.mapkit.ROUTE_B
import com.rakshika.app.ui.mapkit.pointAt
import kotlinx.coroutines.Job
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

    /** Firebase publish state + path, for the "Sharing live" chip on the ride screen. */
    val liveStatus = live.status
    val liveTripUrl = live.tripUrl

    /** Debounced free-text place search via Photon (OSM geocoder), biased to the demo's fixed origin. */
    fun updateQuery(text: String) {
        _state.update { it.copy(query = text) }
        searchJob?.cancel()
        if (text.isBlank()) {
            _state.update { it.copy(searchResults = null, searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            _state.update { it.copy(searching = true) }
            val origin = LiveShareConfig.ORIGIN_GEO
            val results = PlaceSearch.search(text, origin[0], origin[1])
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

    /** The mock-map polyline the chosen corridor walks — ROUTE_B is the main road, ROUTE_A the back lane. */
    private fun chosenPath() =
        if (currentChoice()?.corridor != RouteCorridor.BACK_LANE) ROUTE_B else ROUTE_A

    private fun currentChoice() = _state.value.routes?.let {
        if (_state.value.safeSelected) it.safe else it.fast
    }

    /** Real lat/lng of a point [t] (0..1) along [path], via the shared map bounding box. */
    private fun geoAt(path: List<androidx.compose.ui.geometry.Offset>, t: Float): DoubleArray =
        LiveShareConfig.toGeo(pointAt(path, 1f, 1f, t))

    private fun runAssessment(place: Place, keepStep: Boolean) {
        assessJob?.cancel()
        assessJob = viewModelScope.launch {
            _state.update { it.copy(assessing = true) }
            // Small pause so the "embedding query · retrieving notes" step is visible.
            delay(if (keepStep) 280L else 460L)
            val dataset = SafetyDatasets.byId(_state.value.selectedDatasetId)
            val result = engine.assess("${ORIGIN.name} ${ORIGIN.area}", "${place.name} ${place.area}", dataset)
            _state.update {
                it.copy(
                    step = if (keepStep) it.step else RideStep.ROUTES,
                    assessing = false,
                    rag = result,
                    routes = routePairFrom(result),
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

        val path = chosenPath()
        val destination = _state.value.destination
        if (destination != null) {
            live.startTrip(ORIGIN, destination, chosen, _state.value.safeSelected, path, chosen.minutes)
            val d = geoAt(path, 1f)
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
        val path = chosenPath()
        val here = geoAt(path, _state.value.rideProgress)
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
}
