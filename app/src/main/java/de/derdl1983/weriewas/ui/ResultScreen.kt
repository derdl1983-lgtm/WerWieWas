package de.derdl1983.weriewas.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import de.derdl1983.weriewas.data.Discovery
import de.derdl1983.weriewas.data.DiscoveryStore
import de.derdl1983.weriewas.util.Images

/** Ergebnis-Screen: zeigt die Erklärung zu einer Entdeckung mit Links, Teilen und Video. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    discoveryId: String,
    onVideo: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var discovery by remember(discoveryId) { mutableStateOf(DiscoveryStore.get(context, discoveryId)) }
    var showDelete by remember { mutableStateOf(false) }

    val d = discovery
    if (d == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Diese Entdeckung wurde nicht gefunden.")
            Spacer(Modifier.height(16.dp))
            Button(onClick = onClose) { Text("Zurück") }
        }
        return
    }

    val bitmap = remember(d.id) { Images.loadThumbnail(d.imagePath, 720) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(d.explanation.title) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Schließen")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            DiscoveryStore.toggleFavorite(context, d.id)
                            discovery = DiscoveryStore.get(context, d.id)
                        }
                    ) {
                        Icon(
                            imageVector = if (d.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = if (d.isFavorite) "Favorit entfernen" else "Als Favorit merken",
                            tint = if (d.isFavorite) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                    IconButton(onClick = { showDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Löschen")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            bitmap?.let {
                Card {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Foto der Entdeckung",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = d.explanation.category.ifBlank { "Allgemein" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Erkannt: " +
                        (if (d.explanation.source == "online") "online (Mistral)" else "lokal (auf dem Gerät)"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            if (d.explanation.uncertain) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "Unsicheres Ergebnis",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Die App ist sich nicht sicher, ob das Objekt richtig erkannt wurde. " +
                                "Bitte prüfe die Angaben, bevor du ihnen folgst – " +
                                "besonders bei Pilzen, Medikamenten, Chemikalien oder Werkzeug.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            SectionCard("Was ist das?", d.explanation.summary)
            SectionCard("Wie funktioniert es?", d.explanation.howItWorks)
            SectionCard("Wofür wird es verwendet?", d.explanation.usage)
            SectionCard("Praktische Tipps", d.explanation.practical)
            if (d.explanation.facts.isNotEmpty()) {
                SectionCard("Interessante Fakten", d.explanation.facts.joinToString("\n") { "• " + it })
            }
            if (d.explanation.safety.isNotBlank()) {
                SectionCard("Sicherheit", d.explanation.safety)
            }

            if (d.explanation.links.isNotEmpty()) {
                Text("Weiterführende Links", style = MaterialTheme.typography.titleMedium)
                d.explanation.links.forEach { link ->
                    OutlinedButton(
                        onClick = { openUrl(context, link.url) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(link.label)
                    }
                }
            }

            Button(onClick = onVideo, modifier = Modifier.fillMaxWidth()) {
                Text("Erklärvideo ansehen")
            }
            OutlinedButton(
                onClick = { shareDiscovery(context, d) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Teilen")
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Entdeckung löschen?") },
            text = { Text("Das Foto und die Erklärung werden dauerhaft gelöscht.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDelete = false
                        DiscoveryStore.remove(context, d.id)
                        onClose()
                    }
                ) { Text("Löschen") }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("Abbrechen") }
            }
        )
    }
}

/** Eine thematische Karte mit Überschrift und Text. */
@Composable
private fun SectionCard(title: String, text: String) {
    if (text.isBlank()) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Öffnet einen Link im Browser; Fehler werden still ignoriert. */
private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Teilt die Entdeckung als Text. */
private fun shareDiscovery(context: Context, d: Discovery) {
    val sb = StringBuilder()
    sb.append(d.explanation.title).append("\n\n")
    sb.append(d.explanation.summary).append("\n\n")
    if (d.explanation.howItWorks.isNotBlank()) {
        sb.append("Wie es funktioniert: ").append(d.explanation.howItWorks).append("\n\n")
    }
    if (d.explanation.usage.isNotBlank()) {
        sb.append("Wofür es verwendet wird: ").append(d.explanation.usage).append("\n\n")
    }
    if (d.explanation.links.isNotEmpty()) {
        sb.append("Links:").append("\n")
        d.explanation.links.forEach {
            sb.append("- ").append(it.label).append(": ").append(it.url).append("\n")
        }
        sb.append("\n")
    }
    sb.append("(Analyseergebnis der App „Wer Wie Was“)")
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, sb.toString())
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(send, "Entdeckung teilen")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
