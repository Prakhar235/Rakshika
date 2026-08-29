package com.rakshika.saathi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rakshika.saathi.data.Config
import com.rakshika.saathi.data.GeoPoint
import com.rakshika.saathi.data.TripEvent
import com.rakshika.saathi.data.TripRepository
import com.rakshika.saathi.data.TripSnapshot
import com.rakshika.saathi.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TrackerScreen(modifier: Modifier = Modifier) {
    val snapshot by TripRepository.snapshot.collectAsState()
    val events by TripRepository.events.collectAsState()
    val connected by TripRepository.connected.collectAsState()

    Column(
        modifier
            .fillMaxSize()
            .background(PageBg)
            .padding(16.dp)
    ) {
        Header(connected)
        Spacer(Modifier.height(12.dp))

        val s = snapshot
        if (s == null || !s.active) {
            EmptyState()
        } else {
            if (s.sos) {
                SosBanner(s)
                Spacer(Modifier.height(12.dp))
            }
            MapCard(s)
            Spacer(Modifier.height(12.dp))
            TripCard(s)
        }

        Spacer(Modifier.height(16.dp))
        Text("Updates", style = MaterialTheme.typography.titleSmall, color = InkSecondary)
        Spacer(Modifier.height(8.dp))
        EventLog(events)
    }
}

@Composable
private fun Header(connected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("RakshikaSaathi", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Watching ${Config.COMPANION_NAME}'s ride", style = MaterialTheme.typography.bodySmall, color = InkSecondary)
        }
        Box(Modifier.size(9.dp).clip(CircleShape).background(if (connected) SaathiGreen else SaathiAmber))
        Spacer(Modifier.width(6.dp))
        Text(if (connected) "Live" else "Connecting…", style = MaterialTheme.typography.labelMedium, color = InkSecondary)
    }
}

@Composable
private fun EmptyState() {
    SaathiCard {
        Column(Modifier.padding(20.dp)) {
            Text("No active ride", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "You'll see ${Config.COMPANION_NAME}'s live location here the moment a ride starts in the Rakshika app " +
                    "(Demo tab → Try it yourself → pick a route → Start). SOS and arrival alerts arrive as notifications.",
                style = MaterialTheme.typography.bodyMedium,
                color = InkSecondary
            )
        }
    }
}

@Composable
private fun SosBanner(s: TripSnapshot) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF3A0D1A))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("🚨", fontSize = 22.sp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text("SOS triggered", color = Color(0xFFFF9DB4), fontWeight = FontWeight.Bold)
            Text(
                "${Config.COMPANION_NAME} needs help on the way to ${s.destName}",
                color = Color(0xFFF3D3DA),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun MapCard(s: TripSnapshot) {
    SaathiCard {
        Column(Modifier.padding(14.dp)) {
            Text("Live location", style = MaterialTheme.typography.labelMedium, color = InkSecondary)
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFEDEBE3))
            ) {
                RouteMap(s)
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(CardBg)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(routeColor(s)))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        s.current?.let { "%.5f, %.5f".format(it.lat, it.lng) } ?: "no fix yet",
                        style = MaterialTheme.typography.labelSmall,
                        color = InkSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun RouteMap(s: TripSnapshot) {
    val pts = s.polyline
    Canvas(Modifier.fillMaxSize()) {
        if (pts.size < 2) return@Canvas
        val pad = 24f
        val minLat = pts.minOf { it.lat }
        val maxLat = pts.maxOf { it.lat }
        val minLng = pts.minOf { it.lng }
        val maxLng = pts.maxOf { it.lng }
        val spanLat = (maxLat - minLat).takeIf { it > 1e-9 } ?: 1e-9
        val spanLng = (maxLng - minLng).takeIf { it > 1e-9 } ?: 1e-9

        fun project(p: GeoPoint): Offset {
            val x = pad + ((p.lng - minLng) / spanLng).toFloat() * (size.width - 2 * pad)
            val y = pad + ((maxLat - p.lat) / spanLat).toFloat() * (size.height - 2 * pad)
            return Offset(x, y)
        }

        val screen = pts.map { project(it) }
        for (i in 1 until screen.size) {
            drawLine(routeColor(s), screen[i - 1], screen[i], strokeWidth = 8f, cap = StrokeCap.Round)
        }
        marker(screen.first(), SaathiBlue)
        marker(screen.last(), SaathiRed)
        s.current?.let {
            val c = project(it)
            drawCircle(routeColor(s).copy(alpha = 0.22f), radius = 22f, center = c)
            drawCircle(routeColor(s), radius = 11f, center = c)
            drawCircle(Color.White, radius = 11f, center = c, style = Stroke(4f))
        }
    }
}

private fun DrawScope.marker(p: Offset, color: Color) {
    drawCircle(color, radius = 8f, center = p)
    drawCircle(Color.White, radius = 8f, center = p, style = Stroke(3f))
}

private fun routeColor(s: TripSnapshot) = if (s.routeKind == "fast") SaathiAmber else SaathiGreen

@Composable
private fun TripCard(s: TripSnapshot) {
    SaathiCard {
        Column(Modifier.padding(16.dp)) {
            Text(s.destName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (s.destArea.isNotBlank()) {
                Text(s.destArea, style = MaterialTheme.typography.bodySmall, color = InkSecondary)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(
                    text = if (s.routeKind == "safe") "Safest route" else "Faster route",
                    color = routeColor(s)
                )
                Spacer(Modifier.width(8.dp))
                Text("${s.routeLabel} · safety ${s.safetyScore}/100", style = MaterialTheme.typography.bodySmall, color = InkSecondary)
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (s.arrived) "Arrived" else "${s.etaMinutesLeft}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (s.arrived) SaathiGreen else InkPrimary
                )
                if (!s.arrived) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "min left",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSecondary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                Text("${(s.progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium, color = InkSecondary)
            }
            Spacer(Modifier.height(8.dp))
            ProgressBar(s.progress.toFloat(), routeColor(s))
            if (s.reasons.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(s.reasons.joinToString("  ·  "), style = MaterialTheme.typography.labelSmall, color = InkSecondary)
            }
        }
    }
}

@Composable
private fun ProgressBar(fraction: Float, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(7.dp)
            .clip(RoundedCornerShape(100))
            .background(Hairline)
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(100))
                .background(color)
        )
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(100))
            .border(1.dp, color, RoundedCornerShape(100))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun EventLog(events: List<TripEvent>) {
    if (events.isEmpty()) {
        Text("No updates yet.", style = MaterialTheme.typography.bodySmall, color = InkSecondary)
        return
    }
    val fmt = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(events) { ev ->
            SaathiCard {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(iconFor(ev.kind), fontSize = 16.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ev.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(ev.detail, style = MaterialTheme.typography.bodySmall, color = InkSecondary)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(fmt.format(Date(ev.time)), style = MaterialTheme.typography.labelSmall, color = InkSecondary)
                }
            }
        }
    }
}

private fun iconFor(kind: TripEvent.Kind) = when (kind) {
    TripEvent.Kind.START -> "🚶"
    TripEvent.Kind.PROGRESS -> "📍"
    TripEvent.Kind.SOS -> "🚨"
    TripEvent.Kind.ARRIVED -> "✅"
    TripEvent.Kind.ENDED -> "🏁"
    TripEvent.Kind.INFO -> "ℹ️"
}

@Composable
private fun SaathiCard(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .border(1.dp, Hairline, RoundedCornerShape(16.dp))
    ) { content() }
}
