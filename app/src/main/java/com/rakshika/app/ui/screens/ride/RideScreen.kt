package com.rakshika.app.ui.screens.ride

import android.Manifest
import android.content.Intent
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import com.rakshika.app.risk.FeatureCatalog
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rakshika.app.data.model.ContactStatus
import com.rakshika.app.geo.formatDistance
import com.rakshika.app.geo.geoPointAt
import com.rakshika.app.geo.pathLengthMeters
import com.rakshika.app.live.LiveShareConfig
import com.rakshika.app.live.LiveShareStatus
import com.rakshika.app.location.DeviceLocation
import com.rakshika.app.routing.key
import com.rakshika.app.ride.ORIGIN
import com.rakshika.app.ride.Place
import com.rakshika.app.ride.RideState
import com.rakshika.app.ride.RideStep
import com.rakshika.app.ride.RideViewModel
import com.rakshika.app.ride.RouteOption
import com.rakshika.app.ride.isWalkable
import com.rakshika.app.ride.resolvedGeoPath
import com.rakshika.app.ui.mapkit.ROUTE_A
import com.rakshika.app.ui.mapkit.ROUTE_B
import com.rakshika.app.ui.mapkit.RealMap
import com.rakshika.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun RideScreen(
    viewModel: RideViewModel = viewModel(),
    onFakeCall: (() -> Unit)? = null,
    /** Told true while a ride is under way, so the host can hide its own chrome and go full screen. */
    onFullScreenChange: (Boolean) -> Unit = {},
    /** Told true once past the search step, so the host can drop its header and give the map the room. */
    onCompactChange: (Boolean) -> Unit = {},
    /** The real app (Home tab): the ride follows the phone's GPS instead of a simulated walk. */
    realMotion: Boolean = false,
    /** When set, the search screen shows a hold-to-send SOS — help is one gesture away before any ride starts.
     *  Returns what actually happened ("SOS sent to 2 contacts…", or why it wasn't), shown under the button. */
    onSos: (() -> String)? = null
) {
    // Set before any step composes, so the search step already asks for a real fix.
    remember(viewModel) { if (realMotion) viewModel.enableRealMotion(); true }
    val state by viewModel.state.collectAsState()
    val liveStatus by viewModel.liveStatus.collectAsState()
    val riding = state.step == RideStep.RIDING
    val compact = state.step != RideStep.SEARCH
    LaunchedEffect(riding) { onFullScreenChange(riding) }
    LaunchedEffect(compact) { onCompactChange(compact) }
    DisposableEffect(Unit) { onDispose { onFullScreenChange(false); onCompactChange(false) } }
    // Full screen hides the app's navigation, so back asks before ending the ride rather than leaving it running unseen.
    var confirmEnd by remember { mutableStateOf(false) }
    BackHandler(enabled = riding) { confirmEnd = true }
    if (confirmEnd && riding) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End this ride?") },
            text = { Text("Live sharing stops and your contacts won't get an arrival alert.") },
            confirmButton = {
                TextButton(onClick = { confirmEnd = false; viewModel.newRide() }) { Text("End ride", color = RouteRed) }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep going") } }
        )
    }
    // The score breakdown is a sub-page of the route choice, not a ride step of its own.
    var showScoreDetails by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.step) { if (state.step != RideStep.ROUTES) showScoreDetails = false }
    BackHandler(enabled = showScoreDetails) { showScoreDetails = false }

    Column(modifier = Modifier.fillMaxSize()) {
        when (state.step) {
            RideStep.SEARCH -> SearchStep(
                state, viewModel::updateQuery, viewModel::selectDestination, viewModel::refreshDeviceLocation, onSos
            )
            RideStep.ROUTES -> if (showScoreDetails) {
                RouteSafetyScreen(
                    state,
                    onBack = { showScoreDetails = false },
                    onStart = viewModel::startRide,
                    onRetryPatches = viewModel::retryRiskPatches
                )
            } else {
                RoutesStep(
                    state,
                    viewModel::selectRoute,
                    viewModel::backToSearch,
                    onWhy = { showScoreDetails = true },
                    onStart = viewModel::startRide,
                    onRetryPatches = viewModel::retryRiskPatches
                )
            }
            RideStep.RIDING -> RidingStep(
                state, liveStatus, viewModel::triggerSos, viewModel::reroute, onFakeCall, onEnd = { confirmEnd = true }
            )
            RideStep.ARRIVED -> ArrivedStep(state, viewModel::newRide, viewModel::submitFeedback)
        }
    }
}

