package com.rakshika.app.ui.screens.demo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rakshika.app.data.model.ContactStatus
import com.rakshika.app.demo.DemoNarrator
import com.rakshika.app.demo.DemoScene
import com.rakshika.app.demo.DemoScript
import com.rakshika.app.demo.DemoState
import com.rakshika.app.demo.DemoView
import com.rakshika.app.demo.DemoViewModel
import com.rakshika.app.ui.mapkit.ROUTE_A
import com.rakshika.app.ui.mapkit.ROUTE_B
import com.rakshika.app.ui.mapkit.SOS_PATH
import com.rakshika.app.ui.mapkit.drawMarker
import com.rakshika.app.ui.mapkit.drawRoadsAndBlocks
import com.rakshika.app.ui.mapkit.drawRoute
import com.rakshika.app.ui.mapkit.drawTravelDot
import com.rakshika.app.ui.mapkit.pointAt
import com.rakshika.app.ui.screens.ride.RideScreen
import com.rakshika.app.ui.theme.*

private enum class DemoMode { WATCH, RIDE }

@Composable
fun DemoScreen() {
    var mode by remember { mutableStateOf(DemoMode.WATCH) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfacePage)
    ) {
        Column(Modifier.padding(20.dp, 20.dp, 20.dp, 0.dp)) {
            Text("Demo", style = MaterialTheme.typography.titleLarge)
            Text(
                "Watch a narrated walkthrough, or pick a conditions dataset and search a destination — the safest route is predicted on-device by RAG over that data.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }

        Spacer(Modifier.height(16.dp))

        ModeToggle(mode = mode, onChange = { mode = it }, modifier = Modifier.padding(horizontal = 20.dp))

        Spacer(Modifier.height(4.dp))

        Box(Modifier.weight(1f)) {
            if (mode == DemoMode.WATCH) {
                NarratedDemoContent()
            } else {
                RideScreen()
            }
        }
    }
}

@Composable
private fun ModeToggle(mode: DemoMode, onChange: (DemoMode) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceCard)
            .border(0.5.dp, BorderHairline, RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ModeTab("Watch demo", mode == DemoMode.WATCH, Modifier.weight(1f)) { onChange(DemoMode.WATCH) }
        ModeTab("Try it yourself", mode == DemoMode.RIDE, Modifier.weight(1f)) { onChange(DemoMode.RIDE) }
    }
}

@Composable
private fun ModeTab(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) SurfacePage else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) TextPrimary else TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun NarratedDemoContent(viewModel: DemoViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val narrator = remember { DemoNarrator(context) }
    DisposableEffect(Unit) { onDispose { narrator.shutdown() } }

    Column(modifier = Modifier.fillMaxSize()) {
        SceneTabs(
            active = state.scene,
            onSelect = { viewModel.selectScene(it) },
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        Spacer(Modifier.height(14.dp))

        Box(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFFF1EFE8))
                .border(0.5.dp, BorderHairline, RoundedCornerShape(20.dp))
        ) {
            if (state.view == DemoView.HOME) {
                DemoHomeView(state)
            } else {
                DemoMapView(state)
            }
        }

        Spacer(Modifier.height(14.dp))

        TransportBar(
            state = state,
            onPlayPause = { viewModel.togglePlay { text -> narrator.speak(text) } },
            onMuteToggle = { viewModel.toggleMute() },
            onBeatSelected = { viewModel.goToBeat(it) },
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        Spacer(Modifier.height(10.dp))

        CaptionCard(state.caption, modifier = Modifier.padding(horizontal = 20.dp))

        Spacer(Modifier.height(10.dp))

        TelemetryPanel(state, modifier = Modifier.padding(horizontal = 20.dp))

        Spacer(Modifier.height(20.dp))
    }
}

/* ---------------- Rail controls ---------------- */

@Composable
private fun SceneTabs(active: DemoScene, onSelect: (DemoScene) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceCard)
            .border(0.5.dp, BorderHairline, RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SceneTab("01 · SOS Tracking", active == DemoScene.SOS, Modifier.weight(1f)) { onSelect(DemoScene.SOS) }
        SceneTab("02 · Safe Route", active == DemoScene.ROUTE, Modifier.weight(1f)) { onSelect(DemoScene.ROUTE) }
    }
}

