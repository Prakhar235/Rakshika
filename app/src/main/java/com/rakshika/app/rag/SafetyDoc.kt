package com.rakshika.app.rag

/** Which of the two candidate walking corridors a piece of safety evidence bears on. */
enum class RouteCorridor { MAIN_ROAD, BACK_LANE, BOTH }

/** Coarse category for a safety note, shown as a tag next to retrieved evidence. */
enum class SafetyKind { LIGHTING, INCIDENT, FOOT_TRAFFIC, PATROL, HAZARD, TRANSIT }

/**
 * One retrievable safety note. [text] is the only field that gets embedded — everything
 * else is metadata the scoring model uses once a note has been retrieved.
 *
 * @param sentiment -1f (this makes the corridor less safe) .. +1f (more safe)
 * @param severity  0f..1f how much weight this note carries
 * @param ageDays    how old the report is, used for recency decay
 */
data class SafetyDoc(
    val id: String,
    val text: String,
    val corridor: RouteCorridor,
    val kind: SafetyKind,
    val sentiment: Float,
    val severity: Float,
    val ageDays: Int
)