/* ---------------- Search ---------------- */

@Composable
private fun SearchStep(
    state: RideState,
    onQuery: (String) -> Unit,
    onSelect: (Place) -> Unit,
    onLocationReady: () -> Unit,
    onSos: (() -> String)?
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
            Icon(Icons.Filled.MyLocation, contentDescription = null, tint = PinkInk, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Current location", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Text(
                    when {
                        !state.realMotion -> "${ORIGIN.name} · ${ORIGIN.area}"
                        state.waitingForFix -> "Waiting for GPS…"
                        state.usingDeviceLocation -> "Your location"
                        else -> "Location unavailable — turn on location"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
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
            when {
                state.usingDeviceLocation -> "Searching near your current location"
                state.realMotion -> "Turn on location to search and route from where you are"
                else -> "Searching near the demo default location"
            },
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

        Box(Modifier.weight(1f)) {
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

        if (onSos != null) {
            // Search stays on top; SOS sits under the thumb, always visible whatever the list shows.
            var outcome by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(outcome) {
                if (outcome != null) {
                    delay(6000)
                    outcome = null
                }
            }
            Spacer(Modifier.height(12.dp))
            outcome?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = SosPink,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    textAlign = TextAlign.Center
                )
            }
            SosTile(
                onTriggered = { outcome = onSos() },
                modifier = Modifier.fillMaxWidth(),
                hint = "Hold 2 sec to alert your contacts"
            )
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
    onWhy: () -> Unit,
    onStart: () -> Unit,
    onRetryPatches: () -> Unit
) {
    val context = LocalContext.current
    val destination = state.destination ?: return
    val routes = state.routes ?: return
    val chosen = if (state.safeSelected) routes.safe else routes.fast

    // Real routed road geometry when the destination was geocoded, else the fixed mock-map stand-in
    // (the real app never draws the stand-in — it isn't a road you could follow).
    val safePathGeo = routes.safe.resolvedGeoPath().takeIf { state.isWalkable(routes.safe) }
    val fastPathGeo = routes.fast.resolvedGeoPath().takeIf { state.isWalkable(routes.fast) }
    val canStart = state.isWalkable(chosen)

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(360.dp)
        ) {
            // Both routes are always plotted — the selected one drawn bolder and on top.
            RealMap(
                modifier = Modifier.fillMaxSize(),
                primaryRoute = (if (state.safeSelected) safePathGeo else fastPathGeo).orEmpty(),
                primaryColor = roleColor(state.safeSelected),
                primaryWidth = 12f,
                secondaryRoute = if (state.safeSelected) fastPathGeo else safePathGeo,
                secondaryColor = roleColor(!state.safeSelected),
                secondaryWidth = 8f,
                primaryDotted = !state.safeSelected,
                secondaryDotted = state.safeSelected,
                patches = (state.patchesFor(routes.safe).orEmpty() + state.patchesFor(routes.fast).orEmpty()).toMapPatches(),
                topInsetDp = 72,
                bottomInsetDp = 40
            )

            TripHeaderCard(
                from = originName(state),
                to = "${destination.name} · ${destination.area}",
                assessing = state.assessing,
                onBack = onBack,
                modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 14.dp)
            )

            Row(
                Modifier.align(Alignment.BottomStart).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LegendChip("Safest", RouteGreen, dotted = false)
                LegendChip(if (routes.fast.minutes < routes.safe.minutes) "Fastest" else "Other", RouteRed, dotted = true)
                PatchLegendChip(state, routes, onRetryPatches)
                if (routes.safe.geoPath == null && routes.fast.geoPath == null) RouteSourceBadge(real = false)
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(SurfaceCard)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SheetHandle()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Choose your route",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "Why this score?",
                    style = MaterialTheme.typography.labelMedium,
                    color = PinkInk,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onWhy).padding(4.dp)
                )
            }

            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RouteCard(state, routes.safe, isSafe = true, counterpart = routes.fast, selected = state.safeSelected) {
                    onSelectRoute(true)
                }
                RouteCard(state, routes.fast, isSafe = false, counterpart = routes.safe, selected = !state.safeSelected) {
                    onSelectRoute(false)
                }

                if (!state.safeSelected) {
                    Text(
                        "You've picked the lower-scoring route. Safe Maps recommends the " +
                            "${routes.safe.label.lowercase()} (${routes.safe.safetyScore}/100).",
                        style = MaterialTheme.typography.labelSmall,
                        color = RouteRed
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onStart,
                    enabled = canStart,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = roleColor(state.safeSelected))
                ) {
                    Icon(Icons.Filled.Shield, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            !canStart -> "No real route found"
                            state.safeSelected -> "Start safe route"
                            else -> "Start ${chosen.label.lowercase()} route"
                        }
                    )
                }
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(0.5.dp, BorderHairline, RoundedCornerShape(12.dp))
                        .clickable { shareRoute(context, destination.name, chosen) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Share, contentDescription = "Share route", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

private fun shareRoute(context: android.content.Context, destination: String, route: RouteOption) {
    val text = "I'm heading to $destination via the ${route.label.lowercase()} " +
        "(${route.minutes} min, Safe Maps safety score ${route.safetyScore}/100)."
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Share route"))
}

@Composable
private fun TripHeaderCard(from: String, to: String, assessing: Boolean, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceCard)
            .padding(start = 6.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(PinkInk))
                Spacer(Modifier.width(8.dp))
                Text(from, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
            HorizontalDivider(Modifier.padding(start = 15.dp, top = 6.dp, bottom = 6.dp), thickness = 0.5.dp, color = BorderHairline)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(TextPrimary))
                Spacer(Modifier.width(8.dp))
                Text(to, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (assessing) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = RosePink)
            } else {
                Pill(clockTime(), PinkInk, PinkInkBg)
            }
            if (isNightNow()) {
                Text("Night mode", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
        }
    }
}

