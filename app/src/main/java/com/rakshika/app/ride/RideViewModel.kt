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
import com.rakshika.app.risk.AssessmentRecord
import com.rakshika.app.risk.PredictionSource
import com.rakshika.app.risk.RiskLoop
import com.rakshika.app.risk.TripFeedback
import com.rakshika.app.routing.GeoRoute
import com.rakshika.app.routing.GoogleRouting
import com.rakshika.app.routing.ModelRiskScore
import com.rakshika.app.routing.RouteCorridor
import com.rakshika.app.routing.RouteScoring
import com.rakshika.app.routing.key
import com.rakshika.app.search.PlaceSearch
import com.rakshika.app.ui.mapkit.ROUTE_B
import com.rakshika.app.voice.Narrator
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

    private val live = LiveShareRepository(viewModelScope)
    private val contactsStore = ContactsStore(app)
    private val narrator = Narrator(app)
    private val riskLoop = RiskLoop(app)
    private var rideJob: Job? = null
    private var assessJob: Job? = null
    private var searchJob: Job? = null
    private var analysisJob: Job? = null

    /** The live routes behind the current assessment, kept for the background model analysis at ride start. */
    private var assessedRoutes: Map<String, GeoRoute> = emptyMap()

    /** Search-bias / routing origin: the device's real location once found, else the demo's fixed one. */
    private var originGeo: DoubleArray = LiveShareConfig.ORIGIN_GEO

    /** Firebase publish state + path, for the "Sharing live" chip on the ride screen. */
    val liveStatus = live.status
    val liveTripUrl = live.tripUrl

    init {
        // Rated trips whose learning call failed earlier (offline / model error) get another go, off the main flow.
        viewModelScope.launch { riskLoop.retryPendingLearning() }
    }

    /** Tries the device's last-known location (if permission was granted); falls back to the demo default. */
    fun refreshDeviceLocation() {
        val loc = DeviceLocation.lastKnown(getApplication())
        if (loc != null) {
            originGeo = loc
            // Anchors every mock-map fallback path (used whenever live routing has no real
            // geometry) onto the device's actual position, instead of the fixed MG Road demo spot.
            LiveShareConfig.setLiveOrigin(loc[0], loc[1])
            Log.i(TAG, "Using device location for search/routing: ${loc[0]},${loc[1]}")
            loadNearby(loc[0], loc[1])
        } else {
            Log.i(TAG, "No device location available (permission denied or no fix yet) — using the demo default origin")
        }
        _state.update { it.copy(usingDeviceLocation = loc != null) }
    }

    /** Fetches real named places actually near the device, to replace the static "Nearby" fallback list. */
    private fun loadNearby(lat: Double, lon: Double) {
        viewModelScope.launch {
            val results = PlaceSearch.nearby(getApplication(), lat, lon)
            if (results != null) _state.update { it.copy(nearbyResults = results) }
        }
    }

    /** Debounced free-text place search via the Places API, biased to [originGeo]. */
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
            val results = PlaceSearch.search(getApplication(), text, originGeo[0], originGeo[1])
            if (isActive) _state.update { it.copy(searchResults = results, searching = false) }
        }
    }

    fun selectDestination(place: Place) {
        _state.update { it.copy(destination = place) }
        runAssessment(place, keepStep = false)
    }

    fun selectRoute(safe: Boolean) {
        _state.update { it.copy(safeSelected = safe) }
        val route = _state.value.routes?.let { if (safe) it.safe else it.fast } ?: return
        narrator.say("Switched to the ${route.label.lowercase()} route, safety score ${route.safetyScore} out of 100.")
    }

    fun backToSearch() {
        rideJob?.cancel()
        assessJob?.cancel()
        analysisJob?.cancel()
        live.endTrip()
        narrator.stop()
        _state.update {
            RideState(
                query = it.query,
                nearbyResults = it.nearbyResults,
                usingDeviceLocation = it.usingDeviceLocation
            )
        }
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

            val lat = place.lat
            val lng = place.lng
            val geoRoutes = if (lat != null && lng != null) {
                Log.i(TAG, "Requesting live routes for \"${place.name}\" @ $lat,$lng")
                GoogleRouting.findRoutes(getApplication(), originGeo[0], originGeo[1], lat, lng)
            } else {
                Log.w(TAG, "\"${place.name}\" has no coordinates — skipping live routing, using the mock route")
                null
            }

            // The heuristic is pure math over the Directions response just fetched — it keeps
            // supplying the plain-language facts. The score itself comes from the current safety
            // equation, run on-device so picking a destination never waits on the network; the
            // model's deeper analysis runs in the background once the ride starts (see startRide).
            // With no live route to measure there's nothing to assess, so the heuristic stands alone.
            val rawScores = RouteScoring.rawScores(geoRoutes)
            val liveRoutes = buildMap {
                geoRoutes?.mainRoad?.let { put(RouteCorridor.MAIN_ROAD.key, it) }
                geoRoutes?.backLane?.let { put(RouteCorridor.BACK_LANE.key, it) }
            }
            assessedRoutes = liveRoutes
            val assessment = if (liveRoutes.isNotEmpty()) riskLoop.assessLocal(liveRoutes) else null
            val finalScores = if (assessment != null) {
                RouteScoring.withModelScores(rawScores, assessment.toModelScores())
            } else {
                rawScores
            }
            val comparison = RouteScoring.finalize(finalScores)
            val routes = routePairFrom(comparison, geoRoutes)

            val accuracy = riskLoop.accuracy()
            narrator.say(routes.summary)

            _state.update {
                it.copy(
                    step = if (keepStep) it.step else RideStep.ROUTES,
                    assessing = false,
                    routes = routes,
                    safeSelected = true,
                    assessment = assessment,
                    accuracy = accuracy
                )
            }
        }
    }

    private fun AssessmentRecord.toModelScores(): Map<String, ModelRiskScore> = corridors.associate {
        it.id to ModelRiskScore(it.id, it.predictedScore, it.reason, fromModel = it.source == PredictionSource.MODEL)
    }

    /** Starts the ride: contacts are notified immediately, then the dot walks the chosen route in real time. */
    fun startRide() {
        val routes = _state.value.routes ?: return
        val chosen = if (_state.value.safeSelected) routes.safe else routes.fast

        // Remember which corridor was actually taken (the post-trip feedback is about that one), and
        // run the model's deeper analysis while the ride is under way — ready by the time it ends.
        _state.value.assessment?.let { startBackgroundAnalysis(it, chosen.corridor.key) }

        _state.update {
            it.copy(
                assessment = it.assessment?.copy(chosenCorridor = chosen.corridor.key),
                aiAnalyzing = it.assessment != null,
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
            narrator.say(
                "Starting your ride on the ${chosen.label.lowercase()} route to ${destination.name}, " +
                    "about ${chosen.minutes} minutes away. Amma and Rohan have been notified."
            )
        }

        runRideLoop(chosen, path)
    }

    /**
     * Recalculates the route from wherever the marker currently is and resumes the ride on the
     * new path. With live destination coordinates this asks Valhalla for a fresh route starting
     * at the marker's position; without them (a coordinate-less demo destination) it falls back
     * to just the remaining stretch of the current mock path, re-based to progress 0.
     */
    fun reroute() {
        val routes = _state.value.routes ?: return
        val destination = _state.value.destination ?: return
        if (_state.value.rerouting) return

        val fromPath = chosenGeoPath()
        val progress = _state.value.rideProgress
        val here = geoPointAt(fromPath, progress)

        narrator.say("Recalculating your route from your current location.")
        _state.update { it.copy(rerouting = true) }

        viewModelScope.launch {
            val destLat = destination.lat
            val destLng = destination.lng
            val geoRoutes = if (destLat != null && destLng != null) {
                Log.i(TAG, "Rerouting live from ${here[0]},${here[1]} -> $destLat,$destLng")
                GoogleRouting.findRoutes(getApplication(), here[0], here[1], destLat, destLng)
            } else {
                Log.w(TAG, "Rerouting \"${destination.name}\" without live coordinates — trimming the mock path instead")
                null
            }

            // Both lines are always redrawn from `here`: a corridor Valhalla actually found gets
            // fresh routed geometry, any other corridor falls back to trimming its own current path.
            val newRoutes = RoutePair(
                fast = routes.fast.rerouted(geoRoutes, progress),
                safe = routes.safe.rerouted(geoRoutes, progress),
                summary = routes.summary
            )
            val newChosen = if (_state.value.safeSelected) newRoutes.safe else newRoutes.fast
            val newPath = newChosen.resolvedGeoPath()

            rideJob?.cancel()
            _state.update {
                it.copy(
                    routes = newRoutes,
                    rideProgress = 0f,
                    etaMinutesLeft = newChosen.minutes,
                    rerouting = false
                )
            }
            live.startTrip(ORIGIN, destination, newChosen, _state.value.safeSelected, newPath, newChosen.minutes)
            narrator.say("Rerouted via the ${newChosen.label.lowercase()}, about ${newChosen.minutes} minutes to go.")
            runRideLoop(newChosen, newPath)
        }
    }

    /** Walks the dot along [path] in real time, notifying contacts/Firebase along the way, then marks arrival. */
    private fun runRideLoop(chosen: RouteOption, path: List<DoubleArray>) {
        rideJob = viewModelScope.launch {
            val totalSteps = 90
            // The whole ride is compressed to RIDE_DURATION_MS of screen time regardless of the mocked ETA.
            val stepMs = RIDE_DURATION_MS / totalSteps
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
                narrator.say("You have arrived safely at ${it.name}. Amma and Rohan have been notified.")
            }
            _state.update { it.copy(step = RideStep.ARRIVED, rideProgress = 1f, etaMinutesLeft = 0) }
        }
    }

    /**
     * The slow part of the loop, off the ride flow: the model gets the stored measurements (and may
     * fetch OSM / weather / Places data) and upgrades the assessment with its modified equation,
     * confidence and explanation. The on-screen route scores are left alone; if the model is
     * unreachable the on-device assessment simply stays, and the trip can still be rated.
     */
    private fun startBackgroundAnalysis(assessment: AssessmentRecord, corridorKey: String) {
        analysisJob?.cancel()
        val routes = assessedRoutes
        analysisJob = viewModelScope.launch {
            riskLoop.recordChoice(assessment.id, corridorKey)
            val refined = riskLoop.refineWithModel(assessment.id, routes)
            val accuracy = riskLoop.accuracy()
            _state.update {
                if (it.assessment?.id != assessment.id) it
                else it.copy(aiAnalyzing = false, assessment = refined ?: it.assessment, accuracy = accuracy)
            }
        }
    }

    /**
     * Sends the rider's post-trip ratings — [overall] (1-5) and per-input [perFeature] (1-5, keyed by
     * feature) — to the model along with the stored assessment, to learn a better equation. The
     * feedback is saved first, so if the model call fails it is retried on the next search.
     */
    fun submitFeedback(overall: Int, perFeature: Map<String, Int>) {
        val assessment = _state.value.assessment ?: return
        if (_state.value.feedbackSubmitting || _state.value.aiAnalyzing || assessment.feedback != null) return
        val feedback = TripFeedback(overall.coerceIn(1, 5), perFeature, System.currentTimeMillis())

        _state.update { it.copy(feedbackSubmitting = true, assessment = assessment.copy(feedback = feedback)) }
        viewModelScope.launch {
            val updated = riskLoop.submitFeedback(assessment.id, feedback)
            val accuracy = riskLoop.accuracy()
            // The rider may have moved on to a new ride while the model was thinking.
            _state.update {
                if (it.assessment?.id != assessment.id) it
                else it.copy(feedbackSubmitting = false, assessment = updated ?: it.assessment, accuracy = accuracy)
            }
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
        narrator.say("S.O.S. sent. Rohan and Amma have been notified with your current location.")
        _state.update { it.copy(sosActive = true, contactRohan = ContactStatus.RESPONDING) }
    }

    fun newRide() {
        rideJob?.cancel()
        assessJob?.cancel()
        analysisJob?.cancel()
        live.endTrip()
        narrator.stop()
        _state.update {
            RideState(
                nearbyResults = it.nearbyResults,
                usingDeviceLocation = it.usingDeviceLocation
            )
        }
    }

    override fun onCleared() {
        rideJob?.cancel()
        assessJob?.cancel()
        searchJob?.cancel()
        analysisJob?.cancel()
        narrator.shutdown()
        super.onCleared()
    }

    private companion object {
        const val TAG = "RideViewModel"
        const val RIDE_DURATION_MS = 2 * 60 * 1000L
    }
}