@Composable
private fun SceneTab(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) SurfacePage else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) TextPrimary else TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun TransportBar(
    state: DemoState,
    onPlayPause: () -> Unit,
    onMuteToggle: () -> Unit,
    onBeatSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val beats = DemoScript.beatsFor(state.scene)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onPlayPause, colors = ButtonDefaults.buttonColors(containerColor = RakshikaRed)) {
            Icon(
                if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(if (state.isPlaying) "Pause" else "Play demo")
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(SurfaceCard)
                .border(0.5.dp, BorderHairline, CircleShape)
                .clickable(onClick = onMuteToggle),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (state.isMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                contentDescription = "Toggle voiceover",
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            beats.forEachIndexed { index, _ ->
                val active = index <= state.beatIndex
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (active) RakshikaRed else BorderHairline)
                        .clickable { onBeatSelected(index) }
                )
            }
        }
    }
}

@Composable
private fun CaptionCard(caption: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceCard)
            .border(0.5.dp, BorderHairline, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.RecordVoiceOver, contentDescription = null, tint = RakshikaRed, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(caption, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TelemetryPanel(state: DemoState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceCard)
            .border(0.5.dp, BorderHairline, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        TelemetryRow("STATUS", state.teleStatus)
        TelemetryRow("DETAIL", state.teleDetail)
        TelemetryRow("CONTACTS", state.teleContacts)
    }
}

@Composable
private fun TelemetryRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.labelSmall, color = TextPrimary)
    }
}

/* ---------------- Home (SOS hold) view ---------------- */

@Composable
private fun DemoHomeView(state: DemoState) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(state.sosHolding) {
        if (state.sosHolding) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(1500, easing = LinearEasing))
        } else {
            progress.snapTo(0f)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))
        Text("Rakshika", style = MaterialTheme.typography.titleMedium)
        Text(
            "You're protected. Hold the button below in an emergency.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        Box(modifier = Modifier.size(140.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val strokeWidth = 7.dp.toPx()
                drawCircle(
                    color = Color(0xFFF0997B).copy(alpha = .25f),
                    radius = size.minDimension / 2 - strokeWidth,
                    center = center
                )
                if (progress.value > 0f) {
                    drawArc(
                        color = RakshikaRedDark,
                        startAngle = -90f,
                        sweepAngle = 360f * progress.value,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(RakshikaRed),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Warning, contentDescription = "SOS", tint = Color.White, modifier = Modifier.size(22.dp))
                    Text("SOS", color = Color.White, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Hold for 2 seconds to trigger", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}

/* ---------------- Map view (both scenes) ---------------- */

@Composable
private fun DemoMapView(state: DemoState) {
    val trackAnim = remember { Animatable(if (state.markedSafe) 1f else 0f) }
    val routeAnim = remember { Animatable(if (state.walking) 1f else 0f) }

    LaunchedEffect(state.scene, state.beatIndex) {
        val beat = DemoScript.beatsFor(state.scene).getOrNull(state.beatIndex)
        if (state.scene == DemoScene.SOS) {
            when {
                beat?.animateTrack == true -> {
                    trackAnim.snapTo(0f)
                    trackAnim.animateTo(1f, tween(2200, easing = LinearEasing))
                }
                state.markedSafe -> trackAnim.snapTo(1f)
                else -> trackAnim.snapTo(0f)
            }
        }
        if (state.scene == DemoScene.ROUTE && beat?.animateRoute == true) {
            routeAnim.snapTo(0f)
            routeAnim.animateTo(1f, tween(2600, easing = LinearEasing))
        }
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(Color(0xFFF1EFE8))
            drawRoadsAndBlocks()
            if (state.scene == DemoScene.SOS) {
                drawMarker(SOS_PATH.first(), Color(0xFF378ADD), ring = true)
                if (state.sosTriggered) {
                    drawRoute(SOS_PATH, Color(0xFF378ADD), width = 5f, dashed = false)
                    if (trackAnim.value > 0f) {
                        val p = pointAt(SOS_PATH, size.width, size.height, trackAnim.value)
                        drawTravelDot(p, Color(0xFFD8365E))
                    }
                }
            } else {
                drawMarker(ROUTE_B.first(), Color(0xFF378ADD), ring = true)
                if (state.routesFound) {
                    drawRoute(ROUTE_A, Color(0xFFC98A2E), width = 3.5f, dashed = true)
                    drawRoute(ROUTE_B, Color(0xFF3B8F5C), width = 5.5f, dashed = false)
                    drawMarker(ROUTE_B.last(), Color(0xFFD8365E))
                }
                if (state.walking) {
                    val p = pointAt(ROUTE_B, size.width, size.height, routeAnim.value)
                    drawTravelDot(p, Color(0xFF3B8F5C))
                }
            }
        }

        if (state.scene == DemoScene.SOS) {
            SosOverlays(state)
        } else {
            RouteOverlays(state)
        }
    }
}

@Composable
private fun BoxScope.SosOverlays(state: DemoState) {
    if (state.sharingLive) {
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceCard)
                .border(0.5.dp, BorderHairline, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PulsingDot(RakshikaGreen)
            Spacer(Modifier.width(6.dp))
            Text("Sharing live", style = MaterialTheme.typography.labelSmall)
        }
    }

    if (state.contactAmma != null || state.contactRohan != null) {
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            horizontalAlignment = Alignment.End
        ) {
            state.contactAmma?.let { ContactChip("Amma", it) }
            if (state.contactAmma != null && state.contactRohan != null) Spacer(Modifier.height(6.dp))
            state.contactRohan?.let { ContactChip("Rohan", it) }
        }
    }

    if (state.sharingLive && !state.markedSafe) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceCard)
                .border(0.5.dp, BorderHairline, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text("Accuracy 8m · Updated just now", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
    }

    if (state.markedSafe) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(RakshikaGreenBg)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = RakshikaGreen, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("She checked in — marked safe", style = MaterialTheme.typography.labelSmall, color = RakshikaGreen)
        }
    }
}

