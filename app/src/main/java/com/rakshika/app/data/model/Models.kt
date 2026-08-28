package com.rakshika.app.data.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class EmergencyContact(
    val id: String,
    val name: String,
    val phone: String,
    val relation: String
)

enum class EventType {
    SOS_TRIGGERED,
    CHECK_IN_STARTED,
    CHECK_IN_CANCELLED,
    CHECK_IN_MISSED,
    LOCATION_SHARED
}

data class AlertEvent(
    val id: String,
    val type: EventType,
    val timestamp: Long = System.currentTimeMillis(),
    val note: String = ""
) {
    fun formattedTime(): String =
        SimpleDateFormat("hh:mm a, dd MMM", Locale.getDefault()).format(Date(timestamp))
}

enum class ContactStatus { NOTIFIED, SEEN, RESPONDING }
