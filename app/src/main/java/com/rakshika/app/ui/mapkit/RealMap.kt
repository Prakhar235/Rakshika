package com.rakshika.app.ui.mapkit

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
    current: DoubleArray? = null,
    /** On first render, zoom in close on [current] instead of fitting the whole route — used once the ride starts. */
    zoomToCurrentOnStart: Boolean = false
) {
    val mapView = rememberMapViewWithLifecycle()
    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }
    var overlays by remember { mutableStateOf<MapOverlays?>(null) }
    val fitted = remember { booleanArrayOf(false) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = {
            mapView.getMapAsync { map ->
                map.uiSettings.isZoomControlsEnabled = true
                overlays = MapOverlays(
                    primary = map.addPolyline(PolylineOptions().geodesic(true)),
                    secondary = map.addPolyline(PolylineOptions().geodesic(true)),
                    start = map.addMarker(MarkerOptions().position(LatLng(0.0, 0.0)).title("Start").visible(false))!!,
                    end = map.addMarker(MarkerOptions().position(LatLng(0.0, 0.0)).title("Destination").visible(false))!!,
                    current = map.addMarker(MarkerOptions().position(LatLng(0.0, 0.0)).title("You").visible(false))!!
                )
                googleMap = map
            }
            mapView
        },
        update = { mv ->
            val map = googleMap ?: return@AndroidView
            val ov = overlays ?: return@AndroidView

            ov.primary.color = primaryColor.toArgb()
            ov.primary.width = primaryWidth
            ov.secondary.color = secondaryColor.toArgb()
            ov.secondary.width = secondaryWidth

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
                mv.onceLaidOut { map.moveCamera(CameraUpdateFactory.newLatLngZoom(point, 18f)) }
                fitted[0] = true
            } else if (!fitted[0] && !fitPts.isNullOrEmpty()) {
                val boundsBuilder = LatLngBounds.Builder()
                fitPts.forEach { boundsBuilder.include(it) }
                val bounds = boundsBuilder.build()
                mv.onceLaidOut {
                    runCatching { map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100)) }
                }
                fitted[0] = true
            }

            if (current != null) {
                ov.current.position = LatLng(current[0], current[1])
                ov.current.isVisible = true
            } else {
                ov.current.isVisible = false
            }
        }
    )
}
