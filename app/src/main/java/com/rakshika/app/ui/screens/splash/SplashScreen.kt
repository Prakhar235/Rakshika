package com.rakshika.app.ui.screens.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rakshika.app.ui.theme.RakshikaGreen
import com.rakshika.app.ui.theme.RakshikaRed
import com.rakshika.app.ui.theme.SurfacePage
import com.rakshika.app.ui.theme.TextPrimary
import com.rakshika.app.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sqrt

// Map palette — a muted, light "city map" so the coloured route and pin are what pop.
private val RoadColor = Color(0xFFFFFFFF)
private val BlockA = Color(0xFFEDE9DF)
private val BlockB = Color(0xFFE5E0D3)
private val BlockInner = Color(0x40FFFFFF)
private val ParkColor = Color(0xFFD3E8C9)
private val ParkTree = Color(0xFFBBDBAD)
private val WaterBank = Color(0xFFDCEBF5)
private val WaterColor = Color(0xFFBEDCF0)
private val ArterialCasing = Color(0xFFF0C46C)
private val ArterialFill = Color(0xFFFFE6A8)

// Block sizes in units of (width / 6), cycled; irregular so the grid reads as a real street plan.
private val ColPattern = floatArrayOf(1.0f, 1.4f, 0.8f, 1.2f, 1.0f, 1.5f, 0.9f, 1.3f)
private val RowPattern = floatArrayOf(1.1f, 0.9f, 1.5f, 1.0f, 1.3f, 0.8f, 1.2f, 1.4f, 1.0f)
private const val MAP_TILT_DEGREES = -14f