@Composable
private fun BoxScope.RouteOverlays(state: DemoState) {
    if (state.searching) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceCard)
                .border(0.5.dp, BorderHairline, RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF378ADD)))
                Spacer(Modifier.width(7.dp))
                Text("Hostel", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(RakshikaRed))
                Spacer(Modifier.width(7.dp))
                Text("MG Road Metro", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(11.dp), strokeWidth = 1.5.dp, color = RakshikaRed)
                Spacer(Modifier.width(6.dp))
                Text("Finding the safest way…", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
        }
    }

    if (state.routesFound) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 78.dp, start = 128.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(RakshikaGreen)
                .padding(horizontal = 9.dp, vertical = 4.dp)
        ) {
            Text("Recommended", color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 110.dp, start = 40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(RakshikaAmberBg)
                .border(0.5.dp, RakshikaAmber.copy(alpha = .4f), RoundedCornerShape(8.dp))
                .padding(horizontal = 9.dp, vertical = 4.dp)
        ) {
            Text("Unlit · 2 incidents", color = RakshikaAmber, style = MaterialTheme.typography.labelSmall)
        }
    }

    if (state.routeRecommended && !state.walking) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            ReasonChipsRow()
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(RakshikaGreen)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Navigation, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Start · Safe Route", color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
    }

    if (state.walking) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceCard)
                .border(0.5.dp, BorderHairline, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Column(horizontalAlignment = Alignment.End) {
                Text("13 min", style = MaterialTheme.typography.titleSmall)
                Text("ETA · ROUTE B", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 12.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceCard)
                .border(0.5.dp, BorderHairline, RoundedCornerShape(10.dp))
                .padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PulsingDot(RakshikaGreen)
            Spacer(Modifier.width(6.dp))
            Text("Sharing with Amma", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ReasonChipsRow() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ReasonChip("✓ Well-lit", RakshikaGreen)
            ReasonChip("✓ Higher foot traffic", RakshikaGreen)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ReasonChip("✓ Avoids 2 incidents", RakshikaGreen)
            ReasonChip("Only +4 min", TextSecondary)
        }
    }
}

@Composable
private fun ReasonChip(text: String, tint: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(SurfaceCard)
            .border(0.5.dp, BorderHairline, RoundedCornerShape(100.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

@Composable
private fun ContactChip(name: String, status: ContactStatus) {
    val (label, color) = when (status) {
        ContactStatus.NOTIFIED -> "Notified" to RakshikaAmber
        ContactStatus.SEEN -> "Seen" to RakshikaGreen
        ContactStatus.RESPONDING -> "Responding" to RakshikaRed
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceCard)
            .border(0.5.dp, BorderHairline, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .widthIn(min = 110.dp),
        horizontalAlignment = Alignment.End
    ) {
        Text(name, style = MaterialTheme.typography.labelMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun PulsingDot(color: Color) {
    val infinite = rememberInfiniteTransition(label = "pulse")
    val scale by infinite.animateFloat(
        initialValue = 0.6f,
        targetValue = 2.4f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "pulseScale"
    )
    val alpha by infinite.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "pulseAlpha"
    )
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(7.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(color.copy(alpha = alpha))
        )
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
    }
}
