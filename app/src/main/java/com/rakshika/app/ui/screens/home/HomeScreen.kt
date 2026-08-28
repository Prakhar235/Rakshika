package com.rakshika.app.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.rakshika.app.RakshikaUiState
import com.rakshika.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val HOLD_DURATION_MS = 2000L

@Composable
fun HomeScreen(
    state: RakshikaUiState,
    onToggleOnline: () -> Unit,
    onSosTriggered: () -> Unit,
    onStartCheckIn: (Int) -> Unit,
    onCancelCheckIn: () -> Unit,
    onShareLocation: () -> Unit,
    onFakeCall: () -> Unit,
    onOpenContacts: () -> Unit
) {
    var showCheckInPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfacePage)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(20.dp))

        StatusRow(isOnline = state.isOnline, onToggleOnline = onToggleOnline)

        Spacer(Modifier.height(28.dp))

        Text("Rakshika", style = MaterialTheme.typography.titleLarge)
        Text(
            "You're protected. Hold the button below in an emergency.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )

        Spacer(Modifier.height(28.dp))

        SosButton(
            isOnline = state.isOnline,
            onTriggered = onSosTriggered
        )

        AnimatedVisibility(visible = state.sosJustTriggered) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    if (state.isOnline) "Alert sent · contacts notified" else "Alert sent via SMS · offline mode",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RakshikaGreen
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        CheckInCard(
            active = state.checkInActive,
            secondsLeft = state.checkInSecondsLeft,
            totalSeconds = state.checkInTotalSeconds,
            onStart = { showCheckInPicker = true },
            onCancel = onCancelCheckIn
        )

        Spacer(Modifier.height(20.dp))

        Text("Quick actions", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))

        QuickActionsGrid(
            onFakeCall = onFakeCall,
            onShareLocation = onShareLocation,
            onOpenContacts = onOpenContacts
        )

        Spacer(Modifier.height(24.dp))
    }

    if (showCheckInPicker) {
        CheckInPickerDialog(
            onDismiss = { showCheckInPicker = false },
            onConfirm = { minutes ->
                onStartCheckIn(minutes)
                showCheckInPicker = false
            }
        )
    }
}

@Composable
private fun StatusRow(isOnline: Boolean, onToggleOnline: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusChip(
            icon = if (isOnline) Icons.Filled.Wifi else Icons.Filled.WifiOff,
            label = if (isOnline) "Online" else "Offline mode",
            color = if (isOnline) RakshikaGreen else RakshikaRedDark,
            bg = if (isOnline) RakshikaGreenBg else RakshikaRedBg
        )
        TextButton(onClick = onToggleOnline) {
            Text("Simulate ${if (isOnline) "offline" else "online"}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun StatusChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, bg: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = color, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SosButton(isOnline: Boolean, onTriggered: () -> Unit) {
    var pressProgress by remember { mutableFloatStateOf(0f) }
    var isPressed by remember { mutableStateOf(false) }

    LaunchedEffect(isPressed) {
        if (isPressed) {
            val stepMs = 16L
            val steps = HOLD_DURATION_MS / stepMs
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

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(180.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            val released = tryAwaitRelease()
                            isPressed = false
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 8.dp.toPx()
                drawCircle(
                    color = Color(0xFFF0997B).copy(alpha = 0.25f),
                    radius = size.minDimension / 2 - strokeWidth,
                    center = Offset(size.width / 2, size.height / 2)
                )
                if (pressProgress > 0f) {
                    drawArc(
                        color = RakshikaRedDark,
                        startAngle = -90f,
                        sweepAngle = 360f * pressProgress,
                        useCenter = false,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = strokeWidth,
                            cap = StrokeCap.Round
                        )
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(128.dp)
                    .clip(CircleShape)
                    .background(RakshikaRed),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = "SOS",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("SOS", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Hold for 2 seconds to trigger",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}

@Composable
private fun CheckInCard(
    active: Boolean,
    secondsLeft: Int,
    totalSeconds: Int,
    onStart: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceCard)
            .border(0.5.dp, BorderHairline, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Timer, contentDescription = null, tint = RakshikaAmber, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Check-in timer", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(6.dp))

        if (!active) {
            Text(
                "Set a timer before you head out. If you don't check in, your contacts are notified automatically.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text("Start check-in")
            }
        } else {
            val minutes = secondsLeft / 60
            val seconds = secondsLeft % 60
            val progress = if (totalSeconds > 0) secondsLeft / totalSeconds.toFloat() else 0f

            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = RakshikaAmber,
                trackColor = RakshikaAmberBg
            )
            Spacer(Modifier.height(8.dp))
            Text(
                String.format("%02d:%02d remaining", minutes, seconds),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("I'm safe · cancel")
            }
        }
    }
}

@Composable
private fun QuickActionsGrid(
    onFakeCall: () -> Unit,
    onShareLocation: () -> Unit,
    onOpenContacts: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        QuickActionButton(Icons.Filled.PhoneCallback, "Fake call", Modifier.weight(1f), onFakeCall)
        QuickActionButton(Icons.Filled.Share, "Share location", Modifier.weight(1f), onShareLocation)
        QuickActionButton(Icons.Filled.Group, "Contacts", Modifier.weight(1f), onOpenContacts)
    }
}

@Composable
private fun QuickActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceCard)
            .border(0.5.dp, BorderHairline, RoundedCornerShape(14.dp))
            .padding(vertical = 14.dp)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun CheckInPickerDialog(onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val options = listOf(10, 20, 30, 60)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start check-in timer") },
        text = {
            Column {
                options.forEach { minutes ->
                    TextButton(
                        onClick = { onConfirm(minutes) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("$minutes minutes", modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
