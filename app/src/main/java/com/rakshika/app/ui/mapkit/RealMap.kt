package com.rakshika.app.ui.mapkit

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

private var osmdroidInitialized = false
private fun initOsmdroid(context: Context) {
    if (osmdroidInitialized) return
    osmdroidInitialized = true
    val prefs = context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
    Configuration.getInstance().load(context.applicationContext, prefs)
    Configuration.getInstance().userAgentValue = context.packageName
}

/**
 * A real OpenStreetMap view (osmdroid — free, no API key) showing one or two routes
 * as colored polylines with start/end markers, plus an optional live position dot.
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
    val context = LocalContext.current
    LaunchedEffect(Unit) { initOsmdroid(context) }

    val mapView = remember(context) {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
        }
    }
    val primaryLine = remember { Polyline().apply { isGeodesic = true } }
    val secondaryLine = remember { Polyline().apply { isGeodesic = true } }
    val startMarker = remember {
        Marker(mapView).apply { setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); title = "Start" }
    }
    val endMarker = remember {
        Marker(mapView).apply { setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); title = "Destination" }
    }
    val currentMarker = remember {
        Marker(mapView).apply { setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); title = "You" }
    }
    val fitted = remember { booleanArrayOf(false) }

    DisposableEffect(Unit) { onDispose { mapView.onDetach() } }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = {
            mapView.overlays.add(secondaryLine)
            mapView.overlays.add(primaryLine)
            mapView.overlays.add(startMarker)
            mapView.overlays.add(endMarker)
            mapView.overlays.add(currentMarker)
            mapView
        },
        update = { mv ->
            primaryLine.outlinePaint.color = primaryColor.toArgb()
            primaryLine.outlinePaint.strokeWidth = primaryWidth
            secondaryLine.outlinePaint.color = secondaryColor.toArgb()
            secondaryLine.outlinePaint.strokeWidth = secondaryWidth

            val hasSecondary = secondaryRoute != null && secondaryRoute.size >= 2
            var fitPts: List<GeoPoint>? = null

            if (primaryRoute.size >= 2) {
                val pts = primaryRoute.map { GeoPoint(it[0], it[1]) }
                primaryLine.setPoints(pts)
                startMarker.position = pts.first()
                endMarker.position = pts.last()
                fitPts = pts
            } else {
                primaryLine.setPoints(emptyList())
            }

            if (hasSecondary) {
                val pts = secondaryRoute!!.map { GeoPoint(it[0], it[1]) }
                secondaryLine.setPoints(pts)
                fitPts = (fitPts ?: emptyList()) + pts
            } else {
                secondaryLine.setPoints(emptyList())
            }

            if (!fitted[0] && zoomToCurrentOnStart && current != null) {
                val point = GeoPoint(current[0], current[1])
                mv.post {
                    mv.controller.setZoom(18.0)
                    mv.controller.setCenter(point)
                }
                fitted[0] = true
            } else if (!fitted[0] && fitPts != null) {
                val box = BoundingBox.fromGeoPoints(fitPts).increaseByScale(1.5f)
                mv.post { mv.zoomToBoundingBox(box, false) }
                fitted[0] = true
            }

            if (current != null) {
                currentMarker.position = GeoPoint(current[0], current[1])
                currentMarker.isEnabled = true
            } else {
                currentMarker.isEnabled = false
            }
            mv.invalidate()
        }
    )
}
