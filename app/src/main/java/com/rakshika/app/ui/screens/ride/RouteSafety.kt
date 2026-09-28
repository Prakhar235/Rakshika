package com.rakshika.app.ui.screens.ride

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rakshika.app.risk.AssessmentRecord
import com.rakshika.app.geo.formatDistance
import com.rakshika.app.risk.FeatureCatalog
import com.rakshika.app.risk.RiskPatch
import com.rakshika.app.ui.mapkit.MapPatch
import com.rakshika.app.risk.TermContribution
import com.rakshika.app.routing.key
import com.rakshika.app.ride.RideState
import com.rakshika.app.ride.RouteOption
import com.rakshika.app.ride.isWalkable
import com.rakshika.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/* ---------------- Shared pieces of the route-safety flow ---------------- */

/** [short] fits inside the small score ring on a route card. */
internal enum class Verdict(val label: String, val short: String) {
    SAFE("SAFE", "SAFE"),
    MODERATE("MODERATE", "FAIR"),
    RISKY("RISKY", "RISKY")
}

/** The recommended (safe) route is always green and the other (unsafe) one always red — on the map, cards and banner. */
internal fun roleColor(isSafe: Boolean): Color = if (isSafe) RouteGreen else RouteRed
internal fun roleBg(isSafe: Boolean): Color = if (isSafe) RouteGreenBg else RouteRedBg

/** OSM-flagged risky stretches on [route], or null while unknown (still loading, or the lookup failed). */
internal fun RideState.patchesFor(route: RouteOption): List<RiskPatch>? = riskPatches[route.corridor.key]

internal fun List<RiskPatch>.toMapPatches(): List<MapPatch> =
    map { MapPatch(it.center, it.radiusMeters, it.kind.label, RouteRed) }

internal fun verdictOf(score: Int): Verdict = when {
    score >= 70 -> Verdict.SAFE
    score >= 50 -> Verdict.MODERATE
    else -> Verdict.RISKY
}

/** Same dark-hours window [com.rakshika.app.risk.RouteFeatures] scores `is_night` with. */
internal fun isNightNow(): Boolean {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return hour >= 19 || hour < 6
}

internal fun clockTime(atMillis: Long = System.currentTimeMillis()): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(atMillis))

/** Circular safety score: the arc fills to [score]/100 in [color], dashed for the unsafe route. */
@Composable
internal fun ScoreRing(
    score: Int,
    color: Color,
    bg: Color,
    dashed: Boolean,
    size: Dp,
    stroke: Dp,
    caption: String?,
    numberSize: TextUnit,
    modifier: Modifier = Modifier
) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val inset = w / 2
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(
                color = bg,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = w)
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * score.coerceIn(0, 100) / 100f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(
                    width = w,
                    cap = if (dashed) StrokeCap.Butt else StrokeCap.Round,
                    pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(w * 1.4f, w * 0.9f)) else null
                )
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$score", fontSize = numberSize, fontWeight = FontWeight.Bold, color = color, lineHeight = numberSize)
            if (caption != null) {
                Text(caption, fontSize = 8.sp, fontWeight = FontWeight.Medium, color = TextSecondary, lineHeight = 9.sp)
            }
        }
    }
}

@Composable
internal fun Pill(text: String, color: Color, bg: Color, modifier: Modifier = Modifier, filled: Boolean = false) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (filled) color else bg)
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            text,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (filled) Color.White else color,
            lineHeight = 12.sp
        )
    }
}

/**
 * One equation input as a 0-100 bar: 50 is neutral, the cap of the term in either direction maps
 * to 0 or 100 — so the bar shows exactly how far that input pushed the score, not a made-up rating.
 */
internal fun TermContribution.subScore(): Int =
    (50 + 50 * points / term.cap).roundToInt().coerceIn(0, 100)

/** The inputs behind [corridorId]'s score, biggest effect first. */
internal fun AssessmentRecord.contributionsFor(corridorId: String): List<TermContribution> {
    val corridor = corridor(corridorId) ?: return emptyList()
    return equation.evaluate(corridor.features).contributions.sortedByDescending { abs(it.points) }
}

/* ---------------- Route safety (score breakdown) ---------------- */

