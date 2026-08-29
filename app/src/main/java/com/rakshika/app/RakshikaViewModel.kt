package com.rakshika.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rakshika.app.alerts.AlertMessages
import com.rakshika.app.alerts.Connectivity
import com.rakshika.app.alerts.ContactsStore
import com.rakshika.app.alerts.SmsAlerts
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
    val contacts: List<EmergencyContact> = emptyList(),
    val smsPermissionGranted: Boolean = false,
    val events: List<AlertEvent> = DemoSeedData.events(),
    val checkInActive: Boolean = false,
    val checkInTotalSeconds: Int = 0,
    val checkInSecondsLeft: Int = 0,
    val sosJustTriggered: Boolean = false,
    val fakeCallRinging: Boolean = false
)

class RakshikaViewModel(app: Application) : AndroidViewModel(app) {

    private val contactsStore = ContactsStore(app)

    private val _uiState = MutableStateFlow(
        RakshikaUiState(
            contacts = contactsStore.load(),
            smsPermissionGranted = SmsAlerts.hasPermission(app)
        )
    )
    val uiState: StateFlow<RakshikaUiState> = _uiState

    private var checkInJob: Job? = null

    /** Called by the Contacts screen after the SEND_SMS permission dialog. */
    fun refreshSmsPermission() {
        _uiState.update { it.copy(smsPermissionGranted = SmsAlerts.hasPermission(getApplication())) }
    }

    fun toggleOnlineMode() {
        _uiState.update { it.copy(isOnline = !it.isOnline) }
        // Let the toggle force the SMS-fallback path so it can be demoed without cutting data.
        Connectivity.demoForceOffline = !_uiState.value.isOnline
    }

    fun triggerSos(note: String = "") {
        val result = SmsAlerts.send(getApplication(), contactsStore.recipients(), AlertMessages.sosHome())
        val reason = if (note.isNotBlank()) note else "SOS triggered · ${result.summary}"

        addEvent(EventType.SOS_TRIGGERED, reason)
        _uiState.update { it.copy(sosJustTriggered = true) }
        viewModelScope.launch {
            delay(2500)
            _uiState.update { it.copy(sosJustTriggered = false) }
        }
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
                val result = SmsAlerts.send(
                    getApplication(), contactsStore.recipients(), AlertMessages.missedCheckIn()
                )
                addEvent(EventType.CHECK_IN_MISSED, "No check-in received · ${result.summary}")
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
        updateContacts { it + newContact }
    }

    fun removeContact(id: String) {
        updateContacts { list -> list.filterNot { it.id == id } }
    }

    fun setContactAlerts(id: String, enabled: Boolean) {
        updateContacts { list -> list.map { if (it.id == id) it.copy(alertsEnabled = enabled) else it } }
    }

    private fun updateContacts(transform: (List<EmergencyContact>) -> List<EmergencyContact>) {
        _uiState.update { state ->
            val next = transform(state.contacts)
            contactsStore.save(next)
            state.copy(contacts = next)
        }
    }

    private fun addEvent(type: EventType, note: String) {
        val event = AlertEvent(UUID.randomUUID().toString(), type, note = note)
        _uiState.update { it.copy(events = listOf(event) + it.events) }
    }
}