/**
 * Launch splash: a stylised city map with a safe route (green) drawing itself past a faster
 * one (red, dashed) to a dropping destination pin, then the app name. Drawn entirely in
 * Compose — no image assets or network — and calls [onFinished] once the sequence has played.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val route = remember { Animatable(0f) }
    val pin = remember { Animatable(0f) }
    val title = remember { Animatable(0f) }
    val currentOnFinished by rememberUpdatedState(onFinished)

    LaunchedEffect(Unit) {
        launch {
            delay(500)
            title.animateTo(1f, tween(700))
        }
        launch {
            delay(1050)
            pin.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow))
        }
        route.animateTo(1f, tween(1400, easing = FastOutSlowInEasing))
        delay(1000)
        currentOnFinished()
    }

    val pulseTransition = rememberInfiniteTransition(label = "pulse")
    val pulse by pulseTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse"
    )

    Box(Modifier.fillMaxSize().background(SurfacePage)) {
        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val w = size.width.coerceAtLeast(1f)
                    val h = size.height.coerceAtLeast(1f)

                    // The map never changes, so paint it once into a bitmap and blit it per frame.
                    val mapBitmap = ImageBitmap(w.toInt(), h.toInt())
                    CanvasDrawScope().draw(this, layoutDirection, Canvas(mapBitmap), Size(w, h)) {
                        drawBaseMap()
                    }

                    val safeRoute = RouteGeometry(Path().apply {
                        moveTo(0.20f * w, 0.50f * h)
                        cubicTo(0.20f * w, 0.40f * h, 0.48f * w, 0.42f * h, 0.48f * w, 0.32f * h)
                        cubicTo(0.48f * w, 0.22f * h, 0.70f * w, 0.28f * h, 0.70f * w, 0.17f * h)
                    })
                    val fastRoute = RouteGeometry(Path().apply {
                        moveTo(0.20f * w, 0.50f * h)
                        cubicTo(0.05f * w, 0.36f * h, 0.20f * w, 0.20f * h, 0.42f * w, 0.16f * h)
                        cubicTo(0.55f * w, 0.13f * h, 0.62f * w, 0.14f * h, 0.70f * w, 0.17f * h)
                    })
                    val start = Offset(0.20f * w, 0.50f * h)
                    val destination = Offset(0.70f * w, 0.17f * h)

                    val pinRadius = 0.048f * w
                    val pinPath = pinPath(pinRadius)
                    val dash = PathEffect.dashPathEffect(floatArrayOf(0.028f * w, 0.024f * w), 0f)

                    onDrawBehind {
                        drawImage(mapBitmap)

                        // Faster route: thin red dashes, drawn a touch quicker than the safe one.
                        val fastProgress = (route.value * 1.25f).coerceAtMost(1f)
                        if (fastProgress > 0f) {
                            drawPath(
                                fastRoute.partial(fastProgress),
                                color = RakshikaRed.copy(alpha = 0.75f),
                                style = Stroke(0.010f * w, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = dash)
                            )
                        }

                        // Safe route: green with a white casing so it lifts off the map.
                        if (route.value > 0f) {
                            val segment = safeRoute.partial(route.value)
                            drawPath(segment, Color.White, style = Stroke(0.030f * w, cap = StrokeCap.Round, join = StrokeJoin.Round))
                            drawPath(segment, RakshikaGreen, style = Stroke(0.018f * w, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }

                        // Start marker pops in as the route begins.
                        val startScale = (route.value * 8f).coerceAtMost(1f)
                        if (startScale > 0f) {
                            drawCircle(Color.White, 0.030f * w * startScale, start)
                            drawCircle(RakshikaGreen, 0.030f * w * startScale, start, style = Stroke(0.012f * w))
                        }

                        val landed = ((pin.value - 0.5f) * 2f).coerceIn(0f, 1f)
                        if (landed > 0f) {
                            // "Safe zone" ripples on the ground around the destination.
                            for (phase in floatArrayOf(pulse, (pulse + 0.5f) % 1f)) {
                                val rx = (0.03f + 0.17f * phase) * w
                                val alpha = (1f - phase) * 0.35f * landed
                                drawOval(
                                    RakshikaGreen.copy(alpha = alpha),
                                    topLeft = Offset(destination.x - rx, destination.y - rx * 0.45f),
                                    size = Size(rx * 2f, rx * 0.9f),
                                    style = Stroke(0.006f * w)
                                )
                            }
                        }

                        if (pin.value > 0f) {
                            val drop = (1f - pin.value) * (-0.22f * h)
                            val alpha = (pin.value * 3f).coerceIn(0f, 1f)
                            drawOval(
                                Color.Black.copy(alpha = 0.18f * alpha),
                                topLeft = Offset(destination.x - pinRadius * 0.6f, destination.y - pinRadius * 0.2f),
                                size = Size(pinRadius * 1.2f, pinRadius * 0.4f)
                            )
                            translate(destination.x, destination.y + drop) {
                                drawPath(pinPath, RakshikaRed.copy(alpha = alpha))
                                drawCircle(
                                    Color.White.copy(alpha = alpha),
                                    pinRadius * 0.42f,
                                    Offset(0f, -pinRadius * PIN_HEAD_OFFSET)
                                )
                            }
                        }
                    }
                }
        )

        // Fade the map out toward the bottom so the name sits on a clean surface.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.50f to Color.Transparent,
                        0.66f to SurfacePage.copy(alpha = 0.94f),
                        1.00f to SurfacePage
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 96.dp)
                .graphicsLayer {
                    alpha = title.value
                    translationY = (1f - title.value) * 24.dp.toPx()
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Safe Maps",
                color = TextPrimary,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            )
            Spacer(Modifier.height(6.dp))
            Text("Find the safer way home", color = TextSecondary, fontSize = 15.sp)
        }
    }
}

/** A route path plus the measuring state needed to reveal it progressively. */
private class RouteGeometry(path: Path) {
    private val measure = PathMeasure().apply { setPath(path, false) }
    private val length = measure.length
    private val segment = Path()

    /** The first [fraction] (0..1) of the route; the returned Path is reused between calls. */
    fun partial(fraction: Float): Path {
        segment.reset()
        measure.getSegment(0f, length * fraction, segment, true)
        return segment
    }
}

/** Head centre sits this many radii above the tip. */
private const val PIN_HEAD_OFFSET = 2.35f

