package com.rakshika.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rakshika.app.data.model.AlertEvent
import com.rakshika.app.data.model.EmergencyContact
import com.rakshika.app.data.model.EventType
import com.rakshika.app.data.repository.DemoSeedData
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class RakshikaUiState(
    val isOnline: Boolean = true,
    val contacts: List<EmergencyContact> = DemoSeedData.contacts(),
    val events: List<AlertEvent> = DemoSeedData.events(),
    val checkInActive: Boolean = false,
    val checkInTotalSeconds: Int = 0,
    val checkInSecondsLeft: Int = 0,
    val sosJustTriggered: Boolean = false,
    val fakeCallRinging: Boolean = false
)

class RakshikaViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(RakshikaUiState())
    val uiState: StateFlow<RakshikaUiState> = _uiState

    private var checkInJob: Job? = null

    fun toggleOnlineMode() {
        _uiState.update { it.copy(isOnline = !it.isOnline) }
    }

    fun triggerSos(note: String = "") {
        val reason = if (note.isNotBlank()) note
        else if (_uiState.value.isOnline) "SOS sent via push notification"
        else "SOS sent via SMS fallback (offline)"

        addEvent(EventType.SOS_TRIGGERED, reason)
        _uiState.update { it.copy(sosJustTriggered = true) }
        viewModelScope.launch {
            delay(2500)
            _uiState.update { it.copy(sosJustTriggered = false) }
        }
        // A real SOS also cancels any running check-in.
        cancelCheckIn(auto = false, note = "")
    }

    fun shareLocation() {
        addEvent(EventType.LOCATION_SHARED, "Live location link shared with all contacts")
    }

    fun startCheckIn(minutes: Int) {
        checkInJob?.cancel()
        val totalSeconds = minutes * 60
        _uiState.update {
            it.copy(
                checkInActive = true,
                checkInTotalSeconds = totalSeconds,
                checkInSecondsLeft = totalSeconds
            )
        }
        addEvent(EventType.CHECK_IN_STARTED, "$minutes min check-in started")

        checkInJob = viewModelScope.launch {
            while (_uiState.value.checkInSecondsLeft > 0) {
                delay(1000)
                _uiState.update { it.copy(checkInSecondsLeft = (it.checkInSecondsLeft - 1).coerceAtLeast(0)) }
            }
            if (_uiState.value.checkInActive) {
                // Timer ran out without the user cancelling -> treat as missed check-in.
                addEvent(EventType.CHECK_IN_MISSED, "No check-in received, contacts notified")
                _uiState.update { it.copy(checkInActive = false) }
            }
        }
    }

    fun cancelCheckIn(auto: Boolean = true, note: String = "Checked in safe") {
        if (!_uiState.value.checkInActive) return
        checkInJob?.cancel()
        _uiState.update { it.copy(checkInActive = false, checkInSecondsLeft = 0) }
        if (note.isNotBlank()) {
            addEvent(EventType.CHECK_IN_CANCELLED, note)
        }
    }

    fun startFakeCall() {
        _uiState.update { it.copy(fakeCallRinging = true) }
    }

    fun endFakeCall() {
        _uiState.update { it.copy(fakeCallRinging = false) }
    }

    fun addContact(name: String, phone: String, relation: String) {
        val newContact = EmergencyContact(UUID.randomUUID().toString(), name, phone, relation)
        _uiState.update { it.copy(contacts = it.contacts + newContact) }
    }

    fun removeContact(id: String) {
        _uiState.update { it.copy(contacts = it.contacts.filterNot { c -> c.id == id }) }
    }

    private fun addEvent(type: EventType, note: String) {
        val event = AlertEvent(UUID.randomUUID().toString(), type, note = note)
        _uiState.update { it.copy(events = listOf(event) + it.events) }
    }
}
