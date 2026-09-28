package com.rakshika.app.ui.screens.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rakshika.app.risk.AccuracyStats
import com.rakshika.app.risk.EquationSource
import com.rakshika.app.risk.EquationTerm
import com.rakshika.app.risk.EquationVersion
import com.rakshika.app.risk.EvidenceCap
import com.rakshika.app.risk.FeatureCatalog
import com.rakshika.app.risk.RiskStore
import com.rakshika.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * "Analysis" tab of the demo: the live risk model on this device, not a scripted mock — the
 * equation it would score the next route with right now, in equation form; the algorithm that
 * produced it; and how well it has matched riders so far. Backed by [AnalysisViewModel], which
 * reads the same on-device store [com.rakshika.app.risk.RiskLoop] learns into.
 */
@Composable
fun AnalysisScreen(viewModel: AnalysisViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val current = state.current

    if (state.loading || current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = RakshikaRed)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 14.dp, 16.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "Live data from this phone's own risk model: the discovery-prediction equation it currently scores routes with, " +
                    "the algorithm that keeps improving it, and how close its predictions have come to what riders actually felt.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
        item { EquationCard(current) }
        item { AlgorithmCard() }
        item { AccuracyCard(state.accuracy) }
        if (state.history.isNotEmpty()) {
            item { Text("Equation history", style = MaterialTheme.typography.titleSmall) }
            items(state.history) { HistoryRow(it) }
        }
    }
}

/* ---------------- cards ---------------- */

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceCard)
            .border(0.5.dp, BorderHairline, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content
    )
}

@Composable
private fun EquationCard(current: EquationVersion) {
    val eq = current.equation
    val label = if (current.version == 0) "Discovery equation · seed (v0)" else "Discovery equation · learned v${current.version}"
    SectionCard(title = label) {
        Text(
            "score = clamp(${fmt(eq.base)} + Σ terms, 1, 99)   ·   higher = safer",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(2.dp))
        eq.terms.forEach { EquationTermRow(it) }
        if (eq.terms.isEmpty()) {
            Text("No terms.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Confidence ${pct(current.confidence)} · ${sourceLabel(current.source)} · ${formatDate(current.createdAt)}",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
        if (current.note.isNotBlank()) {
            Text(current.note, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
        }
    }
}

@Composable
private fun EquationTermRow(term: EquationTerm) {
    val label = FeatureCatalog.get(term.feature)?.label ?: term.feature
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            "+ clamp(${fmtSigned(term.weight)} × (${term.feature} − ${fmt(term.reference)}), ±${fmt(term.cap)})",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = TextPrimary
        )
        Text(
            if (term.note.isBlank()) label else "$label — ${term.note}",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
    }
}

@Composable
private fun AlgorithmCard() {
    SectionCard(title = "How the equation is trained") {
        Text(
            "Not a fixed formula — an LLM-in-the-loop search. Before a ride, GPT (OpenAI Chat Completions, called from " +
                "RiskModelClient) may pull live data — OpenStreetMap lighting & footpaths, weather & daylight, nearby open " +
                "places — then returns a modified equation and a 1–99 prediction per route.",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "After the trip, the rider's star rating is fed back to the model as a target score. It proposes a new " +
                "equation (same weight/reference/cap shape shown above), changing each weight by roughly ≤30% from one " +
                "trip alone.",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "Acceptance gate: the app — not the model — recomputes both equations' mean absolute error over the last " +
                "${RiskStore.EXAMPLE_WINDOW} rider-rated trips. The proposal only becomes the next version if it fits at " +
                "least as well (±0.5 pt slack); otherwise the current equation is kept. This is what turns single-trip " +
                "feedback into a validated, monotonically-improving equation instead of chasing the last data point.",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "Reported confidence is capped by evidence, not self-belief: 35% with zero rated trips, +6 points per rated " +
                "trip, never above 95%.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun AccuracyCard(stats: AccuracyStats) {
    SectionCard(title = "Accuracy so far") {
        StatRow("Rider-rated trips", "${stats.ratedTrips}")
        StatRow("Mean absolute error", stats.meanAbsError?.let { "%.1f points".format(it) } ?: "No rated trips yet")
        StatRow(
            "Last trip's error",
            stats.lastError?.let {
                val direction = when { it > 0 -> "scored safer than it felt"; it < 0 -> "scored riskier than it felt"; else -> "exact" }
                "${if (it > 0) "+" else ""}$it pts ($direction)"
            } ?: "—"
        )
        StatRow("Evidence cap on confidence", pct(EvidenceCap.forRatedTrips(stats.ratedTrips)))
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
    }
}

@Composable
private fun HistoryRow(version: EquationVersion) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceCard)
            .border(0.5.dp, BorderHairline, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text("v${version.version} · ${sourceLabel(version.source)} · ${formatDate(version.createdAt)}", style = MaterialTheme.typography.labelMedium)
            if (version.note.isNotBlank()) Text(version.note, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
        Text(
            pct(version.confidence),
            style = MaterialTheme.typography.labelMedium,
            color = if (version.source == EquationSource.LEARNED) RakshikaGreen else TextSecondary
        )
    }
}

/* ---------------- formatting ---------------- */

private fun sourceLabel(source: EquationSource) = when (source) {
    EquationSource.SEED -> "Seed"
    EquationSource.LEARNED -> "Learned from feedback"
}

private fun fmt(n: Double): String = if (n == n.roundToInt().toDouble()) n.roundToInt().toString() else "%.2f".format(n)
private fun fmtSigned(n: Double): String = (if (n >= 0) "+" else "") + fmt(n)
private fun pct(fraction: Double): String = "${(fraction * 100).roundToInt()}%"
private fun formatDate(epochMs: Long): String = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(epochMs))
