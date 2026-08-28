package com.rakshika.app.ui.screens.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.rakshika.app.ui.theme.*

@Composable
fun MapScreen(onSos: () -> Unit, onShareLocation: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfacePage)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            MockMapCanvas()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceCard)
                        .border(0.5.dp, BorderHairline, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Navigation, contentDescription = null, tint = RakshikaGreen, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Sharing live", style = MaterialTheme.typography.labelSmall)
                }

                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(SurfaceCard)
                        .border(0.5.dp, BorderHairline, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.MyLocation, contentDescription = "Recenter", modifier = Modifier.size(16.dp))
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceCard)
                    .border(0.5.dp, BorderHairline, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text("Updated just now", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
        }

        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("MG Road, Sector 14", style = MaterialTheme.typography.titleMedium)
                    Text("Accuracy 8m · GPS", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(RakshikaGreenBg)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("Safe zone", style = MaterialTheme.typography.labelSmall, color = RakshikaGreen)
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onShareLocation, modifier = Modifier.weight(1f)) {
                    Text("Share link")
                }
                Button(
                    onClick = onSos,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = RakshikaRed)
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("SOS")
                }
            }
        }
    }
}

@Composable
private fun MockMapCanvas() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(color = Color(0xFFF1EFE8))

        val roadColor = Color(0xFFDAD7CC)
        drawLine(roadColor, Offset(0f, size.height * 0.18f), Offset(size.width, size.height * 0.15f), strokeWidth = 10f)
        drawLine(roadColor, Offset(0f, size.height * 0.45f), Offset(size.width, size.height * 0.42f), strokeWidth = 10f)
        drawLine(roadColor, Offset(0f, size.height * 0.75f), Offset(size.width, size.height * 0.8f), strokeWidth = 10f)
        drawLine(roadColor, Offset(size.width * 0.2f, 0f), Offset(size.width * 0.18f, size.height), strokeWidth = 10f)
        drawLine(roadColor, Offset(size.width * 0.6f, 0f), Offset(size.width * 0.62f, size.height), strokeWidth = 10f)
        drawLine(roadColor, Offset(size.width * 0.85f, 0f), Offset(size.width * 0.87f, size.height), strokeWidth = 10f)

        val routeStart = Offset(size.width * 0.2f, size.height * 0.42f)
        val routeEnd = Offset(size.width * 0.62f, size.height * 0.78f)
        drawLine(
            color = Color(0xFF378ADD),
            start = routeStart,
            end = routeEnd,
            strokeWidth = 6f,
            cap = StrokeCap.Round,
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 14f))
        )

        // Current location pin
        drawCircle(color = Color(0xFF378ADD).copy(alpha = 0.2f), radius = 26f, center = routeStart)
        drawCircle(color = Color(0xFF378ADD), radius = 12f, center = routeStart)
        drawCircle(color = Color.White, radius = 12f, center = routeStart, style = Stroke(width = 3f))

        // Destination marker
        drawCircle(color = Color(0xFFD8365E), radius = 10f, center = routeEnd)
    }
}
