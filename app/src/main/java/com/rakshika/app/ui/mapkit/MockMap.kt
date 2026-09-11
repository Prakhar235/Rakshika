package com.rakshika.app.ui.mapkit

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.rakshika.app.rag.RouteCorridor

/**
 * A drawn (non-Google-Maps) mock street grid shared by the narrated demo and the
 * interactive ride screens, so both draw the same neighbourhood.
 */

internal data class RoadSeg(val x1: Float, val y1: Float, val x2: Float, val y2: Float)
internal data class Block(val x: Float, val y: Float, val w: Float, val h: Float)

internal val ROADS = listOf(
    RoadSeg(0f, .20f, 1f, .17f),
    RoadSeg(0f, .48f, 1f, .45f),
    RoadSeg(0f, .78f, 1f, .82f),
    RoadSeg(.18f, 0f, .16f, 1f),
    RoadSeg(.55f, 0f, .57f, 1f),
    RoadSeg(.85f, 0f, .87f, 1f)
)
internal val BLOCKS = listOf(
    Block(.24f, .24f, .20f, .14f),
    Block(.62f, .22f, .14f, .16f),
    Block(.24f, .55f, .18f, .14f),
    Block(.66f, .55f, .12f, .16f),
    Block(.24f, .86f, .20f, .10f),
    Block(.66f, .86f, .14f, .10f)
)

internal val SOS_PATH = listOf(
    Offset(.16f, .45f), Offset(.40f, .455f), Offset(.55f, .46f),
    Offset(.565f, .60f), Offset(.57f, .78f), Offset(.72f, .80f)
)
internal val ROUTE_A = listOf(
    Offset(.18f, .45f), Offset(.35f, .34f), Offset(.50f, .30f), Offset(.68f, .24f), Offset(.85f, .20f)
)
internal val ROUTE_B = listOf(
    Offset(.18f, .45f), Offset(.18f, .17f), Offset(.85f, .17f), Offset(.85f, .20f)
)

/** Which fixed mock-map polyline stands in for a corridor when no real route geometry is available. */
fun mockPathFor(corridor: RouteCorridor?): List<Offset> = if (corridor != RouteCorridor.BACK_LANE) ROUTE_B else ROUTE_A

internal fun DrawScope.drawRoadsAndBlocks() {
    val w = size.width
    val h = size.height
    ROADS.forEach { r ->
        drawLine(
            color = Color(0xFFDAD7CC),
            start = Offset(r.x1 * w, r.y1 * h),
            end = Offset(r.x2 * w, r.y2 * h),
            strokeWidth = 11f,
            cap = StrokeCap.Round
        )
    }
    BLOCKS.forEach { b ->
        drawRoundRect(
            color = Color(0xFFECE9DF),
            topLeft = Offset(b.x * w, b.y * h),
            size = Size(b.w * w, b.h * h),
            cornerRadius = CornerRadius(6f, 6f)
        )
    }
}

internal fun DrawScope.drawRoute(path: List<Offset>, color: Color, width: Float, dashed: Boolean) {
    val w = size.width
    val h = size.height
    val pts = path.map { Offset(it.x * w, it.y * h) }
    for (i in 1 until pts.size) {
        drawLine(
            color = color,
            start = pts[i - 1],
            end = pts[i],
            strokeWidth = width,
            cap = StrokeCap.Round,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 14f)) else null
        )
    }
}

internal fun DrawScope.drawMarker(p: Offset, color: Color, ring: Boolean = false) {
    val c = Offset(p.x * size.width, p.y * size.height)
    if (ring) drawCircle(color.copy(alpha = .2f), radius = 22f, center = c)
    drawCircle(color, radius = 9f, center = c)
    drawCircle(Color.White, radius = 9f, center = c, style = Stroke(width = 3f))
}

internal fun DrawScope.drawTravelDot(p: Offset, color: Color) {
    drawCircle(color.copy(alpha = .25f), radius = 20f, center = p)
    drawCircle(color, radius = 10f, center = p)
    drawCircle(Color.White, radius = 10f, center = p, style = Stroke(width = 3.5f))
}

internal fun pointAt(path: List<Offset>, w: Float, h: Float, t: Float): Offset {
    val pts = path.map { Offset(it.x * w, it.y * h) }
    val segLens = FloatArray(pts.size - 1)
    var total = 0f
    for (i in 1 until pts.size) {
        val d = (pts[i] - pts[i - 1]).getDistance()
        segLens[i - 1] = d
        total += d
    }
    var remaining = t.coerceIn(0f, 1f) * total
    for (i in segLens.indices) {
        val d = segLens[i]
        if (remaining <= d || i == segLens.size - 1) {
            val local = if (d == 0f) 0f else (remaining / d).coerceIn(0f, 1f)
            val a = pts[i]
            val b = pts[i + 1]
            return Offset(a.x + (b.x - a.x) * local, a.y + (b.y - a.y) * local)
        }
        remaining -= d
    }
    return pts.last()
}
