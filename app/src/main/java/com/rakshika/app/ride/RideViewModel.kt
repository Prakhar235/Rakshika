package com.rakshika.app.ride

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rakshika.app.data.model.ContactStatus
import com.rakshika.app.live.LiveShareRepository
import com.rakshika.app.rag.RagRouteEngine
import com.rakshika.app.rag.RouteCorridor
import com.rakshika.app.rag.SafetyDatasets
import com.rakshika.app.ui.mapkit.ROUTE_A
import com.rakshika.app.ui.mapkit.ROUTE_B
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RideViewModel : ViewModel() {
    private val _state = MutableStateFlow(RideState())
    val state: StateFlow<RideState> = _state

    private val engine = RagRouteEngine()
    private val live = LiveShareRepository(viewModelScope)
    private var rideJob: Job? = null
    private var assessJob: Job? = null

    /** Firebase publish state + path, for the "Sharing live" chip on the ride screen. */
    val liveStatus = live.status
    val liveTripUrl = live.tripUrl

    fun updateQuery(text: String) {
        _state.update { it.copy(query = text) }
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
            _state.update { it.copy(step = RideStep.ARRIVED, rideProgress = 1f, etaMinutesLeft = 0) }
        }
    }

    fun triggerSos() {
        live.setSos(true)
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
        super.onCleared()
    }
}
