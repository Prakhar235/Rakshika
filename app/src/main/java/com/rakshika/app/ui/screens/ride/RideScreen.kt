package com.rakshika.app.ui.screens.ride

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rakshika.app.data.model.ContactStatus
import com.rakshika.app.geo.formatDistance
import com.rakshika.app.geo.geoPointAt
import com.rakshika.app.geo.pathLengthMeters
import com.rakshika.app.live.LiveShareConfig
import com.rakshika.app.live.LiveShareStatus
import com.rakshika.app.location.DeviceLocation
import com.rakshika.app.routing.RouteFact
import com.rakshika.app.ride.ORIGIN
import com.rakshika.app.ride.Place
import com.rakshika.app.ride.RideState
import com.rakshika.app.ride.RideStep
import com.rakshika.app.ride.RideViewModel
import com.rakshika.app.ride.RouteOption
import com.rakshika.app.ride.resolvedGeoPath
import com.rakshika.app.ui.mapkit.ROUTE_A
import com.rakshika.app.ui.mapkit.ROUTE_B
import com.rakshika.app.ui.mapkit.RealMap
import com.rakshika.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun RideScreen(viewModel: RideViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val liveStatus by viewModel.liveStatus.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        when (state.step) {
            RideStep.SEARCH -> SearchStep(
                state, viewModel::updateQuery, viewModel::selectDestination, viewModel::refreshDeviceLocation
            )
            RideStep.ROUTES -> RoutesStep(state, viewModel::selectRoute, viewModel::backToSearch, viewModel::startRide)
            RideStep.RIDING -> RidingStep(state, liveStatus, viewModel::triggerSos, viewModel::reroute)
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
    onLocationReady: () -> Unit
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onLocationReady() }
    LaunchedEffect(Unit) {
        if (DeviceLocation.hasPermission(context)) {
            onLocationReady()
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

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
        Spacer(Modifier.height(6.dp))
        Text(
            if (state.usingDeviceLocation) "Searching near your current location" else "Searching near the demo default location",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )

        Spacer(Modifier.height(18.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.query.isBlank()) "Nearby" else "Results",
                style = MaterialTheme.typography.titleSmall,
                color = TextSecondary
            )
            if (state.searching) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(modifier = Modifier.size(11.dp), strokeWidth = 1.5.dp, color = RakshikaRed)
            }
        }
        Spacer(Modifier.height(8.dp))

        if (state.suggestions.isEmpty()) {
            if (!state.searching) {
                Text("No matches — try another name.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
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

        // Real routed road geometry when the destination was geocoded, else the fixed mock-map stand-in.
        val safePathGeo = routes.safe.resolvedGeoPath()
        val fastPathGeo = routes.fast.resolvedGeoPath()
        val safeDistance = pathLengthMeters(safePathGeo)
        val fastDistance = pathLengthMeters(fastPathGeo)

        Box(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(20.dp))
                .border(0.5.dp, BorderHairline, RoundedCornerShape(20.dp))
        ) {
            // Both routes are always plotted — the selected one drawn bolder and on top.
            RealMap(
                modifier = Modifier.fillMaxSize(),
                primaryRoute = if (state.safeSelected) safePathGeo else fastPathGeo,
                primaryColor = if (state.safeSelected) RakshikaGreen else RakshikaRed,
                primaryWidth = 12f,
                secondaryRoute = if (state.safeSelected) fastPathGeo else safePathGeo,
                secondaryColor = if (state.safeSelected) RakshikaRed else RakshikaGreen,
                secondaryWidth = 6f
            )
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceCard)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(RakshikaGreen))
                Spacer(Modifier.width(5.dp))
                Text(formatDistance(safeDistance), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(7.dp).clip(CircleShape).background(RakshikaRed))
                Spacer(Modifier.width(5.dp))
                Text(formatDistance(fastDistance), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
            RouteSourceBadge(
                real = routes.safe.geoPath != null || routes.fast.geoPath != null,
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
            )
        }

        Spacer(Modifier.height(14.dp))

        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RouteCard(routes.safe, selected = state.safeSelected, accent = RakshikaGreen) { onSelectRoute(true) }
            RouteCard(routes.fast, selected = !state.safeSelected, accent = RakshikaRed) { onSelectRoute(false) }

            if (chosen.facts.isNotEmpty()) RouteFactsPanel(chosen)

            if (!state.safeSelected) {
                Text(
                    "You've picked the lower-scoring route. Rakshika recommends the " +
                        "${routes.safe.label.lowercase()} (${routes.safe.safetyScore}/100).",
                    style = MaterialTheme.typography.labelSmall,
                    color = RakshikaAmber
                )
            }

            RouteSummaryFooter(routes.summary)

            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.safeSelected) RakshikaGreen else RakshikaRed
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
            if (route.scoredByModel) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(accent.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("AI risk score", style = MaterialTheme.typography.labelSmall, color = accent)
                }
            }
            Spacer(Modifier.weight(1f))
            SafetyMeter(route.safetyScore, accent)
        }
        // When the model scored this route, its own explanation is the headline line — the
        // heuristic facts (still in route.facts/reasons) stay available in the "Why" panel below
        // rather than being folded in here too and diluting the model's actual answer.
        if (route.modelReason != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                route.modelReason,
                style = MaterialTheme.typography.labelSmall,
                fontStyle = FontStyle.Italic,
                color = TextPrimary
            )
        } else if (route.reasons.isNotEmpty()) {
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

/** Shows whether the plotted route is live real road geometry or the offline mock-map fallback. */
@Composable
private fun RouteSourceBadge(real: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceCard)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (real) RakshikaGreen else RakshikaAmber))
        Spacer(Modifier.width(5.dp))
        Text(
            if (real) "Live roads" else "Simulated route — no live roads found",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
    }
}

