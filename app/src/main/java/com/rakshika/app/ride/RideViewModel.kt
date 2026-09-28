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
import com.rakshika.app.risk.RiskPatches
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.rakshika.app.geo.haversineMeters
import com.rakshika.app.geo.projectOnPath
import kotlin.math.ceil

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
    private var patchesJob: Job? = null

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
        RiskPatches.useDiskCache(app.filesDir)
    }

    /**
     * Switches this instance to the real app's behaviour: the ride follows the phone's GPS rather than
     * a simulated walk, the start is "Your location", and alerts name the saved emergency contacts.
     */
    fun enableRealMotion() {
        if (_state.value.realMotion) return
        _state.update { it.copy(realMotion = true, alertContacts = contactNames()) }
    }

    /** Tries the device's last-known location (if permission was granted); falls back to the demo default. */
    fun refreshDeviceLocation() {
        val loc = DeviceLocation.lastKnown(getApplication())
        if (loc != null) {
            useOrigin(loc)
        } else if (_state.value.realMotion && DeviceLocation.hasPermission(getApplication())) {
            // No cached fix (fresh boot, GPS just enabled) — the real app waits for a live one
            // rather than quietly routing from the demo's default spot.
            _state.update { it.copy(waitingForFix = true) }
            viewModelScope.launch {
                val fix = withTimeoutOrNull(FIRST_FIX_TIMEOUT_MS) { DeviceLocation.updates(getApplication()).first() }
                _state.update { it.copy(waitingForFix = false) }
                if (fix != null) useOrigin(doubleArrayOf(fix.latitude, fix.longitude))
            }
        } else {
            Log.i(TAG, "No device location available (permission denied or no fix yet) — using the demo default origin")
        }
        _state.update { it.copy(usingDeviceLocation = loc != null) }
    }

    private fun useOrigin(loc: DoubleArray) {
        originGeo = loc
        // Anchors every mock-map fallback path (used whenever live routing has no real
        // geometry) onto the device's actual position, instead of the fixed MG Road demo spot.
        LiveShareConfig.setLiveOrigin(loc[0], loc[1])
        Log.i(TAG, "Using device location for search/routing: ${loc[0]},${loc[1]}")
        loadNearby(loc[0], loc[1])
        // Get the area's risk data in before she picks a destination — the public servers can be slow.
        viewModelScope.launch { RiskPatches.prefetchAround(loc[0], loc[1]) }
        _state.update { it.copy(usingDeviceLocation = true) }
    }

    private fun contactNames(): List<String> = contactsStore.load().filter { it.alertsEnabled }.map { it.name }

    /** Where the trip starts, as shown to contacts: the demo's fixed hostel, or the rider's real position. */
    private fun originPlace(): Place = if (_state.value.realMotion) Place("Your location", "") else ORIGIN

    /** The narrator's "who was told" line — the demo's scripted contacts, or the real saved ones. */
    private fun notifiedSentence(): String {
        if (!_state.value.realMotion) return "Amma and Rohan have been notified."
        val names = _state.value.alertContacts
        return when (names.size) {
            0 -> "No emergency contacts are set up to alert."
            1 -> "${names[0]} has been notified."
            else -> names.dropLast(1).joinToString(", ") + " and ${names.last()} have been notified."
        }
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
        val target = _state.value.routes?.let { if (safe) it.safe else it.fast } ?: return
        if (!_state.value.isWalkable(target)) return
        _state.update { it.copy(safeSelected = safe) }
        val route = _state.value.routes?.let { if (safe) it.safe else it.fast } ?: return
        narrator.say("Switched to the ${route.label.lowercase()} route, safety score ${route.safetyScore} out of 100.")
    }

    fun backToSearch() {
        rideJob?.cancel()
        assessJob?.cancel()
        analysisJob?.cancel()
        patchesJob?.cancel()
        live.endTrip()
        narrator.stop()
        _state.update {
            RideState(
                query = it.query,
                nearbyResults = it.nearbyResults,
                usingDeviceLocation = it.usingDeviceLocation,
                realMotion = it.realMotion,
                alertContacts = it.alertContacts
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

            // The real app routes from wherever the phone is now, not where it was when search opened.
            if (_state.value.realMotion) DeviceLocation.lastKnown(getApplication())?.let { originGeo = it }
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
                    // Recommended route by default — unless, in the real app, it has no real road to follow.
                    safeSelected = !it.realMotion || routes.safe.geoPath != null || routes.fast.geoPath == null,
                    assessment = assessment,
                    accuracy = accuracy
                )
            }
            loadRiskPatches(routes)
        }
    }

    /** Tries the risk-patch lookup again for the routes on screen — after OSM's servers were unreachable. */
    fun retryRiskPatches() {
        val routes = _state.value.routes ?: return
        if (_state.value.patchesLoading) return
        loadRiskPatches(routes)
    }

    /** Looks up OSM-flagged dark / isolated stretches on both routes, off the main flow — the map fills them in when ready. */
    private fun loadRiskPatches(routes: RoutePair) {
        patchesJob?.cancel()
        _state.update { it.copy(patchesLoading = true, riskPatches = emptyMap()) }
        patchesJob = viewModelScope.launch {
            // Only real roads are checked — in the real app the stand-in path isn't somewhere she'll walk.
            val found = RiskPatches.forRoutes(
                listOf(routes.safe, routes.fast)
                    .filter { r -> _state.value.isWalkable(r) }
                    .associate { it.corridor.key to it.resolvedGeoPath() }
            )
            Log.i(TAG, "Risk patches: " + found.entries.joinToString { (k, v) -> "$k=${v?.size ?: "failed"}" })
            _state.update { it.copy(riskPatches = found, patchesLoading = false) }
        }
    }

    private fun AssessmentRecord.toModelScores(): Map<String, ModelRiskScore> = corridors.associate {
        it.id to ModelRiskScore(it.id, it.predictedScore, it.reason, fromModel = it.source == PredictionSource.MODEL)
    }

    /** Starts the ride: contacts are notified immediately, then the dot walks the chosen route in real time. */
    fun startRide() {
        val routes = _state.value.routes ?: return
        val chosen = if (_state.value.safeSelected) routes.safe else routes.fast
        if (!_state.value.isWalkable(chosen)) return

        // Remember which corridor was actually taken (the post-trip feedback is about that one), and
        // run the model's deeper analysis while the ride is under way — ready by the time it ends.
        _state.value.assessment?.let { startBackgroundAnalysis(it, chosen.corridor.key) }

        val real = _state.value.realMotion
        _state.update {
            it.copy(
                assessment = it.assessment?.copy(chosenCorridor = chosen.corridor.key),
                aiAnalyzing = it.assessment != null,
                step = RideStep.RIDING,
                rideProgress = 0f,
                etaMinutesLeft = chosen.minutes,
                sharingLive = true,
                // The demo scripts two contacts' read receipts; the real app only knows who it texted.
                contactAmma = if (real) null else ContactStatus.NOTIFIED,
                contactRohan = if (real) null else ContactStatus.NOTIFIED,
                alertContacts = if (real) contactNames() else it.alertContacts,
                liveFix = if (real) DeviceLocation.lastKnown(getApplication()) else null,
                offRouteMeters = null,
                sosActive = false
            )
        }

        val path = chosenGeoPath()
        val destination = _state.value.destination
        if (destination != null) {
            live.startTrip(originPlace(), destination, chosen, _state.value.safeSelected, path, chosen.minutes)
            val d = path.last()
            SmsAlerts.send(
                getApplication(),
                contactsStore.recipients(),
                AlertMessages.rideStarted(destination.name, chosen.label, chosen.minutes, d[0], d[1])
            )
            narrator.say(
                "Starting your ride on the ${chosen.label.lowercase()} route to ${destination.name}, " +
                    "about ${chosen.minutes} minutes away. ${notifiedSentence()}"
            )
        }

        startMotion(chosen, path)
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
        val here = _state.value.liveFix ?: geoPointAt(fromPath, progress)

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
            live.startTrip(originPlace(), destination, newChosen, _state.value.safeSelected, newPath, newChosen.minutes)
            // Patch positions are measured along a route, so the re-based routes need their own.
            loadRiskPatches(newRoutes)
            narrator.say("Rerouted via the ${newChosen.label.lowercase()}, about ${newChosen.minutes} minutes to go.")
            startMotion(newChosen, newPath)
        }
    }

    /** The real app follows the phone's GPS; the demo walks the dot along the route on a timer. */
    private fun startMotion(chosen: RouteOption, path: List<DoubleArray>) {
        if (_state.value.realMotion) followGps(chosen, path) else runRideLoop(chosen, path)
    }

    /**
     * Real ride: every GPS fix is projected onto [path] to get progress, ETA and how far off-route the
     * rider is, pushed to Firebase as her actual position, and the ride ends once she is really at
     * the destination. Nothing moves on screen unless the phone does.
     */
    private fun followGps(chosen: RouteOption, path: List<DoubleArray>) {
        rideJob?.cancel()
        val destinationPoint = path.last()
        rideJob = viewModelScope.launch {
            DeviceLocation.updates(getApplication()).collect { loc ->
                val fix = doubleArrayOf(loc.latitude, loc.longitude)
                val accuracy = if (loc.hasAccuracy()) loc.accuracy else null
                // A coarse network fix (often ±500 m+) would yank the marker around — once there is a
                // usable fix, only reasonably accurate ones move it.
                if (accuracy != null && accuracy > MAX_FIX_ACCURACY_M && _state.value.liveFix != null) return@collect
                val (progress, offBy) = projectOnPath(path, fix)
                val minutesLeft = ceil(chosen.minutes * (1 - progress)).toInt().coerceAtLeast(0)
                _state.update {
                    it.copy(
                        rideProgress = progress,
                        etaMinutesLeft = minutesLeft,
                        liveFix = fix,
                        fixAccuracyM = accuracy,
                        offRouteMeters = offBy
                    )
                }
                live.updateLocation(path, progress, minutesLeft, at = fix)
                Log.d(TAG, "GPS fix ${fix[0]},${fix[1]} ±${accuracy}m -> progress $progress, " +
                    "${offBy.toInt()} m off route, ${haversineMeters(fix, destinationPoint).toInt()} m to go " +
                    "(route ends ${destinationPoint[0]},${destinationPoint[1]})")
                // A coarse fix can land "inside" the arrival radius from far away, so it must be decent too.
                val closeEnough = haversineMeters(fix, destinationPoint) <= ARRIVAL_RADIUS_M
                if (closeEnough && (accuracy == null || accuracy <= ARRIVAL_MAX_ACCURACY_M)) {
                    finishRide(path, fix)
                    cancel()
                }
            }
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
            finishRide(path, null)
        }
    }

    private fun finishRide(path: List<DoubleArray>, at: DoubleArray?) {
        live.updateLocation(path, 1f, 0, at)
        live.arrive()
        _state.value.destination?.let {
            SmsAlerts.send(getApplication(), contactsStore.recipients(), AlertMessages.arrived(it.name))
            narrator.say("You have arrived safely at ${it.name}. ${notifiedSentence()}")
        }
        _state.update { it.copy(step = RideStep.ARRIVED, rideProgress = 1f, etaMinutesLeft = 0) }
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
        val here = _state.value.liveFix ?: geoPointAt(path, _state.value.rideProgress)
        val destination = _state.value.destination?.name ?: "my destination"
        SmsAlerts.send(
            getApplication(),
            contactsStore.recipients(),
            AlertMessages.sosRide(destination, here[0], here[1])
        )
        narrator.say("S.O.S. sent with your current location. ${notifiedSentence()}")
        _state.update {
            it.copy(sosActive = true, contactRohan = if (it.realMotion) null else ContactStatus.RESPONDING)
        }
    }

    fun newRide() {
        rideJob?.cancel()
        assessJob?.cancel()
        analysisJob?.cancel()
        patchesJob?.cancel()
        live.endTrip()
        narrator.stop()
        _state.update {
            RideState(
                nearbyResults = it.nearbyResults,
                usingDeviceLocation = it.usingDeviceLocation,
                realMotion = it.realMotion,
                alertContacts = it.alertContacts
            )
        }
    }

    override fun onCleared() {
        rideJob?.cancel()
        assessJob?.cancel()
        searchJob?.cancel()
        analysisJob?.cancel()
        patchesJob?.cancel()
        narrator.shutdown()
        super.onCleared()
    }

    private companion object {
        const val TAG = "RideViewModel"
        const val RIDE_DURATION_MS = 2 * 60 * 1000L
        const val FIRST_FIX_TIMEOUT_MS = 20_000L
        const val MAX_FIX_ACCURACY_M = 100f
        /** A real ride ends once a fix lands this close to the destination... */
        const val ARRIVAL_RADIUS_M = 35.0
        /** ...and that fix is at least this accurate. */
        const val ARRIVAL_MAX_ACCURACY_M = 50f
    }
}
