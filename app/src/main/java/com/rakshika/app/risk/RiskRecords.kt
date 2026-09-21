package com.rakshika.app.risk

import org.json.JSONArray
import org.json.JSONObject

enum class EquationSource { SEED, LEARNED }

/** MODEL = the AI's own prediction; EQUATION = the model was unreachable, so the on-device equation scored it. */
enum class PredictionSource { MODEL, EQUATION }

/** One numbered generation of the equation, with how sure we are of it. */
data class EquationVersion(
    val version: Int,
    val equation: SafetyEquation,
    val confidence: Double,
    val source: EquationSource,
    val createdAt: Long,
    /** Why it looks the way it does — the model's lesson for a learned version. */
    val note: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("version", version)
        .put("equation", equation.toJson())
        .put("confidence", confidence)
        .put("source", source.name)
        .put("createdAt", createdAt)
        .put("note", note)

    companion object {
        fun seed(now: Long) = EquationVersion(
            version = 0,
            equation = SafetyEquation.SEED,
            confidence = SafetyEquation.SEED_CONFIDENCE,
            source = EquationSource.SEED,
            createdAt = now,
            note = "Starting equation from Google Directions speed, road naming and turn density."
        )

        fun fromJson(o: JSONObject) = EquationVersion(
            version = o.getInt("version"),
            equation = SafetyEquation.fromJson(o.getJSONObject("equation")),
            confidence = o.getDouble("confidence"),
            source = EquationSource.valueOf(o.getString("source")),
            createdAt = o.getLong("createdAt"),
            note = o.optString("note")
        )
    }
}

/** Everything the app knows about one corridor for one trip. */
data class CorridorAssessment(
    /** "main" or "back". */
    val id: String,
    /** Every measurement gathered for this corridor — Google Directions plus whatever the model fetched. */
    val features: Map<String, Double>,
    /** What the seed equation alone scored it, re-computed on-device. */
    val seedScore: Int,
    /** What the (possibly model-modified) equation scores it, re-computed on-device. */
    val equationScore: Int,
    /** The score shown to the rider. */
    val predictedScore: Int,
    val confidence: Double,
    val reason: String,
    val source: PredictionSource
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("features", featuresToJson(features))
        .put("seedScore", seedScore)
        .put("equationScore", equationScore)
        .put("predictedScore", predictedScore)
        .put("confidence", confidence)
        .put("reason", reason)
        .put("source", source.name)

    companion object {
        fun fromJson(o: JSONObject) = CorridorAssessment(
            id = o.getString("id"),
            features = featuresFromJson(o.getJSONObject("features")),
            seedScore = o.getInt("seedScore"),
            equationScore = o.getInt("equationScore"),
            predictedScore = o.getInt("predictedScore"),
            confidence = o.getDouble("confidence"),
            reason = o.optString("reason"),
            source = PredictionSource.valueOf(o.getString("source"))
        )
    }
}

/** What the rider said after the trip: 1-5 for the whole route, and optionally 1-5 per equation input. */
data class TripFeedback(val overall: Int, val perFeature: Map<String, Int>, val submittedAt: Long) {
    /** The rider's overall rating on the same 1-99 scale as the safety score: 1★→10 … 5★→90. */
    val actualScore: Int get() = overall * 20 - 10

    fun toJson(): JSONObject = JSONObject()
        .put("overall", overall)
        .put("perFeature", JSONObject().also { o -> perFeature.forEach { (k, v) -> o.put(k, v) } })
        .put("submittedAt", submittedAt)

    companion object {
        fun fromJson(o: JSONObject): TripFeedback {
            val per = o.optJSONObject("perFeature")
            return TripFeedback(
                overall = o.getInt("overall"),
                perFeature = per?.keys()?.asSequence()?.associateWith { per.getInt(it) } ?: emptyMap(),
                submittedAt = o.getLong("submittedAt")
            )
        }
    }
}

