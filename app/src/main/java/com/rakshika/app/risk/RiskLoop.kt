package com.rakshika.app.risk

import android.content.Context
import android.util.Log
import com.rakshika.app.routing.GeoRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min

/**
 * The learning loop, end to end:
 *
 *  1. [assessLocal] — the moment a destination is picked: the current equation (the seed, or the
 *     last learned one) scores the real Google Directions measurements on-device. Instant, no
 *     model call, so choosing a place never waits on the network. Stored.
 *  2. [recordChoice] + [refineWithModel] — when the ride starts, in the background: the same
 *     measurements go to the model, which may fetch more data and returns a modified equation,
 *     confidence, predictions and reasons. Upgrades the stored record, ready by arrival.
 *  3. [submitFeedback] — the rider's ratings after the trip go back to the model with the stored
 *     assessment; it returns a learned equation. If that equation fits recent rated trips at least
 *     as well as the current one it becomes the next seed; either way the outcome is stored.
 *
 * The next [assessLocal] then starts from the newly learned equation, and so on.
 */
class RiskLoop(context: Context) {
    private val appContext = context.applicationContext
    private val store = RiskStore(appContext)

    /** Serialises learning, so two learning calls never race to derive the next equation version. */
    private val learningLock = Mutex()

    suspend fun accuracy(): AccuracyStats = withContext(Dispatchers.IO) { store.accuracy() }

    /**
     * Scores [routes] (keyed "main"/"back", live corridors only) with the current equation and
     * stores the result as [PredictionSource.EQUATION]. Never touches the network and never waits
     * on an in-flight learning call, so it is safe to await in the destination-tap flow; the trade-off
     * is that a search made within seconds of submitting feedback may still see the previous equation.
     */
    suspend fun assessLocal(routes: Map<String, GeoRoute>): AssessmentRecord = withContext(Dispatchers.IO) {
        val record = fromEquation(store.currentEquation(), routes.mapValues { RouteFeatures.from(it.value) })
        store.saveAssessment(record)
        record
    }

    /**
     * Runs the model on the stored assessment [assessmentId] (slow — several network calls) and, on
     * success, upgrades the record in place with the model's modified equation, confidence,
     * predictions and explanation. Returns the upgraded record, or null if the model was unreachable
     * or its reply was rejected — the on-device record is then left as is. Meant for a background job.
     */
    suspend fun refineWithModel(assessmentId: String, routes: Map<String, GeoRoute>): AssessmentRecord? = withContext(Dispatchers.IO) {
        val local = store.assessment(assessmentId) ?: return@withContext null
        val stats = store.accuracy()
        val base = local.corridors.associate { it.id to it.features }
        val model = RiskModelClient.assess(appContext, routes.filterKeys { it in base }, base, local.seed, stats)
            ?: return@withContext null

        val refined = fromModel(local.seed, model, stats.ratedTrips)
        store.update(assessmentId) { latest ->
            // Keep whatever happened to the record while the model was thinking.
            refined.copy(
                id = latest.id,
                createdAt = latest.createdAt,
                chosenCorridor = latest.chosenCorridor,
                feedback = latest.feedback,
                learning = latest.learning
            )
        }
    }

    suspend fun recordChoice(assessmentId: String, corridorId: String) {
        withContext(Dispatchers.IO) { store.update(assessmentId) { it.copy(chosenCorridor = corridorId) } }
    }

    /** Stores [feedback] and runs the learning call. Returns the updated record (with its learning outcome, if it succeeded). */
    suspend fun submitFeedback(assessmentId: String, feedback: TripFeedback): AssessmentRecord? = withContext(Dispatchers.IO) {
        learningLock.withLock {
            val record = store.update(assessmentId) { it.copy(feedback = feedback) } ?: return@withLock null
            learnLocked(record)
        }
    }

    /** Retries learning for rated trips whose model call failed earlier (offline / model error). Background only. */
    suspend fun retryPendingLearning() = withContext(Dispatchers.IO) {
        learningLock.withLock { store.pendingLearning().forEach { learnLocked(it) } }
    }