@Composable
private fun RouteFactsPanel(route: RouteOption) {
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
                "Why — ${route.facts.size} facts from Google Directions",
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
            route.facts.forEach { fact -> RouteFactRow(fact) }
        }
    }
}

@Composable
private fun RouteFactRow(fact: RouteFact) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(if (fact.positive) "✓" else "⚠", color = if (fact.positive) RakshikaGreen else RakshikaAmber)
        Spacer(Modifier.width(8.dp))
        Text(fact.text, style = MaterialTheme.typography.labelSmall, color = TextPrimary, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun RouteSummaryFooter(summary: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(RakshikaGreenBg)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(summary, style = MaterialTheme.typography.labelSmall, color = TextPrimary)
    }
}

/* ---------------- Riding ---------------- */

@Composable
private fun RidingStep(state: RideState, liveStatus: LiveShareStatus, onSos: () -> Unit, onReroute: () -> Unit) {
    val destination = state.destination ?: return

    val chosen = if (state.safeSelected) state.routes?.safe else state.routes?.fast
    val alt = if (state.safeSelected) state.routes?.fast else state.routes?.safe
    // Safe (main-road) route is always green, the shorter/riskier one always red — on the map and everywhere else.
    val color = if (state.safeSelected) RakshikaGreen else RakshikaRed
    val altColor = if (state.safeSelected) RakshikaRed else RakshikaGreen

    // Real routed road geometry when the destination was geocoded, else the fixed mock-map stand-in.
    val pathGeo = chosen?.resolvedGeoPath() ?: LiveShareConfig.toGeoPath(ROUTE_B)
    val altPathGeo = alt?.resolvedGeoPath() ?: LiveShareConfig.toGeoPath(ROUTE_A)
    val totalMeters = pathLengthMeters(pathGeo)
    val currentGeo = geoPointAt(pathGeo, state.rideProgress)
    val remainingMeters = totalMeters * (1 - state.rideProgress)

    Box(Modifier.fillMaxSize()) {
        // The alternate (not taken) route stays visible, thin and muted, for context.
        RealMap(
            modifier = Modifier.fillMaxSize(),
            primaryRoute = pathGeo,
            primaryColor = color,
            primaryWidth = 12f,
            secondaryRoute = altPathGeo,
            secondaryColor = altColor,
            secondaryWidth = 5f,
            current = currentGeo,
            zoomToCurrentOnStart = true
        )

        RouteSourceBadge(
            real = chosen?.geoPath != null,
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)
        )

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
                    Text(
                        "${formatDistance(remainingMeters)} left",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            state.contactAmma?.let { ContactBadge("Amma", it) }
            Spacer(Modifier.height(6.dp))
            state.contactRohan?.let { ContactBadge("Rohan", it) }
        }

        if (state.rerouting) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceCard)
                    .border(0.5.dp, BorderHairline, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = RakshikaRed)
                Spacer(Modifier.width(8.dp))
                Text("Recalculating route from here…", style = MaterialTheme.typography.labelSmall)
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp, 16.dp, 16.dp, 28.dp)
                .size(52.dp)
                .clip(CircleShape)
                .background(SurfaceCard)
                .border(0.5.dp, BorderHairline, CircleShape)
                .clickable(enabled = !state.rerouting, onClick = onReroute),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Autorenew, contentDescription = "Reroute from here", tint = RakshikaRed, modifier = Modifier.size(22.dp))
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
