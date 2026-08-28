package com.rakshika.app.ui.screens.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.rakshika.app.data.model.AlertEvent
import com.rakshika.app.data.model.EventType
import com.rakshika.app.ui.theme.*

@Composable
fun TimelineScreen(events: List<AlertEvent>) {
    Column(modifier = Modifier.fillMaxSize().background(SurfacePage)) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Activity", style = MaterialTheme.typography.titleLarge)
            Text(
                "A log of alerts, check-ins, and shared locations.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }

        if (events.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No activity yet", color = TextSecondary)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(events, key = { it.id }) { event ->
                    TimelineRow(event)
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

@Composable
private fun TimelineRow(event: AlertEvent) {
    val (icon, tint, bg, title) = eventVisuals(event.type)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceCard)
            .border(0.5.dp, BorderHairline, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(bg),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (event.note.isNotBlank()) {
                Text(event.note, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
        }
        Text(event.formattedTime(), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

private data class EventVisual(val icon: ImageVector, val tint: Color, val bg: Color, val title: String)

private fun eventVisuals(type: EventType): EventVisual = when (type) {
    EventType.SOS_TRIGGERED -> EventVisual(Icons.Filled.Warning, RakshikaRedDark, RakshikaRedBg, "SOS triggered")
    EventType.CHECK_IN_STARTED -> EventVisual(Icons.Filled.Timer, RakshikaAmber, RakshikaAmberBg, "Check-in started")
    EventType.CHECK_IN_CANCELLED -> EventVisual(Icons.Filled.Check, RakshikaGreen, RakshikaGreenBg, "Checked in safe")
    EventType.CHECK_IN_MISSED -> EventVisual(Icons.Filled.PriorityHigh, RakshikaRedDark, RakshikaRedBg, "Check-in missed")
    EventType.LOCATION_SHARED -> EventVisual(Icons.Filled.Share, RakshikaGreen, RakshikaGreenBg, "Location shared")
}
