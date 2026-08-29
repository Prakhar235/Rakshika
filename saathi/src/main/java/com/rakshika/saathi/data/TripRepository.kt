package com.rakshika.saathi.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject

/**
 * Single source of truth for the whole app (UI + service both observe this).
 * Feed it raw Firebase trees via [onTree]; it exposes the parsed snapshot, a
 * running event log, and a hot [newEvent] stream the service turns into
 * system notifications.
 */
object TripRepository {

    private val _snapshot = MutableStateFlow<TripSnapshot?>(null)
    val snapshot: StateFlow<TripSnapshot?> = _snapshot

    private val _events = MutableStateFlow<List<TripEvent>>(emptyList())
    val events: StateFlow<List<TripEvent>> = _events

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _newEvent = MutableSharedFlow<TripEvent>(extraBufferCapacity = 16)
    val newEvent: SharedFlow<TripEvent> = _newEvent.asSharedFlow()

    // --- edge-detection state for event derivation ---
    private var prev: TripSnapshot? = null
    private var announcedStart = 0L
    private var announcedSos = false
    private var announcedHalf = false

    fun setConnected(value: Boolean) { _connected.value = value }

    fun onTree(tree: JSONObject?) {
        val next = TripSnapshot.from(tree)
        _snapshot.value = next
        derive(prev, next)
        prev = next
    }

    /** Wipe derived state (e.g. when the service restarts). */
    fun reset() {
        prev = null
        announcedStart = 0L
        announcedSos = false
        announcedHalf = false
    }

    private fun derive(old: TripSnapshot?, new: TripSnapshot?) {
        if (new == null) {
            if (old != null && old.active) emit(TripEvent.Kind.ENDED, "Ride ended", "${Config.COMPANION_NAME}'s trip is no longer sharing")
            announcedStart = 0L
            announcedSos = false
            announcedHalf = false
            return
        }

        if (new.riding && new.startedAt != announcedStart) {
            announcedStart = new.startedAt
            announcedSos = false
            announcedHalf = false
            val label = if (new.routeKind == "safe") "safest" else "faster"
            emit(
                TripEvent.Kind.START,
                "${Config.COMPANION_NAME} started a ride",
                "To ${new.destName}${areaSuffix(new)} · $label route, safety ${new.safetyScore}/100 · ETA ${new.etaMinutesLeft} min"
            )
        }

        if (new.sos && !announcedSos) {
            announcedSos = true
            emit(
                TripEvent.Kind.SOS,
                "🚨 SOS from ${Config.COMPANION_NAME}",
                "Triggered ${if (new.progress > 0) "${(new.progress * 100).toInt()}% into the ride" else "on the ride"} to ${new.destName}. Tap to view live location."
            )
        } else if (!new.sos && announcedSos) {
            announcedSos = false
            emit(TripEvent.Kind.INFO, "SOS cleared", "${Config.COMPANION_NAME} marked the SOS resolved")
        }

        if (new.riding && !announcedHalf && new.progress >= 0.5) {
            announcedHalf = true
            emit(TripEvent.Kind.PROGRESS, "Halfway there", "${new.etaMinutesLeft} min left to ${new.destName}")
        }

        if (new.arrived && old?.arrived != true) {
            emit(TripEvent.Kind.ARRIVED, "${Config.COMPANION_NAME} arrived safely", "Reached ${new.destName}${areaSuffix(new)}")
        }
    }

    private fun areaSuffix(s: TripSnapshot) = if (s.destArea.isBlank()) "" else " · ${s.destArea}"

    private fun emit(kind: TripEvent.Kind, title: String, detail: String) {
        val ev = TripEvent(System.currentTimeMillis(), kind, title, detail)
        _events.value = (listOf(ev) + _events.value).take(50)
        _newEvent.tryEmit(ev)
    }
}