@Composable
private fun LegendChip(label: String, color: Color, dotted: Boolean) {
    Row(
        Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(SurfaceCard)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(Modifier.width(16.dp).height(4.dp)) {
            drawLine(
                color,
                start = Offset(0f, size.height / 2),
                end = Offset(size.width, size.height / 2),
                strokeWidth = size.height,
                cap = StrokeCap.Round,
                pathEffect = if (dotted) PathEffect.dashPathEffect(floatArrayOf(0.1f, size.height * 2f)) else null
            )
        }
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextPrimary)
    }
}

/** Map legend for risk patches: loading, how many were found, none, or — tappable — that the lookup failed. */
@Composable
private fun PatchLegendChip(state: RideState, routes: com.rakshika.app.ride.RoutePair, onRetry: () -> Unit) {
    val safe = state.patchesFor(routes.safe)
    val fast = state.patchesFor(routes.fast)
    val count = safe.orEmpty().size + fast.orEmpty().size
    val failed = !state.patchesLoading && state.riskPatches.isNotEmpty() && state.riskPatches.values.all { it == null }
    val text = when {
        state.patchesLoading -> "Checking risk…"
        failed -> "Risk data unavailable · Retry"
        safe == null && fast == null -> return
        count == 0 -> "No risk patches"
        else -> "Risk patch ($count)"
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(SurfaceCard)
            .clickable(enabled = failed, onClick = onRetry)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (state.patchesLoading) {
            CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.2.dp, color = RouteRed)
        } else {
            Canvas(Modifier.size(11.dp)) {
                drawCircle(RouteRed.copy(alpha = 0.16f))
                drawCircle(
                    RouteRed,
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f)))
                )
            }
        }
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = TextPrimary)
    }
}