/** Teardrop pin with its tip at the origin and the round head above it. */
private fun pinPath(radius: Float): Path {
    val d = PIN_HEAD_OFFSET * radius
    // Tangent points from the tip (origin) to the head circle centred at (0, -d).
    val tx = radius * sqrt(1f - (radius / d) * (radius / d))
    val ty = -d + radius * radius / d
    return Path().apply {
        // Counter-clockwise, like addOval's default, so the two overlap as a union.
        moveTo(0f, 0f)
        lineTo(tx, ty)
        lineTo(-tx, ty)
        close()
        addOval(Rect(Offset(0f, -d), radius))
    }
}

private fun gridLines(start: Float, end: Float, unit: Float, pattern: FloatArray): List<Float> {
    val lines = ArrayList<Float>()
    var pos = start
    var i = 0
    lines += pos
    while (pos < end) {
        pos += pattern[i % pattern.size] * unit
        lines += pos
        i++
    }
    return lines
}

/** Blocks, parks, river and arterial roads. The grid is tilted; the river is not. */
private fun DrawScope.drawBaseMap() {
    val w = size.width
    val h = size.height
    val pivot = Offset(w / 2f, h / 2f)
    val xs = gridLines(-0.7f * w, 1.7f * w, w / 6f, ColPattern)
    val ys = gridLines(-0.5f * h, 1.5f * h, w / 6f, RowPattern)
    val roadWidth = 0.034f * w

    drawRect(RoadColor)

    rotate(MAP_TILT_DEGREES, pivot) {
        val corner = CornerRadius(0.008f * w)
        for (i in 0 until xs.size - 1) {
            for (j in 0 until ys.size - 1) {
                val topLeft = Offset(xs[i] + roadWidth / 2f, ys[j] + roadWidth / 2f)
                val blockSize = Size(xs[i + 1] - xs[i] - roadWidth, ys[j + 1] - ys[j] - roadWidth)
                val kind = (((i * 73856093) xor (j * 19349663)) ushr 4) % 9
                val color = when (kind) {
                    0 -> ParkColor
                    1, 2, 3 -> BlockB
                    else -> BlockA
                }
                drawRoundRect(color, topLeft, blockSize, corner)
                if (kind == 0) {
                    val centre = Offset(topLeft.x + blockSize.width / 2f, topLeft.y + blockSize.height / 2f)
                    drawCircle(ParkTree, minOf(blockSize.width, blockSize.height) * 0.22f, centre)
                } else {
                    val inset = 0.14f
                    drawRoundRect(
                        BlockInner,
                        Offset(topLeft.x + blockSize.width * inset, topLeft.y + blockSize.height * inset),
                        Size(blockSize.width * (1f - 2f * inset), blockSize.height * (1f - 2f * inset)),
                        corner
                    )
                }
            }
        }
    }

    val river = Path().apply {
        moveTo(-0.1f * w, 0.31f * h)
        cubicTo(0.30f * w, 0.22f * h, 0.55f * w, 0.41f * h, 1.1f * w, 0.30f * h)
    }
    drawPath(river, WaterBank, style = Stroke(0.11f * w, cap = StrokeCap.Round))
    drawPath(river, WaterColor, style = Stroke(0.085f * w, cap = StrokeCap.Round))

    // Arterials go on after the river so they cross it like bridges.
    rotate(MAP_TILT_DEGREES, pivot) {
        for (i in xs.indices) {
            if (i % 4 != 2) continue
            drawLine(ArterialCasing, Offset(xs[i], ys.first()), Offset(xs[i], ys.last()), roadWidth * 1.15f)
            drawLine(ArterialFill, Offset(xs[i], ys.first()), Offset(xs[i], ys.last()), roadWidth * 0.8f)
        }
        for (j in ys.indices) {
            if (j % 5 != 3) continue
            drawLine(ArterialCasing, Offset(xs.first(), ys[j]), Offset(xs.last(), ys[j]), roadWidth * 1.15f)
            drawLine(ArterialFill, Offset(xs.first(), ys[j]), Offset(xs.last(), ys[j]), roadWidth * 0.8f)
        }
    }
}
