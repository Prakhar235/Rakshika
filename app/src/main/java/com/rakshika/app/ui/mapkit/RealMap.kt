package com.rakshika.app.ui.mapkit

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Dot
import com.google.android.gms.maps.model.RoundCap
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions

/**
 * Runs [block] once this MapView actually has pixels — `newLatLngBounds`/a zoomed
 * `newLatLngZoom` camera update need real width/height or GoogleMap throws/no-ops. `View.post`
 * is used (not `viewTreeObserver`) because it's safe to call before the view is attached to a
 * window — it just queues the Runnable for once attachment/layout happens — whereas a
 * `ViewTreeObserver` fetched pre-attach can be a throwaway instance whose listeners never fire
 * once the real one takes over at attach time.
 */
private fun MapView.onceLaidOut(block: MapView.() -> Unit) {
    if (width > 0 && height > 0) {
        block()
    } else {
        post { onceLaidOut(block) }
    }
}

/**
 * Keeps a Google Maps [MapView]'s own lifecycle in step with the composition's — GoogleMap
 * needs onCreate/onStart/onResume/... called on it directly; it does not observe the host
 * Activity's lifecycle by itself. `Lifecycle.addObserver` brings a newly-added observer up to
 * the current state, so this still fires the right callbacks even when the composable enters
 * composition after the activity is already resumed (the normal case here, since the map is
 * only composed once the user navigates to the ride screen).
 */
@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return mapView
}

/** A risky stretch to outline on the map: a dashed circle with a small [label] pill at its centre. */
data class MapPatch(val center: DoubleArray, val radiusMeters: Double, val label: String, val color: Color)

/** Circles + label markers currently drawn for [patches], so they can be swapped when the list changes. */
private class PatchLayer(var patches: List<MapPatch> = emptyList()) {
    val circles = mutableListOf<Circle>()
    val labels = mutableListOf<Marker>()
    fun clear() {
        circles.forEach { it.remove() }; circles.clear()
        labels.forEach { it.remove() }; labels.clear()
    }
}

/** "You are here": a blue dot with a white ring and a soft halo, like other navigation apps. */
private fun youDotBitmap(density: Float): Bitmap {
    val size = (28 * density).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val c = size / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.argb(60, 26, 115, 232)
    canvas.drawCircle(c, c, c, paint)
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(c, c, 8 * density, paint)
    paint.color = android.graphics.Color.rgb(26, 115, 232)
    canvas.drawCircle(c, c, 6 * density, paint)
    return bmp
}

/** A rounded white pill with [text] in [color] — the label drawn over a risk patch. */
private fun labelBitmap(text: String, color: Int, density: Float): Bitmap {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11 * density
        this.color = color
        typeface = Typeface.DEFAULT_BOLD
    }
    val padH = 8 * density
    val padV = 4 * density
    val w = (paint.measureText(text) + padH * 2).toInt()
    val h = (paint.textSize + padV * 2).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.WHITE }
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    val rect = RectF(1f, 1f, w - 1f, h - 1f)
    canvas.drawRoundRect(rect, h / 2f, h / 2f, bg)
    canvas.drawRoundRect(rect, h / 2f, h / 2f, border)
    canvas.drawText(text, padH, h / 2f - (paint.descent() + paint.ascent()) / 2, paint)
    return bmp
}

/** The patch circle's north/south/east/west edge points, for camera fitting. */
private fun MapPatch.extent(): List<LatLng> {
    val dLat = radiusMeters / 110_540.0
    val dLng = radiusMeters / (111_320.0 * Math.cos(Math.toRadians(center[0])))
    return listOf(
        LatLng(center[0] + dLat, center[1]), LatLng(center[0] - dLat, center[1]),
        LatLng(center[0], center[1] + dLng), LatLng(center[0], center[1] - dLng)
    )
}

private fun boundsOf(points: List<LatLng>): LatLngBounds =
    LatLngBounds.Builder().apply { points.forEach { include(it) } }.build()

private class MapOverlays(
    val primary: Polyline,
    val secondary: Polyline,
    val start: Marker,
    val end: Marker,
    val current: Marker
)

/**
 * A real Google Maps view (Maps SDK for Android — requires MAPS_API_KEY, see local.properties)
 * showing one or two routes as colored polylines with start/end markers, plus an optional live
 * position dot.
 */