@Composable
private fun SheetHandle() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(100.dp)).background(BorderHairline))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RouteCard(
    state: RideState,
    route: RouteOption,
    isSafe: Boolean,
    counterpart: RouteOption,
    selected: Boolean,
    onClick: () -> Unit
) {
    val verdict = verdictOf(route.safetyScore)
    val color = roleColor(isSafe)
    val bg = roleBg(isSafe)
    val patches = state.patchesFor(route).orEmpty()
    val distance = formatDistance(pathLengthMeters(route.resolvedGeoPath()))
    val walkable = state.isWalkable(route)
    // The three inputs that moved this route's score the most, as short tags — real equation terms, not canned copy.
    val tags = state.assessment?.contributionsFor(route.corridor.key).orEmpty().take(3)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) bg.copy(alpha = 0.55f) else SurfaceCard)
            .border(
                if (selected) 1.5.dp else 0.5.dp,
                if (selected) color else BorderHairline,
                RoundedCornerShape(16.dp)
            )
            .clickable(enabled = walkable, onClick = onClick)
            .alpha(if (walkable) 1f else 0.5f)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ScoreRing(
            route.safetyScore, color, bg, dashed = !isSafe,
            size = 50.dp, stroke = 4.dp, caption = verdict.short, numberSize = 16.sp
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    titleFor(route, isSafe, counterpart),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(Modifier.width(6.dp))
                if (route.recommended) {
                    Pill("RECOMMENDED", RouteGreen, RouteGreenBg, filled = true)
                } else if (route.minutes < counterpart.minutes) {
                    Text("−${counterpart.minutes - route.minutes} min", style = MaterialTheme.typography.labelSmall, color = RouteRed)
                }
                if (route.scoredByModel) {
                    Spacer(Modifier.width(6.dp))
                    Pill("AI", PinkInk, PinkInkBg)
                }
            }
            if (!walkable) {
                Text(
                    "Google found no real road route here, so this option can't be navigated.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                return@Column
            }
            Text(
                "${route.minutes} min · $distance · via ${route.via ?: route.label}",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1
            )
            if (tags.isNotEmpty() || patches.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    patches.groupBy { it.kind }.forEach { (kind, list) ->
                        Pill("⚠ ${kind.label} ${formatDistance(list.sumOf { it.lengthMeters })}", RouteRed, RouteRedBg)
                    }
                    tags.forEach { c ->
                        val good = c.points >= 0
                        Pill(
                            (if (good) "" else "⚠ ") + FeatureCatalog.label(c.term.feature) + " " +
                                FeatureCatalog.format(c.term.feature, c.value),
                            if (good) RouteGreen else RouteRed,
                            if (good) RouteGreenBg else RouteRedBg
                        )
                    }
                }
            } else if (route.reasons.isNotEmpty()) {
                Text(route.reasons.first(), style = MaterialTheme.typography.labelSmall, color = TextSecondary, maxLines = 2)
            }
        }
    }
}

/** Shows whether the plotted route is live real road geometry or the offline mock-map fallback. */
@Composable
private fun RouteSourceBadge(real: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(100.dp))
            .background(SurfaceCard)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (real) RosePink else RoseMid))
        Spacer(Modifier.width(5.dp))
        Text(
            if (real) "Live roads" else "Simulated route",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
    }
}

/* ---------------- Riding ---------------- */

/** A real fix further than this from the chosen route counts as off-route. */
private const val OFF_ROUTE_M = 60.0