    /** Must hold [learningLock]. Leaves the record "pending" (feedback saved, no outcome) if the model call fails, to retry later. */
    private suspend fun learnLocked(record: AssessmentRecord): AssessmentRecord {
        val chosen = record.chosen() ?: return record
        val feedback = record.feedback ?: return record

        val current = store.currentEquation()
        val examples = store.trainingExamples()
        val stats = store.accuracy()
        val proposal = RiskModelClient.learn(record, current, examples, stats) ?: return record

        val before = meanAbsError(current.equation, examples)
        val after = meanAbsError(proposal.equation, examples)
        val accepted = after <= before + ACCEPT_TOLERANCE_POINTS
        val confidence = min(proposal.confidence, EvidenceCap.forRatedTrips(stats.ratedTrips))

        var newVersion: Int? = null
        if (accepted) {
            newVersion = current.version + 1
            store.addEquation(
                EquationVersion(newVersion, proposal.equation, confidence, EquationSource.LEARNED, System.currentTimeMillis(), proposal.lessons)
            )
        }
        Log.i(TAG, "Learned from feedback: error %.1f -> %.1f over %d trip(s), accepted=$accepted".format(before, after, examples.size))

        val lessons = if (accepted) proposal.lessons else
            "${proposal.lessons} (Not adopted: the proposed equation fit recent rated trips worse — " +
                "%.1f vs %.1f points of error — so the current equation was kept.)".format(after, before)
        val outcome = LearningOutcome(
            actualScore = feedback.actualScore,
            predictedScore = chosen.predictedScore,
            adjustedScore = proposal.equation.evaluate(chosen.features).score,
            accepted = accepted,
            newVersion = newVersion,
            confidence = confidence,
            errorBefore = before,
            errorAfter = after,
            lessons = lessons,
            at = System.currentTimeMillis()
        )
        return store.update(record.id) { it.copy(learning = outcome) } ?: record.copy(learning = outcome)
    }

    private fun fromModel(seed: EquationVersion, model: ModelAssessment, ratedTrips: Int): AssessmentRecord {
        val cap = EvidenceCap.forRatedTrips(ratedTrips)
        val corridors = model.features.map { (id, features) ->
            val equationScore = model.equation.evaluate(features).score
            val prediction = model.predictions[id]
            CorridorAssessment(
                id = id,
                features = features,
                seedScore = seed.equation.evaluate(features).score,
                equationScore = equationScore,
                predictedScore = prediction?.score ?: equationScore,
                confidence = min(prediction?.confidence ?: model.equationConfidence, cap),
                reason = prediction?.reason ?: "Scored by the modified equation.",
                source = PredictionSource.MODEL
            )
        }
        return AssessmentRecord(
            id = UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis(),
            seed = seed,
            equation = model.equation,
            equationConfidence = min(model.equationConfidence, cap),
            corridors = corridors,
            explanation = model.explanation,
            sourcesUsed = model.sourcesUsed,
            usedModel = true
        )
    }

    private fun fromEquation(seed: EquationVersion, features: Map<String, Map<String, Double>>): AssessmentRecord {
        val label = if (seed.version == 0) "the built-in seed equation" else "learned equation v${seed.version}"
        val corridors = features.map { (id, f) ->
            val score = seed.equation.evaluate(f).score
            CorridorAssessment(
                id = id,
                features = f,
                seedScore = score,
                equationScore = score,
                predictedScore = score,
                confidence = seed.confidence,
                reason = "Scored on-device by $label.",
                source = PredictionSource.EQUATION
            )
        }
        return AssessmentRecord(
            id = UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis(),
            seed = seed,
            equation = seed.equation,
            equationConfidence = seed.confidence,
            corridors = corridors,
            explanation = "These scores come straight from $label using Google's live route data, so they appear instantly. " +
                "The AI's deeper analysis runs in the background once you start the ride.",
            sourcesUsed = listOf("google_directions"),
            usedModel = false
        )
    }

    private fun meanAbsError(equation: SafetyEquation, examples: List<TrainingExample>): Double =
        if (examples.isEmpty()) 0.0 else examples.map { abs(equation.evaluate(it.features).score - it.actualScore).toDouble() }.average()

    private companion object {
        const val TAG = "RiskLoop"

        /** A learned equation may fit past trips this much worse (mean points) and still be adopted — noise room. */
        const val ACCEPT_TOLERANCE_POINTS = 0.5
    }
}

/**
 * The model's own confidence is self-reported, so it's capped by how much real evidence exists:
 * 35% with no rider-rated trips, +6 points per rated trip, never above 95%. A model can't claim 90%
 * on an equation no rider has ever checked.
 */
internal object EvidenceCap {
    fun forRatedTrips(n: Int): Double = min(0.95, 0.35 + 0.06 * n)
}
