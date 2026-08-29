package com.rakshika.app.ui.screens.contacts

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rakshika.app.data.model.EmergencyContact
import com.rakshika.app.ui.theme.*

@Composable
fun ContactsScreen(
    contacts: List<EmergencyContact>,
    smsPermissionGranted: Boolean,
    onSmsPermissionResult: () -> Unit,
    onAddContact: (String, String, String) -> Unit,
    onRemoveContact: (String) -> Unit,
    onToggleAlerts: (String, Boolean) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = SurfacePage,
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }, containerColor = RakshikaRed) {
                Icon(Icons.Filled.Add, contentDescription = "Add contact")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Emergency contacts", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Numbers with alerts on get a background SMS on SOS, a missed check-in, " +
                        "and when a ride starts or ends.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    SmsPermissionCard(smsPermissionGranted, onSmsPermissionResult)
                    Spacer(Modifier.height(4.dp))
                }
                items(contacts, key = { it.id }) { contact ->
                    ContactRow(
                        contact = contact,
                        onRemove = { onRemoveContact(contact.id) },
                        onToggleAlerts = { onToggleAlerts(contact.id, it) }
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    if (showAddDialog) {
        AddContactDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, phone, relation ->
                onAddContact(name, phone, relation)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun SmsPermissionCard(granted: Boolean, onResult: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { onResult() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (granted) RakshikaGreenBg else RakshikaRedBg)
            .border(0.5.dp, BorderHairline, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Sms,
            contentDescription = null,
            tint = if (granted) RakshikaGreen else RakshikaRedDark,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Background SMS alerts", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (granted) "On — alerts send silently, no app opens."
                else "Off — grant SMS permission to send alerts automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
        if (!granted) {
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = { launcher.launch(Manifest.permission.SEND_SMS) },
                colors = ButtonDefaults.buttonColors(containerColor = RakshikaRed)
            ) { Text("Enable") }
        }
    }
}

@Composable
private fun ContactRow(
    contact: EmergencyContact,
    onRemove: () -> Unit,
    onToggleAlerts: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceCard)
            .border(0.5.dp, BorderHairline, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(RakshikaRedBg),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    contact.name.take(1).uppercase(),
                    color = RakshikaRedDark,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(contact.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${contact.relation} · ${contact.phone}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = TextSecondary)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Send alerts to this number", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Spacer(Modifier.weight(1f))
            Switch(checked = contact.alertsEnabled, onCheckedChange = onToggleAlerts)
        }
    }
}

@Composable
private fun AddContactDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var relation by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add emergency contact") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone (with country code, e.g. +91…)") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Phone)
                )
                OutlinedTextField(value = relation, onValueChange = { relation = it }, label = { Text("Relation") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank() && phone.isNotBlank()) onConfirm(name, phone, relation.ifBlank { "Contact" }) }
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