/** Who gets told about this ride: the demo's scripted pair, or the real saved emergency contacts. */
internal fun notifiedNames(state: RideState): String = when {
    !state.realMotion -> "Amma and Rohan"
    state.alertContacts.isEmpty() -> "no contacts"
    state.alertContacts.size == 1 -> state.alertContacts[0]
    else -> state.alertContacts.dropLast(1).joinToString(", ") + " and " + state.alertContacts.last()
}

internal fun originName(state: RideState): String = if (state.realMotion) "Your location" else ORIGIN.name

@Composable
private fun RidingStep(
    state: RideState,
    liveStatus: LiveShareStatus,
    onSos: () -> Unit,
    onReroute: () -> Unit,
    onFakeCall: (() -> Unit)?,
    onEnd: () -> Unit
) {
    val destination = state.destination ?: return

    val chosen = if (state.safeSelected) state.routes?.safe else state.routes?.fast
    val alt = if (state.safeSelected) state.routes?.fast else state.routes?.safe
    // Safe route is always solid green, the unsafe one always dotted red — on the map and everywhere else.
    val color = roleColor(state.safeSelected)
    val altColor = roleColor(!state.safeSelected)

    // Real routed road geometry when the destination was geocoded, else the fixed mock-map stand-in.
    val pathGeo = chosen?.resolvedGeoPath() ?: LiveShareConfig.toGeoPath(ROUTE_B)
    val altPathGeo = alt?.resolvedGeoPath() ?: LiveShareConfig.toGeoPath(ROUTE_A)
    val totalMeters = pathLengthMeters(pathGeo)
    // The real app shows where the phone actually is; the demo reads the dot off the route.
    val currentGeo = state.liveFix ?: geoPointAt(pathGeo, state.rideProgress)
    val offRoute = (state.offRouteMeters ?: 0.0) > OFF_ROUTE_M
    // Navigation should stay on screen — and a real ride keeps getting GPS fixes while it does.
    val hostView = LocalView.current
    DisposableEffect(hostView) {
        hostView.keepScreenOn = true
        onDispose { hostView.keepScreenOn = false }
    }
    val remainingMeters = totalMeters * (1 - state.rideProgress)
    var cautionDismissed by rememberSaveable { mutableStateOf(false) }
    val patches = chosen?.let { state.patchesFor(it) }.orEmpty()
    val altPatches = alt?.let { state.patchesFor(it) }.orEmpty()
    // The next OSM-flagged stretch within 300 m ahead (or the one she's in) — dismissed ones stay quiet.
    var dismissedPatches by remember { mutableStateOf(setOf<Double>()) }
    val walked = totalMeters * state.rideProgress
    val nextPatch = patches.firstOrNull {
        it.atMeters + it.lengthMeters / 2 >= walked && it.atMeters - it.lengthMeters / 2 - walked <= 300 &&
            it.atMeters !in dismissedPatches
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            // The alternate (not taken) route stays visible, thin and muted, for context.
            RealMap(
                modifier = Modifier.fillMaxSize(),
                primaryRoute = pathGeo,
                primaryColor = color,
                primaryWidth = 12f,
                secondaryRoute = altPathGeo,
                secondaryColor = altColor,
                secondaryWidth = 6f,
                primaryDotted = !state.safeSelected,
                secondaryDotted = state.safeSelected,
                current = currentGeo,
                zoomToCurrentOnStart = true,
                // The other route's spots stay visible too, so she can see what she's avoiding.
                patches = (patches + altPatches).toMapPatches(),
                topInsetDp = 120,
                bottomInsetDp = 40,
                followCurrent = state.realMotion
            )

            Column(
                Modifier.align(Alignment.TopCenter).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                NavBanner(
                    distance = formatDistance(remainingMeters),
                    destination = destination.name,
                    detail = when {
                        state.realMotion && state.liveFix == null -> "Waiting for GPS…"
                        offRoute -> "About ${formatDistance(state.offRouteMeters ?: 0.0)} off the route — tap Reroute"
                        chosen == null -> "Sharing live with your contacts"
                        state.safeSelected -> "Safest route · via ${chosen.via ?: chosen.label}"
                        else -> "Lower-scoring route · via ${chosen.via ?: chosen.label}"
                    },
                    color = color
                )

                if (state.rerouting) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceCard)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = RouteGreen)
                        Spacer(Modifier.width(8.dp))
                        Text("Recalculating route from here…", style = MaterialTheme.typography.labelSmall)
                    }
                }

                if (nextPatch != null) {
                    val ahead = (nextPatch.atMeters - nextPatch.lengthMeters / 2 - walked).coerceAtLeast(0.0)
                    CautionCard(
                        title = if (ahead <= 0.0) "You're in a caution zone · ${nextPatch.kind.label}"
                        else "Caution zone ${formatDistance(ahead)} ahead · ${nextPatch.kind.label}",
                        body = nextPatch.detail,
                        onDismiss = { dismissedPatches = dismissedPatches + nextPatch.atMeters }
                    )
                } else if (!state.safeSelected && !cautionDismissed && chosen != null && alt != null) {
                    // Only when she's on the route the score itself flagged as riskier.
                    CautionCard(
                        title = "You're on the lower-scoring route",
                        body = "The ${chosen.label.lowercase()} scores ${chosen.safetyScore}/100 vs ${alt.safetyScore} " +
                            "for the ${alt.label.lowercase()}. ${chosen.reasons.firstOrNull().orEmpty()}".trim(),
                        onDismiss = { cautionDismissed = true }
                    )
                }

                if (state.sosActive) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(RakshikaRedBg)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = SosPink, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("SOS sent — ${notifiedNames(state)} notified", style = MaterialTheme.typography.labelSmall, color = SosPink)
                    }
                }
            }

            RouteSourceBadge(
                real = chosen?.geoPath != null,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(SurfaceCard)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SheetHandle()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${state.etaMinutesLeft} min", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            " · ${formatDistance(remainingMeters)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }
                    Text(
                        "Arrive ${clockTime(System.currentTimeMillis() + state.etaMinutesLeft * 60_000L)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
                chosen?.let {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(100.dp))
                            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(100.dp))
                            .background(roleBg(state.safeSelected))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Shield, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Safety ${it.safetyScore}", style = MaterialTheme.typography.labelSmall, color = color)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .border(0.5.dp, BorderHairline, CircleShape)
                        .clickable(onClick = onEnd),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "End ride", tint = TextSecondary, modifier = Modifier.size(16.dp))
                }
            }

            LiveShareRow(state, liveStatus)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onFakeCall != null) {
                    ActionTile("Fake call", Icons.Filled.Call, Modifier.weight(1f), onClick = onFakeCall)
                }
                ActionTile("Reroute", Icons.Filled.Autorenew, Modifier.weight(1f), enabled = !state.rerouting, onClick = onReroute)
                SosTile(onTriggered = onSos, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NavBanner(distance: String, destination: String, detail: String, color: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(color)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Navigation, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(distance, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
            Text("to $destination", color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Text(detail, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun CautionCard(title: String, body: String, onDismiss: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceCard)
            .border(1.5.dp, RouteRed, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row {
            Box(
                Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(RouteRedBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = RouteRed, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = RouteRed)
                Text(body, style = MaterialTheme.typography.labelSmall, color = TextPrimary, maxLines = 3)
            }
        }
        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().height(36.dp),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            Text("Got it", style = MaterialTheme.typography.labelMedium, color = TextPrimary)
        }
    }
}

@Composable
private fun LiveShareRow(state: RideState, liveStatus: LiveShareStatus) {
    val dotColor = when (liveStatus) {
        LiveShareStatus.LIVE -> RosePink
        LiveShareStatus.CONNECTING -> RoseMid
        LiveShareStatus.ERROR -> SosPink
        LiveShareStatus.OFF -> TextSecondary
    }
    val statusText = when (liveStatus) {
        LiveShareStatus.LIVE -> "Live location shared"
        LiveShareStatus.CONNECTING -> "Connecting live share…"
        LiveShareStatus.ERROR -> "Live share unreachable"
        LiveShareStatus.OFF -> "Sharing live (local)"
    }
    // The demo scripts two contacts' read receipts; the real app lists who it actually texted.
    val contacts: List<Pair<String, String>> = if (state.realMotion) {
        state.alertContacts.map { it to "texted" }
    } else {
        listOfNotNull(
            state.contactAmma?.let { "Amma" to contactStatusText(it) },
            state.contactRohan?.let { "Rohan" to contactStatusText(it) }
        )
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfacePage)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
            contacts.take(3).forEachIndexed { i, (name, _) ->
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(SurfaceCard)
                        .padding(1.5.dp)
                        .clip(CircleShape)
                        .background(if (i == 0) PinkInk else RosePink),
                    contentAlignment = Alignment.Center
                ) {
                    Text(name.take(1), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(statusText, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (contacts.isEmpty()) "No emergency contacts to alert — add them in Contacts"
                else contacts.joinToString(" · ") { (name, status) -> "$name $status" },
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1
            )
        }
        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
    }
}

private fun contactStatusText(status: ContactStatus) = when (status) {
    ContactStatus.NOTIFIED -> "notified"
    ContactStatus.SEEN -> "seen"
    ContactStatus.RESPONDING -> "responding"
}

@Composable
private fun ActionTile(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Column(
        modifier
            .height(72.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(0.5.dp, BorderHairline, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) TextPrimary else TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (enabled) TextPrimary else TextSecondary)
    }
}

/** Hold-to-trigger SOS: a dark fill sweeps up the tile over 2 seconds; letting go early cancels it. */
@Composable
private fun SosTile(onTriggered: () -> Unit, modifier: Modifier = Modifier, hint: String = "Hold 2 sec") {
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
        modifier
            .height(72.dp)
            .shadow(8.dp, RoundedCornerShape(14.dp), spotColor = SosPink)
            .clip(RoundedCornerShape(14.dp))
            .background(SosPink)
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
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(pressProgress)
                .background(RakshikaRedDark)
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SOS", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp)
            Text(hint, color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp)
        }
    }
}

/* ---------------- Arrived ---------------- */

@Composable
private fun ArrivedStep(state: RideState, onNewRide: () -> Unit, onSubmitFeedback: (Int, Map<String, Int>) -> Unit) {
    val destination = state.destination
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(72.dp).clip(CircleShape).background(RouteGreenBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = RouteGreen, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("You've arrived safely", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        if (destination != null) {
            Text("at ${destination.name} · ${destination.area}", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (state.realMotion && state.alertContacts.isEmpty()) "No emergency contacts were set up to notify."
            else "${notifiedNames(state)} ${if (!state.realMotion || state.alertContacts.size > 1) "were" else "was"} notified automatically.",
            style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Spacer(Modifier.height(20.dp))
        // Only trips that were assessed on live route data can be rated and learned from. The AI
        // analysis has been running in the background since the ride started; the rating form waits
        // for it (it is built from the equation the AI settled on) — normally it's long done by now.
        state.assessment?.let { assessment ->
            val chosenKey = assessment.chosenCorridor
            if (chosenKey != null) {
                AiAnalysisPanel(assessment, chosenKey, state.accuracy, aiStateOf(assessment, state.aiAnalyzing, tripOver = true))
                Spacer(Modifier.height(12.dp))
            }
            if (state.aiAnalyzing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = RakshikaRed)
                    Spacer(Modifier.width(8.dp))
                    Text("Finishing the AI analysis of your route…", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
            } else {
                TripFeedbackSection(assessment, state.feedbackSubmitting, onSubmitFeedback)
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onNewRide, colors = ButtonDefaults.buttonColors(containerColor = RakshikaRed)) {
            Text("Plan another ride")
        }
    }
}