/** What learning from one piece of feedback did. */
data class LearningOutcome(
    val actualScore: Int,
    /** What we told the rider beforehand. */
    val predictedScore: Int,
    /** What the learned equation would have said for this same trip — the "modified score by actual rating". */
    val adjustedScore: Int,
    /** False when the proposed equation fit past trips worse than the current one, so it was not adopted. */
    val accepted: Boolean,
    val newVersion: Int?,
    val confidence: Double,
    /** Mean |score − rider's rating| over recent rated trips, current equation vs. proposed one. */
    val errorBefore: Double,
    val errorAfter: Double,
    val lessons: String,
    val at: Long
) {
    fun toJson(): JSONObject = JSONObject()
        .put("actualScore", actualScore)
        .put("predictedScore", predictedScore)
        .put("adjustedScore", adjustedScore)
        .put("accepted", accepted)
        .put("newVersion", newVersion ?: JSONObject.NULL)
        .put("confidence", confidence)
        .put("errorBefore", errorBefore)
        .put("errorAfter", errorAfter)
        .put("lessons", lessons)
        .put("at", at)

    companion object {
        fun fromJson(o: JSONObject) = LearningOutcome(
            actualScore = o.getInt("actualScore"),
            predictedScore = o.getInt("predictedScore"),
            adjustedScore = o.getInt("adjustedScore"),
            accepted = o.getBoolean("accepted"),
            newVersion = if (o.isNull("newVersion")) null else o.getInt("newVersion"),
            confidence = o.getDouble("confidence"),
            errorBefore = o.getDouble("errorBefore"),
            errorAfter = o.getDouble("errorAfter"),
            lessons = o.optString("lessons"),
            at = o.getLong("at")
        )
    }
}

/** One route search, from the equation we sent the model to what the rider said afterwards. */
data class AssessmentRecord(
    val id: String,
    val createdAt: Long,
    /** The equation + confidence that went to the model as the starting point. */
    val seed: EquationVersion,
    /** The function actually used to explain this trip — the model's modified one, or [seed]'s if the model was unreachable. */
    val equation: SafetyEquation,
    val equationConfidence: Double,
    val corridors: List<CorridorAssessment>,
    val explanation: String,
    val sourcesUsed: List<String>,
    val usedModel: Boolean,
    val chosenCorridor: String? = null,
    val feedback: TripFeedback? = null,
    val learning: LearningOutcome? = null
) {
    fun corridor(id: String): CorridorAssessment? = corridors.firstOrNull { it.id == id }
    fun chosen(): CorridorAssessment? = chosenCorridor?.let(::corridor)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("createdAt", createdAt)
        .put("seed", seed.toJson())
        .put("equation", equation.toJson())
        .put("equationConfidence", equationConfidence)
        .put("corridors", JSONArray().also { a -> corridors.forEach { a.put(it.toJson()) } })
        .put("explanation", explanation)
        .put("sourcesUsed", JSONArray(sourcesUsed))
        .put("usedModel", usedModel)
        .put("chosenCorridor", chosenCorridor ?: JSONObject.NULL)
        .put("feedback", feedback?.toJson() ?: JSONObject.NULL)
        .put("learning", learning?.toJson() ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): AssessmentRecord {
            val corridors = o.getJSONArray("corridors")
            val sources = o.getJSONArray("sourcesUsed")
            return AssessmentRecord(
                id = o.getString("id"),
                createdAt = o.getLong("createdAt"),
                seed = EquationVersion.fromJson(o.getJSONObject("seed")),
                equation = SafetyEquation.fromJson(o.getJSONObject("equation")),
                equationConfidence = o.getDouble("equationConfidence"),
                corridors = (0 until corridors.length()).map { CorridorAssessment.fromJson(corridors.getJSONObject(it)) },
                explanation = o.optString("explanation"),
                sourcesUsed = (0 until sources.length()).map { sources.getString(it) },
                usedModel = o.getBoolean("usedModel"),
                chosenCorridor = if (o.isNull("chosenCorridor")) null else o.getString("chosenCorridor"),
                feedback = o.optJSONObject("feedback")?.let(TripFeedback::fromJson),
                learning = o.optJSONObject("learning")?.let(LearningOutcome::fromJson)
            )
        }
    }
}

/** How well past predictions matched what riders actually felt. */
data class AccuracyStats(
    val ratedTrips: Int,
    /** Mean |predicted − rider's rating| over the rated trips, in score points; null until one is rated. */
    val meanAbsError: Double?,
    /** Signed error of the most recent rated trip (predicted − actual): positive = we were too optimistic. */
    val lastError: Int?
)

internal fun featuresToJson(features: Map<String, Double>): JSONObject =
    JSONObject().also { o -> features.forEach { (k, v) -> o.put(k, v) } }

internal fun featuresFromJson(o: JSONObject): Map<String, Double> =
    o.keys().asSequence().associateWith { o.getDouble(it) }
