package com.rakshika.app.data.repository

import com.rakshika.app.data.model.AlertEvent
import com.rakshika.app.data.model.EmergencyContact
import com.rakshika.app.data.model.EventType

/**
 * Static seed data so the demo doesn't open to empty screens.
 * Replace with Firestore-backed repositories for the real build.
 */
object DemoSeedData {

    fun contacts() = listOf(
        EmergencyContact("c1", "Mom", "+91 98xxxxxx01", "Mother"),
        EmergencyContact("c2", "Aditi Sharma", "+91 98xxxxxx02", "Sister"),
        EmergencyContact("c3", "Rohan Verma", "+91 98xxxxxx03", "Friend")
    )

    fun events(): List<AlertEvent> {
        val now = System.currentTimeMillis()
        val hour = 60 * 60 * 1000L
        return listOf(
            AlertEvent("e1", EventType.LOCATION_SHARED, now - hour * 3, "Shared live location with Mom"),
            AlertEvent("e2", EventType.CHECK_IN_STARTED, now - hour * 20, "20 min check-in before evening walk"),
            AlertEvent("e3", EventType.CHECK_IN_CANCELLED, now - hour * 20 + 15 * 60 * 1000, "Checked in safe"),
            AlertEvent("e4", EventType.SOS_TRIGGERED, now - hour * 48, "Test alert sent to 3 contacts")
        ).sortedByDescending { it.timestamp }
    }
}