@Composable
internal fun RouteSafetyScreen(state: RideState, onBack: () -> Unit, onStart: () -> Unit, onRetryPatches: () -> Unit) {
    val destination = state.destination ?: return
    val routes = state.routes ?: return
    val chosen = if (state.safeSelected) routes.safe else routes.fast
    val other = if (state.safeSelected) routes.fast else routes.safe
    val verdict = verdictOf(chosen.safetyScore)
    val color = roleColor(state.safeSelected)
    val bg = roleBg(state.safeSelected)
    val patches = state.patchesFor(chosen)
    val contributions = state.assessment?.contributionsFor(chosen.corridor.key).orEmpty()
    val reason = chosen.modelReason
        ?: state.assessment?.corridor(chosen.corridor.key)?.reason
        ?: chosen.reasons.firstOrNull()
    // The model's reason is also the first fact (see RouteScoring.withModelScores) — it's already the headline above.
    val facts = chosen.facts.filter { it.text != reason }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(SurfaceCard)
                    .border(0.5.dp, BorderHairline, CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Route safety", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${originName(state)} → ${destination.name} · ${clockTime()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScoreRing(
                    chosen.safetyScore, color, bg, dashed = !state.safeSelected,
                    size = 104.dp, stroke = 9.dp, caption = "out of 100", numberSize = 32.sp
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val pill = if (verdict == Verdict.SAFE && isNightNow()) "SAFE AT NIGHT" else verdict.label
                    Pill(pill, color, bg, filled = true)
                    if (reason != null) {
                        Text(reason, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                    }
                    Text(
                        "${titleFor(other, isSafe = !state.safeSelected, counterpart = chosen)} scores ${other.safetyScore}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
        }

        if (contributions.isNotEmpty()) {
            SectionCard {
                Text("What goes into the score", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                contributions.forEach { c ->
                    FactorBar(
                        label = FeatureCatalog.label(c.term.feature),
                        measured = FeatureCatalog.format(c.term.feature, c.value),
                        score = c.subScore()
                    )
                }
                Text(
                    "50 is neutral — each bar shows how far that input moved this route's score.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }

        SectionCard {
            Text("Risk patches on this route", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            when {
                state.patchesLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = RouteRed)
                    Spacer(Modifier.width(8.dp))
                    Text("Checking OpenStreetMap for unlit and isolated stretches…", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
                patches == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Couldn't reach OpenStreetMap's servers to check for unlit or isolated stretches.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRetryPatches) { Text("Retry", color = RouteRed) }
                }
                patches.isEmpty() -> Text(
                    "No unlit or isolated stretches found in OpenStreetMap along this route.",
                    style = MaterialTheme.typography.labelSmall,
                    color = RouteGreen
                )
                else -> patches.forEach { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(RouteRedBg)
                            .padding(10.dp)
                    ) {
                        Text("⚠", color = RouteRed, style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                "${p.kind.label} · ${formatDistance(p.atMeters)} in",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = RouteRed
                            )
                            Text(p.detail, style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                        }
                    }
                }
            }
        }

        if (facts.isNotEmpty()) {
            SectionCard {
                Text("Along the way", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                facts.forEach { fact ->
                    val tint = if (fact.positive) RouteGreen else RouteRed
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (fact.positive) RouteGreenBg else RouteRedBg)
                            .padding(10.dp)
                    ) {
                        Text(if (fact.positive) "✓" else "⚠", color = tint, style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.width(8.dp))
                        Text(fact.text, style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                    }
                }
            }
        }

        state.assessment?.let {
            AiAnalysisPanel(it, chosen.corridor.key, state.accuracy, aiStateOf(it, state.aiAnalyzing, tripOver = false))
        }

        Button(
            onClick = onStart,
            enabled = state.isWalkable(chosen),
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = color)
        ) {
            Icon(Icons.Filled.Shield, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (state.safeSelected) "Start safe route" else "Start ${chosen.label.lowercase()} route")
        }
    }
}

/** "Safest route" for the recommended one; the other is "Fastest route" only if it really is faster. */
internal fun titleFor(route: RouteOption, isSafe: Boolean, counterpart: RouteOption): String = when {
    isSafe -> "Safest route"
    route.minutes < counterpart.minutes -> "Fastest route"
    else -> "Other route"
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceCard)
            .border(0.5.dp, BorderHairline, RoundedCornerShape(18.dp))
            .padding(16.dp),
        content = content
    )
}

@Composable
private fun FactorBar(label: String, measured: String, score: Int) {
    val color = if (score >= 50) RouteGreen else RouteRed
    Column(Modifier.padding(bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = TextPrimary)
            Spacer(Modifier.width(6.dp))
            Text(measured, style = MaterialTheme.typography.labelSmall, color = TextSecondary, modifier = Modifier.weight(1f))
            Text("$score", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = color)
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(100.dp))
                .background(BorderHairline)
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(score / 100f)
                    .clip(RoundedCornerShape(100.dp))
                    .background(color)
            )
        }
    }
}
