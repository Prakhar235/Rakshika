package com.rakshika.app.ui.screens.ride

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dataset
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rakshika.app.data.model.ContactStatus
import com.rakshika.app.live.LiveShareStatus
import com.rakshika.app.rag.RagResult
import com.rakshika.app.rag.RouteCorridor
import com.rakshika.app.rag.RouteEvidence
import com.rakshika.app.rag.SafetyDatasets
import com.rakshika.app.ride.ORIGIN
import com.rakshika.app.ride.Place
import com.rakshika.app.ride.RideState
import com.rakshika.app.ride.RideStep
import com.rakshika.app.ride.RideViewModel
import com.rakshika.app.ride.RouteOption
import com.rakshika.app.ui.mapkit.ROUTE_A
import com.rakshika.app.ui.mapkit.ROUTE_B
import com.rakshika.app.ui.mapkit.drawMarker
import com.rakshika.app.ui.mapkit.drawRoadsAndBlocks
import com.rakshika.app.ui.mapkit.drawRoute
import com.rakshika.app.ui.mapkit.drawTravelDot
import com.rakshika.app.ui.mapkit.pointAt
import com.rakshika.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun RideScreen(viewModel: RideViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val liveStatus by viewModel.liveStatus.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        when (state.step) {
            RideStep.SEARCH -> SearchStep(state, viewModel::updateQuery, viewModel::selectDestination, viewModel::selectDataset)
            RideStep.ROUTES -> RoutesStep(state, viewModel::selectRoute, viewModel::backToSearch, viewModel::startRide)
            RideStep.RIDING -> RidingStep(state, liveStatus, viewModel::triggerSos)
            RideStep.ARRIVED -> ArrivedStep(state, viewModel::newRide)
        }
    }
}

/* ---------------- Search ---------------- */