@Composable
fun RealMap(
    modifier: Modifier = Modifier,
    primaryRoute: List<DoubleArray>,
    primaryColor: Color,
    primaryWidth: Float = 12f,
    secondaryRoute: List<DoubleArray>? = null,
    secondaryColor: Color = Color(0xFF9C978A),
    secondaryWidth: Float = 7f,
    /** Draw that route as a dotted line — used for the unsafe route so it reads differently from the solid safe one. */
    primaryDotted: Boolean = false,
    secondaryDotted: Boolean = false,
    current: DoubleArray? = null,
    /** On first render, zoom in close on [current] instead of fitting the whole route — used once the ride starts. */
    zoomToCurrentOnStart: Boolean = false,
    patches: List<MapPatch> = emptyList(),
    /** Space (dp) taken by cards drawn over the map's top / bottom edge, so fitting keeps routes and patches clear of them. */
    topInsetDp: Int = 0,
    bottomInsetDp: Int = 0,
    /** Keep the camera centred on [current] as it moves — real navigation, where the rider is the point. */
    followCurrent: Boolean = false
) {
    val mapView = rememberMapViewWithLifecycle()
    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }
    var overlays by remember { mutableStateOf<MapOverlays?>(null) }
    val fitted = remember { booleanArrayOf(false) }
    val patchLayer = remember { PatchLayer() }
    val lastFollowed = remember { arrayOfNulls<LatLng>(1) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = {
            mapView.getMapAsync { map ->
                map.uiSettings.isZoomControlsEnabled = true
                overlays = MapOverlays(
                    primary = map.addPolyline(PolylineOptions().geodesic(true).startCap(RoundCap()).endCap(RoundCap()).jointType(com.google.android.gms.maps.model.JointType.ROUND)),
                    secondary = map.addPolyline(PolylineOptions().geodesic(true).startCap(RoundCap()).endCap(RoundCap()).jointType(com.google.android.gms.maps.model.JointType.ROUND)),
                    start = map.addMarker(MarkerOptions().position(LatLng(0.0, 0.0)).title("Start").visible(false))!!,
                    end = map.addMarker(MarkerOptions().position(LatLng(0.0, 0.0)).title("Destination").visible(false))!!,
                    current = map.addMarker(
                        MarkerOptions()
                            .position(LatLng(0.0, 0.0))
                            .title("You")
                            .anchor(0.5f, 0.5f)
                            .zIndex(3f)
                            .icon(BitmapDescriptorFactory.fromBitmap(youDotBitmap(it.resources.displayMetrics.density)))
                            .visible(false)
                    )!!
                )
                googleMap = map
            }
            mapView
        },
        update = { mv ->
            val map = googleMap ?: return@AndroidView
            val ov = overlays ?: return@AndroidView

            val density = mv.resources.displayMetrics.density
            map.setPadding(0, (topInsetDp * density).toInt(), 0, (bottomInsetDp * density).toInt())

            ov.primary.color = primaryColor.toArgb()
            ov.primary.width = primaryWidth
            ov.secondary.color = secondaryColor.toArgb()
            ov.secondary.width = secondaryWidth
            val dotted = listOf(Dot(), Gap(primaryWidth))
            ov.primary.pattern = if (primaryDotted) dotted else null
            ov.secondary.pattern = if (secondaryDotted) listOf(Dot(), Gap(secondaryWidth * 1.5f)) else null

            val hasSecondary = secondaryRoute != null && secondaryRoute.size >= 2
            var fitPts: List<LatLng>? = null

            if (primaryRoute.size >= 2) {
                val pts = primaryRoute.map { LatLng(it[0], it[1]) }
                ov.primary.points = pts
                ov.start.position = pts.first(); ov.start.isVisible = true
                ov.end.position = pts.last(); ov.end.isVisible = true
                fitPts = pts
            } else {
                ov.primary.points = emptyList()
                ov.start.isVisible = false
                ov.end.isVisible = false
            }

            if (hasSecondary) {
                val pts = secondaryRoute!!.map { LatLng(it[0], it[1]) }
                ov.secondary.points = pts
                fitPts = (fitPts ?: emptyList()) + pts
            } else {
                ov.secondary.points = emptyList()
            }

            if (!fitted[0] && zoomToCurrentOnStart && current != null) {
                val point = LatLng(current[0], current[1])
                mv.onceLaidOut { map.moveCamera(CameraUpdateFactory.newLatLngZoom(point, 16.5f)) }
                fitted[0] = true
            } else if (!fitted[0] && !fitPts.isNullOrEmpty()) {
                val bounds = boundsOf(fitPts + patches.flatMap { it.extent() })
                mv.onceLaidOut {
                    runCatching { map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, (16 * density).toInt())) }
                }
                fitted[0] = true
            }

            if (patchLayer.patches != patches) {
                // Patches arrive a few seconds after the routes — widen the overview so none sit off-screen.
                if (!zoomToCurrentOnStart && patches.isNotEmpty() && !fitPts.isNullOrEmpty()) {
                    val bounds = boundsOf(fitPts + patches.flatMap { it.extent() })
                    mv.onceLaidOut {
                        runCatching { map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, (16 * density).toInt())) }
                    }
                }
                patchLayer.clear()
                patches.forEach { p ->
                    val center = LatLng(p.center[0], p.center[1])
                    val argb = p.color.toArgb()
                    patchLayer.circles += map.addCircle(
                        CircleOptions()
                            .center(center)
                            .radius(p.radiusMeters)
                            .strokeColor(argb)
                            .strokeWidth(2.5f * density)
                            .strokePattern(listOf(Dash(10f * density), Gap(6f * density)))
                            .fillColor(p.color.copy(alpha = 0.16f).toArgb())
                    )
                    // The label hangs from the circle's bottom edge, clear of the route's start/end pins.
                    val south = LatLng(p.center[0] - p.radiusMeters / 110_540.0, p.center[1])
                    map.addMarker(
                        MarkerOptions()
                            .position(south)
                            .anchor(0.5f, 0.5f)
                            .zIndex(2f)
                            .title(p.label)
                            .icon(BitmapDescriptorFactory.fromBitmap(labelBitmap(p.label, argb, density)))
                    )?.let { patchLayer.labels += it }
                }
                patchLayer.patches = patches
            }

            if (current != null) {
                val here = LatLng(current[0], current[1])
                ov.current.position = here
                ov.current.isVisible = true
                if (followCurrent && fitted[0] && here != lastFollowed[0]) {
                    lastFollowed[0] = here
                    mv.onceLaidOut { map.animateCamera(CameraUpdateFactory.newLatLng(here)) }
                }
            } else {
                ov.current.isVisible = false
            }
        }
    )
}
