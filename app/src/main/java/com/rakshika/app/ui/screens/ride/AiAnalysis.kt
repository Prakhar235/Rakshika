package com.rakshika.app.ui.screens.ride

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rakshika.app.risk.AccuracyStats
import com.rakshika.app.risk.AssessmentRecord
import com.rakshika.app.risk.EquationSource
import com.rakshika.app.risk.FeatureCatalog
import com.rakshika.app.risk.LearningOutcome
import com.rakshika.app.ui.theme.BorderHairline
import com.rakshika.app.ui.theme.RakshikaAmber
import com.rakshika.app.ui.theme.RakshikaGreen
import com.rakshika.app.ui.theme.RakshikaGreenBg
import com.rakshika.app.ui.theme.RakshikaRed
import com.rakshika.app.ui.theme.SurfaceCard
import com.rakshika.app.ui.theme.TextPrimary
import com.rakshika.app.ui.theme.TextSecondary
import kotlin.math.abs
import kotlin.math.roundToInt

private val SOURCE_NAMES = mapOf(
    "google_directions" to "Google Directions",
    "osm_overpass" to "OpenStreetMap",
    "open_meteo" to "Open-Meteo weather",
    "google_places" to "Google Places"
)

private fun pct(v: Double) = "${(v * 100).roundToInt()}%"
private fun signed(v: Double) = if (v >= 0) "+%.1f".format(v) else "%.1f".format(v)

/* ---------------- Explanation ---------------- */

/** Where the model's analysis of the trip is: it starts with the ride, in the background. */
internal enum class AiState { PENDING, RUNNING, DONE, UNAVAILABLE }

internal fun aiStateOf(assessment: AssessmentRecord, running: Boolean, tripOver: Boolean): AiState = when {
    assessment.usedModel -> AiState.DONE
    running -> AiState.RUNNING
    tripOver -> AiState.UNAVAILABLE
    else -> AiState.PENDING
}

/**
 * The "explanation section": what the model predicted for the selected route, how confident it is,
 * why, which data it used, and — expanded — the exact equation with each input's contribution.
 */