@Composable
private fun SearchStep(
    state: RideState,
    onQuery: (String) -> Unit,
    onSelect: (Place) -> Unit,
    onSelectDataset: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceCard)
                .border(0.5.dp, BorderHairline, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.MyLocation, contentDescription = null, tint = Color(0xFF378ADD), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Current location", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Text("${ORIGIN.name} · ${ORIGIN.area}", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(12.dp))

        DatasetSelector(
            selectedId = state.selectedDatasetId,
            assessing = state.assessing,
            onSelect = onSelectDataset
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = state.query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Where are you headed?") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(Modifier.height(18.dp))

        Text(
            if (state.query.isBlank()) "Nearby" else "Results",
            style = MaterialTheme.typography.titleSmall,
            color = TextSecondary
        )
        Spacer(Modifier.height(8.dp))

        if (state.suggestions.isEmpty()) {
            Text("No matches — try another name.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.suggestions) { place ->
                    PlaceRow(place) { onSelect(place) }
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(place: Place, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceCard)
            .border(0.5.dp, BorderHairline, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.LocationOn, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(place.name, style = MaterialTheme.typography.bodyMedium)
            Text(place.area, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
    }
}

@Composable
private fun DatasetSelector(selectedId: String, assessing: Boolean, onSelect: (String) -> Unit) {
    val selected = SafetyDatasets.byId(selectedId)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceCard)
            .border(0.5.dp, BorderHairline, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Dataset, contentDescription = null, tint = RakshikaRed, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("Conditions dataset", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Spacer(Modifier.weight(1f))
            if (assessing) {
                CircularProgressIndicator(modifier = Modifier.size(11.dp), strokeWidth = 1.5.dp, color = RakshikaRed)
                Spacer(Modifier.width(6.dp))
                Text("embedding · retrieving", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SafetyDatasets.ALL.forEach { ds ->
                val isSel = ds.id == selectedId
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(if (isSel) RakshikaRed else SurfacePage)
                        .border(0.5.dp, if (isSel) RakshikaRed else BorderHairline, RoundedCornerShape(100.dp))
                        .clickable { onSelect(ds.id) }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text(
                        ds.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSel) Color.White else TextSecondary
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(selected.blurb, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

/* ---------------- Routes ---------------- */

@Composable
private fun RoutesStep(
    state: RideState,
    onSelectRoute: (Boolean) -> Unit,
    onBack: () -> Unit,
    onStart: () -> Unit
) {
    val destination = state.destination ?: return
    val routes = state.routes ?: return
    val chosen = if (state.safeSelected) routes.safe else routes.fast
    val chosenIsMain = chosen.corridor != RouteCorridor.BACK_LANE

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            modifier = Modifier.padding(20.dp, 20.dp, 20.dp, 0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(SurfaceCard)
                    .border(0.5.dp, BorderHairline, CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("${ORIGIN.name} → ${destination.name}", style = MaterialTheme.typography.titleMedium)
                Text(destination.area, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
            if (state.assessing) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = RakshikaRed)
            }
        }

        Spacer(Modifier.height(14.dp))

        Box(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFFF1EFE8))
                .border(0.5.dp, BorderHairline, RoundedCornerShape(20.dp))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(Color(0xFFF1EFE8))
                drawRoadsAndBlocks()
                drawMarker(ROUTE_B.first(), Color(0xFF378ADD), ring = true)
                // ROUTE_B is drawn along the main road, ROUTE_A along the back lane.
                drawRoute(
                    ROUTE_A,
                    if (chosenIsMain) Color(0xFFC98A2E) else Color(0xFF3B8F5C),
                    width = if (!chosenIsMain) 6f else 3.5f,
                    dashed = chosenIsMain
                )
                drawRoute(
                    ROUTE_B,
                    if (chosenIsMain) Color(0xFF3B8F5C) else Color(0xFFC98A2E),
                    width = if (chosenIsMain) 6f else 3.5f,
                    dashed = !chosenIsMain
                )
                drawMarker(ROUTE_B.last(), Color(0xFFD8365E))
            }
        }

        Spacer(Modifier.height(14.dp))

        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RouteCard(routes.safe, selected = state.safeSelected, accent = RakshikaGreen) { onSelectRoute(true) }
            RouteCard(routes.fast, selected = !state.safeSelected, accent = RakshikaAmber) { onSelectRoute(false) }

            if (chosen.evidence.isNotEmpty()) EvidencePanel(chosen)

            if (!state.safeSelected) {
                Text(
                    "You've picked the lower-scoring route. Rakshika recommends the " +
                        "${routes.safe.label.lowercase()} (${routes.safe.safetyScore}/100).",
                    style = MaterialTheme.typography.labelSmall,
                    color = RakshikaAmber
                )
            }

            state.rag?.let { RagFooter(it) }

            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.safeSelected) RakshikaGreen else RakshikaAmber
                )
            ) {
                Icon(Icons.Filled.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Start · ${chosen.label} route")
            }
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun RouteCard(route: RouteOption, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceCard)
            .border(if (selected) 1.5.dp else 0.5.dp, if (selected) accent else BorderHairline, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(route.label, style = MaterialTheme.typography.titleSmall, color = accent)
                if (route.recommended) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(RakshikaGreenBg)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("Safest", style = MaterialTheme.typography.labelSmall, color = RakshikaGreen)
                    }
                }
            }
            Text("${route.minutes} min", style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Shield, contentDescription = null, tint = accent, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
            Text("Safety score ${route.safetyScore}/100", style = MaterialTheme.typography.labelSmall, color = accent)
            Spacer(Modifier.weight(1f))
            SafetyMeter(route.safetyScore, accent)
        }
        if (route.reasons.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(route.reasons.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
    }
}

@Composable
private fun SafetyMeter(score: Int, accent: Color) {
    Box(
        modifier = Modifier
            .width(72.dp)
            .height(6.dp)
            .clip(RoundedCornerShape(100.dp))
            .background(BorderHairline)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(score.coerceIn(0, 100) / 100f)
                .clip(RoundedCornerShape(100.dp))
                .background(accent)
        )
    }
}

@Composable
private fun EvidencePanel(route: RouteOption) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceCard)
            .border(0.5.dp, BorderHairline, RoundedCornerShape(12.dp))
            .clickable { expanded = !expanded }
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Why — ${route.evidence.size} retrieved notes",
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary
            )
            Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            route.evidence.forEach { ev -> EvidenceRow(ev) }
        }
    }
}

