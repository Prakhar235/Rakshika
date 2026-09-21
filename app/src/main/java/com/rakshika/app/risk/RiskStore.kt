package com.rakshika.app.risk

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.io.File
import kotlin.math.abs

/** A rated trip reduced to what the equation is fit against: the measurements and how safe it felt. */
data class TrainingExample(val features: Map<String, Double>, val actualScore: Int, val predictedScore: Int)

/**
 * On-device persistence for the loop — every assessment (with its feedback and learning result)
 * and every equation version. Two small JSON files in app-private storage, capped so they can't
 * grow without bound; same "plain JSON, no database" approach as [com.rakshika.app.alerts.ContactsStore].
 *
 * Calls block on file IO, so call from `Dispatchers.IO`.
 */
class RiskStore(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "risk").apply { mkdirs() }
    private val assessmentsFile = File(dir, "assessments.json")
    private val equationsFile = File(dir, "equations.json")
    private val lock = Any()

    /** The equation the next assessment starts from: the latest learned version, or the built-in seed. */
    fun currentEquation(): EquationVersion = synchronized(lock) {
        readEquations().lastOrNull() ?: EquationVersion.seed(System.currentTimeMillis())
    }

    fun equationHistory(): List<EquationVersion> = synchronized(lock) { readEquations() }

    fun addEquation(version: EquationVersion) = synchronized(lock) {
        write(equationsFile, (readEquations() + version).takeLast(MAX_EQUATIONS).map { it.toJson() })
    }

    /** Inserts or replaces (by id) — a record is saved when assessed, then again on start, feedback and learning. */
    fun saveAssessment(record: AssessmentRecord) = synchronized(lock) {
        val all = readAssessments().filterNot { it.id == record.id } + record
        write(assessmentsFile, all.takeLast(MAX_ASSESSMENTS).map { it.toJson() })
    }

    /** Atomically applies [transform] to the stored record with [id] and returns the result (null if it isn't stored).
     *  Background analysis, the ride choice and feedback all write to the same record, so each edits only its own
     *  fields under the store lock rather than overwriting the whole record from a stale copy. */
    fun update(id: String, transform: (AssessmentRecord) -> AssessmentRecord): AssessmentRecord? = synchronized(lock) {
        val all = readAssessments()
        val current = all.firstOrNull { it.id == id } ?: return null
        val updated = transform(current)
        write(assessmentsFile, all.map { if (it.id == id) updated else it }.takeLast(MAX_ASSESSMENTS).map { it.toJson() })
        updated
    }

    fun assessment(id: String): AssessmentRecord? = synchronized(lock) { readAssessments().firstOrNull { it.id == id } }

    fun allAssessments(): List<AssessmentRecord> = synchronized(lock) { readAssessments() }

    /** Rated trips whose learning call never succeeded (offline / model error) — retried in the background. */
    fun pendingLearning(): List<AssessmentRecord> = synchronized(lock) {
        readAssessments().filter { it.feedback != null && it.learning == null }
    }

    fun accuracy(): AccuracyStats = synchronized(lock) {
        val rated = readAssessments().mapNotNull { r -> r.chosen()?.let { c -> r.feedback?.let { f -> c.predictedScore to f.actualScore } } }
        AccuracyStats(
            ratedTrips = rated.size,
            meanAbsError = rated.takeIf { it.isNotEmpty() }?.map { abs(it.first - it.second).toDouble() }?.average(),
            lastError = rated.lastOrNull()?.let { it.first - it.second }
        )
    }

    /** The most recent rated trips, oldest first — what a proposed equation gets checked against. */
    fun trainingExamples(limit: Int = EXAMPLE_WINDOW): List<TrainingExample> = synchronized(lock) {
        readAssessments().mapNotNull { r ->
            val chosen = r.chosen() ?: return@mapNotNull null
            val feedback = r.feedback ?: return@mapNotNull null
            TrainingExample(chosen.features, feedback.actualScore, chosen.predictedScore)
        }.takeLast(limit)
    }

    private fun readAssessments(): List<AssessmentRecord> = read(assessmentsFile) { AssessmentRecord.fromJson(it) }
    private fun readEquations(): List<EquationVersion> = read(equationsFile) { EquationVersion.fromJson(it) }

    /** A corrupt or unreadable entry is dropped rather than losing the whole file. */
    private fun <T> read(file: File, parse: (org.json.JSONObject) -> T): List<T> {
        if (!file.exists()) return emptyList()
        val arr = runCatching { JSONArray(file.readText()) }
            .onFailure { Log.w(TAG, "Unreadable ${file.name}, starting empty", it) }
            .getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            runCatching { parse(arr.getJSONObject(i)) }
                .onFailure { Log.w(TAG, "Dropping bad entry $i in ${file.name}", it) }
                .getOrNull()
        }
    }

    /** Write-then-rename so a crash mid-write can't leave a half-written file. */
    private fun write(file: File, items: List<org.json.JSONObject>) {
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(JSONArray().also { a -> items.forEach { a.put(it) } }.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        private const val TAG = "RiskStore"
        private const val MAX_ASSESSMENTS = 100
        private const val MAX_EQUATIONS = 50
        const val EXAMPLE_WINDOW = 20
    }
}