@Composable
internal fun AiAnalysisPanel(assessment: AssessmentRecord, corridorId: String, accuracy: AccuracyStats?, aiState: AiState) {
    val corridor = assessment.corridor(corridorId) ?: return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val evaluation = assessment.equation.evaluate(corridor.features)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceCard)
            .border(0.5.dp, BorderHairline, RoundedCornerShape(12.dp))
            .clickable { expanded = !expanded }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (assessment.usedModel) "AI safety analysis" else "Safety analysis (on-device)",
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary
            )
            Spacer(Modifier.width(8.dp))
            Chip("Confidence ${pct(corridor.confidence)}", RakshikaGreen, RakshikaGreenBg)
            Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }

        Text(
            "Prediction: ${corridor.predictedScore}/100",
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary
        )
        Text(corridor.reason, style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic, color = TextPrimary)

        when (aiState) {
            AiState.PENDING -> Text(
                "The AI's deeper analysis runs in the background once you start the ride.",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            AiState.RUNNING -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(11.dp), strokeWidth = 1.5.dp, color = RakshikaRed)
                Spacer(Modifier.width(6.dp))
                Text("AI analysis in progress…", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
            AiState.UNAVAILABLE -> Text(
                "The AI analysis couldn't run (offline or a model error), so the on-device equation's score stands. " +
                    "You can still rate this trip and it will be learned from.",
                style = MaterialTheme.typography.labelSmall,
                color = RakshikaAmber
            )
            AiState.DONE -> Unit
        }

        if (!expanded) {
            Text("Tap for the equation, data used and reasoning", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            return@Column
        }

        if (assessment.explanation.isNotBlank()) {
            SectionTitle("Reasoning")
            Text(assessment.explanation, style = MaterialTheme.typography.labelSmall, color = TextPrimary)
        }

        SectionTitle("Data used")
        Text(
            assessment.sourcesUsed.joinToString(" · ") { SOURCE_NAMES[it] ?: it },
            style = MaterialTheme.typography.labelSmall,
            color = TextPrimary
        )

        val seedLabel = if (assessment.seed.source == EquationSource.SEED) "the built-in seed" else "learned v${assessment.seed.version}"
        SectionTitle(
            if (assessment.usedModel) "Equation — $seedLabel, modified by the AI for this trip" else "Equation — $seedLabel"
        )
        EquationLine("Base", "", "%.0f".format(assessment.equation.base))
        evaluation.contributions.forEach { c ->
            EquationLine(
                FeatureCatalog.label(c.term.feature),
                FeatureCatalog.format(c.term.feature, c.value),
                signed(c.points) + " pts"
            )
        }
        EquationLine("Score", "", "${evaluation.score}/100", bold = true)
        if (evaluation.unmeasured.isNotEmpty()) {
            Text(
                "Not measured on this route, so skipped: " + evaluation.unmeasured.joinToString { FeatureCatalog.label(it) },
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
        if (corridor.seedScore != corridor.equationScore) {
            Text(
                "The seed equation alone would have scored this ${corridor.seedScore}/100.",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
        if (abs(corridor.predictedScore - corridor.equationScore) > 5) {
            Text(
                "The AI's prediction (${corridor.predictedScore}) differs from the equation's output (${corridor.equationScore}) — see the reasoning above.",
                style = MaterialTheme.typography.labelSmall,
                color = RakshikaAmber
            )
        }

        SectionTitle("Track record")
        val meanError = accuracy?.meanAbsError
        Text(
            if (accuracy == null || meanError == null || accuracy.ratedTrips == 0) {
                "No rated trips yet — confidence stays capped until riders rate trips and the model can check itself."
            } else {
                val trips = "${accuracy.ratedTrips} rated trip${if (accuracy.ratedTrips == 1) "" else "s"}"
                val last = accuracy.lastError?.let { e ->
                    when {
                        e > 0 -> "; last time it was $e points too optimistic."
                        e < 0 -> "; last time it was ${-e} points too cautious."
                        else -> "; last time it was spot on."
                    }
                } ?: "."
                "Off by ${"%.0f".format(meanError)} points on average across $trips$last"
            },
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )

    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = TextSecondary, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun EquationLine(label: String, measured: String, points: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            color = TextPrimary,
            modifier = Modifier.weight(1f)
        )
        if (measured.isNotEmpty()) {
            Text(measured, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Spacer(Modifier.width(10.dp))
        }
        Text(
            points,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            color = TextPrimary
        )
    }
}

@Composable
private fun Chip(text: String, color: Color, bg: Color) {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/* ---------------- Post-trip feedback ---------------- */

/**
 * After arrival: rate the route overall and each input the equation used, then send it back to the
 * model to learn from. Once submitted this turns into the learning result (or a "saved, will learn
 * next time" note if the model couldn't be reached).
 */
@Composable
internal fun TripFeedbackSection(
    assessment: AssessmentRecord,
    submitting: Boolean,
    onSubmit: (overall: Int, perFeature: Map<String, Int>) -> Unit
) {
    val chosen = assessment.chosen() ?: return
    val feedback = assessment.feedback

    when {
        submitting -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = RakshikaRed)
            Spacer(Modifier.width(8.dp))
            Text("Learning from your feedback…", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
        feedback != null && assessment.learning != null -> LearningResultCard(assessment.learning, feedback.overall)
        feedback != null -> Text(
            "Thanks — your feedback is saved. The model couldn't be reached just now, so it will be learned from the next time you search a route.",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
        else -> {
            var overall by remember { mutableIntStateOf(0) }
            val perFeature = remember { mutableStateMapOf<String, Int>() }
            // Only the inputs this trip's equation actually used AND we have a measurement for — each one can be rated.
            val inputs = assessment.equation.terms.filter { chosen.features[it.feature] != null }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SurfaceCard)
                    .border(0.5.dp, BorderHairline, RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("How did the route feel?", style = MaterialTheme.typography.titleSmall)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StarRow(overall, { overall = it }, size = 32.dp)
                    Text(
                        when (overall) {
                            0 -> "Overall safety"
                            1 -> "Felt unsafe"
                            2 -> "Felt a bit uneasy"
                            3 -> "Okay"
                            4 -> "Felt safe"
                            else -> "Felt very safe"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }

                if (inputs.isNotEmpty()) {
                    Text(
                        "Rate what we considered (optional) — how safe did each feel?",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    inputs.forEach { term ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(FeatureCatalog.label(term.feature), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    FeatureCatalog.format(term.feature, chosen.features.getValue(term.feature)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                            StarRow(perFeature[term.feature] ?: 0, { v -> if (v == 0) perFeature.remove(term.feature) else perFeature[term.feature] = v }, size = 22.dp)
                        }
                    }
                }

                Button(
                    onClick = { onSubmit(overall, perFeature.toMap()) },
                    enabled = overall > 0,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = RakshikaGreen)
                ) { Text("Send feedback") }
            }
        }
    }
}

@Composable
private fun LearningResultCard(learning: LearningOutcome, stars: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(RakshikaGreenBg)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Thanks — here's what we learned", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
        Text(
            "You rated it $stars★ (≈ ${learning.actualScore}/100). We predicted ${learning.predictedScore}.",
            style = MaterialTheme.typography.labelSmall,
            color = TextPrimary
        )
        Text(
            if (learning.accepted) {
                "The updated equation (v${learning.newVersion}, ${pct(learning.confidence)} confidence) would have scored this trip " +
                    "${learning.adjustedScore}. Error on recent rated trips: %.0f → %.0f points.".format(learning.errorBefore, learning.errorAfter)
            } else {
                "The proposed update would have scored this trip ${learning.adjustedScore}, but it fit your recent rated trips worse, so the current equation was kept."
            },
            style = MaterialTheme.typography.labelSmall,
            color = TextPrimary
        )
        Text(learning.lessons, style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic, color = TextPrimary)
    }
}

@Composable
private fun StarRow(value: Int, onChange: (Int) -> Unit, size: Dp) {
    Row {
        for (i in 1..5) {
            Icon(
                if (i <= value) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = "$i star${if (i == 1) "" else "s"}",
                tint = if (i <= value) RakshikaAmber else TextSecondary,
                modifier = Modifier.size(size).clickable { onChange(if (value == i) 0 else i) }
            )
        }
    }
}