@Composable
private fun EvidenceRow(ev: RouteEvidence) {
    val bad = ev.contribution < 0f
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(if (bad) "⚠" else "✓", color = if (bad) RakshikaAmber else RakshikaGreen)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(ev.doc.text, style = MaterialTheme.typography.labelSmall, color = TextPrimary)
            Spacer(Modifier.height(2.dp))
            Text(
                "${ev.doc.kind.name.lowercase().replace('_', ' ')} · sim ${"%.2f".format(ev.similarity)} · " +
                    "${if (bad) "" else "+"}${"%.1f".format(ev.contribution)} pts",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun RagFooter(rag: RagResult) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(RakshikaGreenBg)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(rag.recommendationText, style = MaterialTheme.typography.labelSmall, color = TextPrimary)
        Text(
            "On-device RAG · ${rag.datasetName} · indexed ${rag.indexedDocs} notes · " +
                "retrieved ${rag.retrievedDocs} · dim ${rag.embeddingDim} · top-k ${rag.topK}",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
    }
}

/* ---------------- Riding ---------------- */

@Composable
private fun RidingStep(state: RideState, liveStatus: LiveShareStatus, onSos: () -> Unit) {
    val destination = state.destination ?: return

    Box(Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(Color(0xFFF1EFE8))
            drawRoadsAndBlocks()
            val chosen = if (state.safeSelected) state.routes?.safe else state.routes?.fast
            val onMainRoad = chosen?.corridor != RouteCorridor.BACK_LANE
            val path = if (onMainRoad) ROUTE_B else ROUTE_A
            val color = if (state.safeSelected) Color(0xFF3B8F5C) else Color(0xFFC98A2E)
            drawMarker(path.first(), Color(0xFF378ADD), ring = true)
            drawRoute(path, color, width = 5.5f, dashed = false)
            drawMarker(path.last(), Color(0xFFD8365E))
            val p = pointAt(path, size.width, size.height, state.rideProgress)
            drawTravelDot(p, color)
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceCard)
                .border(0.5.dp, BorderHairline, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val (dotColor, label) = when (liveStatus) {
                LiveShareStatus.LIVE -> RakshikaGreen to "Live on Firebase"
                LiveShareStatus.CONNECTING -> RakshikaAmber to "Connecting to Firebase…"
                LiveShareStatus.ERROR -> RakshikaRed to "Firebase unreachable"
                LiveShareStatus.OFF -> TextSecondary to "Sharing live (local)"
            }
            Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
            Spacer(Modifier.width(6.dp))
            Text("$label · ${destination.name}", style = MaterialTheme.typography.labelSmall)
        }

        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            horizontalAlignment = Alignment.End
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceCard)
                    .border(0.5.dp, BorderHairline, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("${state.etaMinutesLeft} min", style = MaterialTheme.typography.titleSmall)
                    Text("ETA remaining", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
            }
            Spacer(Modifier.height(8.dp))
            state.contactAmma?.let { ContactBadge("Amma", it) }
            Spacer(Modifier.height(6.dp))
            state.contactRohan?.let { ContactBadge("Rohan", it) }
        }

        if (state.sosActive) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp, 16.dp, 16.dp, 104.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(RakshikaRedBg)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = RakshikaRedDark, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("SOS sent — Amma & Rohan notified", style = MaterialTheme.typography.labelSmall, color = RakshikaRedDark)
            }
        }

        RideSosButton(
            onTriggered = onSos,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp)
        )
    }
}

@Composable
private fun ContactBadge(name: String, status: ContactStatus) {
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
private fun RideSosButton(onTriggered: () -> Unit, modifier: Modifier = Modifier) {
    var pressProgress by remember { mutableFloatStateOf(0f) }
    var isPressed by remember { mutableStateOf(false) }

    LaunchedEffect(isPressed) {
        if (isPressed) {
            val stepMs = 16L
            val steps = 2000L / stepMs
            var current = 0
            while (isActive && isPressed && current < steps) {
                delay(stepMs)
                current++
                pressProgress = current / steps.toFloat()
            }
            if (isPressed && pressProgress >= 1f) {
                onTriggered()
            }
            pressProgress = 0f
        } else {
            pressProgress = 0f
        }
    }

    Box(
        modifier = modifier
            .size(76.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 5.dp.toPx()
            drawCircle(
                color = Color(0xFFF0997B).copy(alpha = .3f),
                radius = size.minDimension / 2 - strokeWidth,
                center = center
            )
            if (pressProgress > 0f) {
                drawArc(
                    color = RakshikaRedDark,
                    startAngle = -90f,
                    sweepAngle = 360f * pressProgress,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }
        Box(
            modifier = Modifier.size(56.dp).clip(CircleShape).background(RakshikaRed),
            contentAlignment = Alignment.Center
        ) {
            Text("SOS", color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/* ---------------- Arrived ---------------- */

@Composable
private fun ArrivedStep(state: RideState, onNewRide: () -> Unit) {
    val destination = state.destination
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(72.dp).clip(CircleShape).background(RakshikaGreenBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = RakshikaGreen, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("You've arrived safely", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        if (destination != null) {
            Text("at ${destination.name} · ${destination.area}", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
        Spacer(Modifier.height(4.dp))
        Text("Amma and Rohan were notified automatically.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onNewRide, colors = ButtonDefaults.buttonColors(containerColor = RakshikaRed)) {
            Text("Plan another ride")
        }
    }
}
