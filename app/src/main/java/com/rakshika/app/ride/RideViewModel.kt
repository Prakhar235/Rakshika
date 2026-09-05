package com.rakshika.app.ride

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import com.rakshika.app.alerts.AlertMessages
import com.rakshika.app.alerts.ContactsStore
import com.rakshika.app.alerts.SmsAlerts
import com.rakshika.app.data.model.ContactStatus
import com.rakshika.app.geo.DeviceLocation
import com.rakshika.app.geo.DirectionsRepository
import com.rakshika.app.geo.GeoPath
import com.rakshika.app.geo.GeoRoute
import com.rakshika.app.geo.MapsConfig
import com.rakshika.app.geo.PlaceSuggestion
import com.rakshika.app.geo.PlacesSearch
import com.rakshika.app.live.LiveShareRepository
import com.rakshika.app.rag.RagRouteEngine
import com.rakshika.app.rag.RouteCorridor
import com.rakshika.app.rag.SafetyDatasets
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

class RideViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(RideState())
    val state: StateFlow<RideState> = _state

    private val engine = RagRouteEngine()
    private val live = LiveShareRepository(viewModelScope)
    private val contactsStore = ContactsStore(app)
    private var rideJob: Job? = null
    private var assessJob: Job? = null
    private var searchJob: Job? = null

    // Places Autocomplete billing is per session, not per keystroke — one token per search,
    // refreshed once a place is actually picked.
    private var sessionToken = UUID.randomUUID().toString()

    // The real routes fetched for the current destination, cached so switching the safety
    // dataset (selectDataset) re-scores instantly without re-calling Directions.
    private var cachedGeoRoutes: Map<RouteCorridor, GeoRoute>? = null
    private var cachedForDestination: Place? = null

    /** Firebase publish state + path, for the "Sharing live" chip on the ride screen. */
    val liveStatus = live.status
    val liveTripUrl = live.tripUrl

    init {
        locateOrigin()
    }

    /** Resolves the real starting point via device location, falling back to a fixed real coordinate. */
    fun locateOrigin() {
        viewModelScope.launch {
            _state.update { it.copy(locatingOrigin = true) }
            val fix = DeviceLocation.lastKnown(getApplication()) ?: MapsConfig.FALLBACK_ORIGIN
            val label = DeviceLocation.reverseGeocode(fix)
            _state.update {
                it.copy(
                    origin = Place(
                        name = label?.substringBefore(",")?.trim()?.ifBlank { null } ?: "Current location",
                        area = label ?: "Approximate location",
                        lat = fix.latitude,
                        lng = fix.longitude
                    ),
                    locatingOrigin = false
                )
            }
        }
    }

    fun onLocationPermissionResult(granted: Boolean) {
        _state.update { it.copy(locationPermissionGranted = granted) }
        if (granted) locateOrigin()
    }

    /** Debounced real Places Autocomplete search. */
    fun updateQuery(text: String) {
        _state.update { it.copy(query = text) }
        searchJob?.cancel()
        if (text.isBlank()) {
            _state.update { it.copy(suggestions = emptyList(), searching = false, searchError = null) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(300L)
            _state.update { it.copy(searching = true, searchError = null) }
            val results = PlacesSearch.autocomplete(text, sessionToken, _state.value.origin?.latLng)
            _state.update {
                it.copy(
                    suggestions = results,
                    searching = false,
                    searchError = when {
                        results.isNotEmpty() -> null
                        !MapsConfig.isConfigured -> "Maps API key not set — see local.properties."
                        else -> "No matches — try another name."
                    }
                )
            }
        }
    }

    fun selectSuggestion(suggestion: PlaceSuggestion) {
        viewModelScope.launch {
            _state.update { it.copy(searching = true, searchError = null) }
            val latLng = PlacesSearch.fetchLatLng(suggestion.placeId, sessionToken)
            sessionToken = UUID.randomUUID().toString()
            if (latLng == null) {
                _state.update { it.copy(searching = false, searchError = "Couldn't look up that place — try again.") }
                return@launch
            }
            _state.update { it.copy(searching = false, suggestions = emptyList(), query = suggestion.primaryText) }
            selectDestination(
                Place(name = suggestion.primaryText, area = suggestion.secondaryText, lat = latLng.latitude, lng = latLng.longitude)
            )
        }
    }

    /** Pick which safety dataset the on-device RAG retrieves from; re-scores the cached real routes. */
    fun selectDataset(id: String) {
        if (_state.value.selectedDatasetId == id) return
        _state.update { it.copy(selectedDatasetId = id) }
        _state.value.destination?.let { runAssessment(it, keepStep = true) }
    }

    fun selectDestination(place: Place) {
        cachedGeoRoutes = null
        cachedForDestination = null
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
        _state.update {
            RideState(
                query = it.query,
                selectedDatasetId = it.selectedDatasetId,
                origin = it.origin,
                locationPermissionGranted = it.locationPermissionGranted
            )
        }
    }

    /** The real Directions polyline the chosen route walks. */
    private fun chosenPath(): List<LatLng> = currentChoice()?.path.orEmpty()

    private fun currentChoice() = _state.value.routes?.let {
        if (_state.value.safeSelected) it.safe else it.fast
    }

    private fun geoAt(path: List<LatLng>, t: Float): LatLng = GeoPath.pointAt(path, t)

    /** Fetches (or reuses) real routes for [place], then re-runs the on-device RAG scoring over them. */
    private fun runAssessment(place: Place, keepStep: Boolean) {
        assessJob?.cancel()
        assessJob = viewModelScope.launch {
            _state.update { it.copy(assessing = true, searchError = null) }
            val origin = _state.value.origin ?: Place("Current location", "Approximate location", MapsConfig.FALLBACK_ORIGIN.latitude, MapsConfig.FALLBACK_ORIGIN.longitude)

            val geoRoutes = cachedGeoRoutes?.takeIf { cachedForDestination == place } ?: run {
                // Small pause so the "embedding query · retrieving notes" step is visible even
                // when Directions answers instantly.
                delay(if (keepStep) 0L else 200L)
                val fetched = fetchGeoRoutes(origin.latLng, place.latLng)
                cachedGeoRoutes = fetched
                cachedForDestination = place
                fetched
            }

            if (geoRoutes.isEmpty()) {
                _state.update {
                    it.copy(
                        assessing = false,
                        searchError = if (!MapsConfig.isConfigured)
                            "Maps API key not set — see local.properties."
                        else
                            "Couldn't fetch a real route to ${place.name} — check connectivity and try again."
                    )
                }
                return@launch
            }

            val dataset = SafetyDatasets.byId(_state.value.selectedDatasetId)
            val realEtas = geoRoutes.mapValues { it.value.minutes }
            val result = engine.assess("${origin.name} ${origin.area}", "${place.name} ${place.area}", dataset, realEtas)
            val paths = geoRoutes.mapValues { it.value.points }

            _state.update {
                it.copy(
                    step = if (keepStep) it.step else RideStep.ROUTES,
                    assessing = false,
                    rag = result,
                    routes = routePairFrom(result, paths),
                    safeSelected = true
                )
            }
        }
    }

    /** Two real walking-route alternatives, mapped onto the RAG engine's two corridors by duration. */
    private suspend fun fetchGeoRoutes(origin: LatLng, destination: LatLng): Map<RouteCorridor, GeoRoute> {
        val routes = DirectionsRepository.fetchRoutes(origin, destination)
        if (routes.isEmpty()) return emptyMap()
        val sorted = routes.sortedBy { it.minutes }
        val faster = sorted.first()
        val slower = sorted.last() // same route as faster if Directions only returned one alternative
        return mapOf(RouteCorridor.BACK_LANE to faster, RouteCorridor.MAIN_ROAD to slower)
    }

    /** Starts the ride: contacts are notified immediately, then the dot walks the chosen real route live. */
    fun startRide() {
        val routes = _state.value.routes ?: return
        val chosen = if (_state.value.safeSelected) routes.safe else routes.fast
        val origin = _state.value.origin ?: return

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
            live.startTrip(origin, destination, chosen, _state.value.safeSelected, path, chosen.minutes)
            val d = geoAt(path, 1f)
            SmsAlerts.send(
                getApplication(),
                contactsStore.recipients(),
                AlertMessages.rideStarted(destination.name, chosen.label, chosen.minutes, d.latitude, d.longitude)
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
            AlertMessages.sosRide(destination, here.latitude, here.longitude)
        )
        _state.update { it.copy(sosActive = true, contactRohan = ContactStatus.RESPONDING) }
    }

    fun newRide() {
        rideJob?.cancel()
        assessJob?.cancel()
        live.endTrip()
        _state.update {
            RideState(
                selectedDatasetId = it.selectedDatasetId,
                origin = it.origin,
                locationPermissionGranted = it.locationPermissionGranted
            )
        }
    }

    override fun onCleared() {
        rideJob?.cancel()
        assessJob?.cancel()
        searchJob?.cancel()
        super.onCleared()
    }
}
