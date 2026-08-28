package com.rakshika.app.ride

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rakshika.app.data.model.ContactStatus
import com.rakshika.app.rag.RagRouteEngine
import com.rakshika.app.rag.SafetyDatasets
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
    private var rideJob: Job? = null
    private var assessJob: Job? = null

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
        _state.update { RideState(query = it.query, selectedDatasetId = it.selectedDatasetId) }
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
                delay(stepMs)
                step++
            }
            _state.update { it.copy(step = RideStep.ARRIVED, rideProgress = 1f, etaMinutesLeft = 0) }
        }
    }

    fun triggerSos() {
        _state.update { it.copy(sosActive = true, contactRohan = ContactStatus.RESPONDING) }
    }

    fun newRide() {
        rideJob?.cancel()
        assessJob?.cancel()
        _state.update { RideState(selectedDatasetId = it.selectedDatasetId) }
    }

    override fun onCleared() {
        rideJob?.cancel()
        assessJob?.cancel()
        super.onCleared()
    }
}
