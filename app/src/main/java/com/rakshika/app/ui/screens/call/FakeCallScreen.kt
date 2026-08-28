package com.rakshika.app.ui.screens.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun FakeCallScreen(onEndCall: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF14151A))
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))
        Text("Incoming call", color = Color(0xFFB9B9C0), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))

        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(Color(0xFF2B2C33)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Person, contentDescription = null, tint = Color(0xFFB9B9C0), modifier = Modifier.size(46.dp))
        }

        Spacer(Modifier.height(20.dp))
        Text("Papa", color = Color.White, style = MaterialTheme.typography.titleLarge)
        Text("Mobile", color = Color(0xFFB9B9C0), style = MaterialTheme.typography.bodyMedium)

        Spacer(Modifier.weight(1f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            CallActionButton(
                icon = Icons.Filled.CallEnd,
                bg = Color(0xFFE24B4A),
                onClick = onEndCall
            )
            CallActionButton(
                icon = Icons.Filled.Call,
                bg = Color(0xFF639922),
                onClick = onEndCall
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CallActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, bg: Color, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(bg)
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
    }
}
