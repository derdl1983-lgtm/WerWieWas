package de.derdl1983.weriewas.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.derdl1983.weriewas.data.Discovery
import de.derdl1983.weriewas.data.DiscoveryStore
import de.derdl1983.weriewas.util.Images
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Verlauf aller Entdeckungen mit Favoriten-Kennzeichnung und Löschen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onOpen: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var list by remember { mutableStateOf(DiscoveryStore.loadAll(context)) }
    var pendingDelete by remember { mutableStateOf<Discovery?>(null) }
    var search by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf<String?>(null) }

    val categories = list.map { it.explanation.category }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
    val filtered = list.filter { entry ->
        val matchesCategory = categoryFilter == null || entry.explanation.category == categoryFilter
        val q = search.trim()
        val matchesSearch = q.isEmpty() ||
            entry.explanation.title.contains(q, ignoreCase = true) ||
            entry.explanation.summary.contains(q, ignoreCase = true)
        matchesCategory && matchesSearch
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Verlauf") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                }
            )
        }
    ) { padding ->
        if (list.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Noch keine Entdeckungen", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.size(8.dp))
                Text(
                    "Mach dein erstes Foto über die Startseite.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    placeholder = { Text("Titel oder Beschreibung suchen") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true
                )
                if (categories.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { categoryFilter = null },
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (categoryFilter == null) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    androidx.compose.ui.graphics.Color.Transparent
                                }
                            )
                        ) { Text("Alle") }
                        categories.forEach { category ->
                            OutlinedButton(
                                onClick = {
                                    categoryFilter = if (categoryFilter == category) null else category
                                },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (categoryFilter == category) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        androidx.compose.ui.graphics.Color.Transparent
                                    }
                                )
                            ) { Text(category) }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (filtered.isEmpty()) {
                    Text(
                        "Keine Treffer",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filtered, key = { it.id }) { entry ->
                            HistoryItem(
                                entry = entry,
                                onClick = { onOpen(entry.id) },
                                onDelete = { pendingDelete = entry }
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Entdeckung löschen?") },
            text = { Text("„" + entry.explanation.title + "“ wird dauerhaft gelöscht.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        DiscoveryStore.remove(context, entry.id)
                        list = DiscoveryStore.loadAll(context)
                        pendingDelete = null
                    }
                ) { Text("Löschen") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Abbrechen") }
            }
        )
    }
}

/** Ein Eintrag im Verlauf. */
@Composable
private fun HistoryItem(
    entry: Discovery,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val thumb = remember(entry.id) { Images.loadThumbnail(entry.imagePath, 128) }
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (thumb != null) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        )
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = entry.explanation.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = dateFormat.format(Date(entry.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                if (entry.explanation.uncertain) {
                    Text(
                        text = "Unsicher erkannt",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            if (entry.isFavorite) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = "Favorit",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Löschen",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
