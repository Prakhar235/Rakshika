package com.rakshika.app.ui.screens.contacts

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.rakshika.app.data.model.EmergencyContact
import com.rakshika.app.ui.theme.*

@Composable
fun ContactsScreen(
    contacts: List<EmergencyContact>,
    onAddContact: (String, String, String) -> Unit,
    onRemoveContact: (String) -> Unit
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
                    "These people are notified the moment SOS is triggered.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(contacts, key = { it.id }) { contact ->
                    ContactRow(contact, onRemove = { onRemoveContact(contact.id) })
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
private fun ContactRow(contact: EmergencyContact, onRemove: () -> Unit) {
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
            Text("${contact.relation} · ${contact.phone}", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = TextSecondary)
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
                OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone") }, singleLine = true)
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
