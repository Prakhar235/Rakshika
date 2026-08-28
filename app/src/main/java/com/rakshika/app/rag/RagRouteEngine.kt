package com.rakshika.app.rag

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/** One retrieved note plus how strongly it moved a corridor's score. */
data class RouteEvidence(
    val doc: SafetyDoc,
    val similarity: Float,
    val contribution: Float
)

/** The scored prediction for a single corridor. */
data class RouteAssessment(
    val corridor: RouteCorridor,
    val label: String,
    val minutes: Int,
    val safetyScore: Int,
    val recommended: Boolean,
    val rationale: List<String>,
    val evidence: List<RouteEvidence>
)

/** The full output of one RAG pass — enough to render the routes screen and explain it. */
data class RagResult(
    val datasetId: String,
    val datasetName: String,
    val indexedDocs: Int,
    val retrievedDocs: Int,
    val embeddingDim: Int,
    val topK: Int,
    val queryText: String,
    val routes: List<RouteAssessment>,
    val safest: RouteAssessment,
    val recommendationText: String
)

/**
 * The on-device retrieval-augmented pipeline behind the safest-route prediction:
 *
 *   index(dataset) → embed every note → [InMemoryVectorStore]
 *   assess(...)    → embed a route query → retrieve top-k notes → score each corridor
 *
 * Scoring is a deliberately transparent linear model over the retrieved notes' metadata
 * (severity × recency × sentiment, gated by retrieval similarity) so the demo can show
 * exactly why one route won.
 */
class RagRouteEngine(
    val embedder: TextEmbedder = HashingTextEmbedder(),
    private val store: InMemoryVectorStore = InMemoryVectorStore(embedder),
    private val topK: Int = 4
) {
    private var indexedDatasetId: String? = null
    private var indexedCount: Int = 0

    fun index(dataset: SafetyDataset) {
        store.clear()
        store.insertAll(dataset.docs)
        indexedDatasetId = dataset.id
        indexedCount = store.size
    }

    fun assess(originLabel: String, destinationLabel: String, dataset: SafetyDataset): RagResult {
        if (indexedDatasetId != dataset.id) index(dataset)

        val query = buildQuery(originLabel, destinationLabel)
        val pool = store.query(query, topK * 2)
        val maxSim = (pool.maxOfOrNull { it.score } ?: 1f).coerceAtLeast(1e-4f)
        val etas = deterministicEtas(destinationLabel, dataset)

        val raw = listOf(RouteCorridor.MAIN_ROAD, RouteCorridor.BACK_LANE).map { corridor ->
            val relevant = pool
                .filter { it.doc.corridor == corridor || it.doc.corridor == RouteCorridor.BOTH }
                .take(topK)

            val evidence = relevant.map { m ->
                val normSim = 0.5f + 0.5f * (m.score / maxSim).coerceIn(0f, 1f)
                val decay = 1f / (1f + m.doc.ageDays / 14f)
                val contribution = (normSim * m.doc.severity * decay * m.doc.sentiment * SCORE_GAIN)
                    .coerceIn(-CONTRIB_CAP, CONTRIB_CAP)
                RouteEvidence(m.doc, m.score, contribution)
            }

            val score = (BASE_SCORE + evidence.sumOf { it.contribution.toDouble() })
                .coerceIn(1.0, 99.0)
                .roundToInt()

            val rationale = evidence
                .sortedByDescending { abs(it.contribution) }
                .take(3)
                .map { (if (it.contribution >= 0f) "✓ " else "⚠ ") + summarise(it.doc.text) }

            Triple(corridor, score, Pair(rationale, evidence))
        }

        val mainScore = raw.first { it.first == RouteCorridor.MAIN_ROAD }.second
        val backScore = raw.first { it.first == RouteCorridor.BACK_LANE }.second
        // Ties go to the main road — busier and easier to get help on.
        val recommendedCorridor = if (backScore > mainScore) RouteCorridor.BACK_LANE else RouteCorridor.MAIN_ROAD

        val assessments = raw.map { (corridor, score, detail) ->
            RouteAssessment(
                corridor = corridor,
                label = labelFor(corridor),
                minutes = etas.getValue(corridor),
                safetyScore = score,
                recommended = corridor == recommendedCorridor,
                rationale = detail.first,
                evidence = detail.second
            )
        }.sortedByDescending { it.safetyScore }

        val safest = assessments.first { it.recommended }
        val other = assessments.first { !it.recommended }

        return RagResult(
            datasetId = dataset.id,
            datasetName = dataset.name,
            indexedDocs = indexedCount,
            retrievedDocs = pool.size,
            embeddingDim = embedder.dimension,
            topK = topK,
            queryText = query,
            routes = assessments,
            safest = safest,
            recommendationText = recommendationText(dataset, safest, other)
        )
    }

    private fun buildQuery(originLabel: String, destinationLabel: String): String =
        "Safest walking route at night from $originLabel to $destinationLabel. " +
            "Consider street lighting, pedestrian foot traffic, recent incidents, " +
            "police patrols, crowd density, waterlogging and other hazards."

    private fun deterministicEtas(destinationLabel: String, dataset: SafetyDataset): Map<RouteCorridor, Int> {
        val rnd = Random(destinationLabel.hashCode())
        val backLane = 6 + rnd.nextInt(6)
        var mainRoad = backLane + 2 + rnd.nextInt(4)
        mainRoad += when (dataset.id) {
            "monsoon" -> 5   // detour around the flooded underpass
            "festival" -> 6  // barricades and diversions along the procession
            else -> 0
        }
        return mapOf(RouteCorridor.MAIN_ROAD to mainRoad, RouteCorridor.BACK_LANE to backLane)
    }

    private fun labelFor(corridor: RouteCorridor) = when (corridor) {
        RouteCorridor.MAIN_ROAD -> "Main road"
        RouteCorridor.BACK_LANE -> "Back lane"
        RouteCorridor.BOTH -> "Either route"
    }

    private fun summarise(text: String): String {
        val firstSentence = text.substringBefore(". ").trimEnd('.', ' ')
        return if (firstSentence.length <= 96) firstSentence else firstSentence.take(93).trimEnd() + "…"
    }

    private fun recommendationText(
        dataset: SafetyDataset,
        safest: RouteAssessment,
        other: RouteAssessment
    ): String {
        val diff = safest.minutes - other.minutes
        val etaText = when {
            diff <= -1 -> "and it's ${-diff} min faster"
            diff == 0 -> "at the same ETA"
            diff <= 3 -> "for only $diff min more"
            else -> "though it adds $diff min"
        }
        val lead = safest.rationale.firstOrNull()?.removePrefix("✓ ")?.removePrefix("⚠ ")
            ?: "it retrieved fewer risk reports"
        return "On the ${dataset.name} data, Rakshika embedded ${indexedCount} safety notes " +
            "on-device and retrieved the most relevant. The ${safest.label.lowercase()} scores " +
            "${safest.safetyScore}/100 vs ${other.safetyScore}/100 — ${lead.replaceFirstChar { it.lowercase() }} — $etaText."
    }

    private companion object {
        const val BASE_SCORE = 70.0
        const val SCORE_GAIN = 18f
        const val CONTRIB_CAP = 20f
    }
}
