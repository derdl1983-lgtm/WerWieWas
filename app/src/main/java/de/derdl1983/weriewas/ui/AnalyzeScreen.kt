package de.derdl1983.weriewas.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.derdl1983.weriewas.data.Discovery
import de.derdl1983.weriewas.data.DiscoveryStore
import de.derdl1983.weriewas.data.Explanation
import de.derdl1983.weriewas.data.LabelConfidence
import de.derdl1983.weriewas.ml.OnlineAnalyzer
import de.derdl1983.weriewas.util.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Analyse-Screen: analysiert das Foto ausschließlich online mit dem in den
 * Einstellungen gewählten KI-Anbieter (eigener API-Schlüssel des Nutzers).
 * Ohne konfigurierten Anbieter wird zum Einrichten der Einstellungen geführt.
 */
@Composable
fun AnalyzeScreen(
    imagePath: String,
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
    onSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bitmap = remember(imagePath) { Images.loadThumbnail(imagePath, 640) }

    var configured by remember { mutableStateOf(OnlineAnalyzer.isConfigured(context)) }
    var showConsent by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val providerLabel = OnlineAnalyzer.label(OnlineAnalyzer.currentProvider(context))

    // Speichern schreibt Dateien – bewusst außerhalb des Main-Threads.
    suspend fun saveAndFinish(explanation: Explanation) {
        val discovery = Discovery(
            id = UUID.randomUUID().toString(),
            imagePath = imagePath,
            timestamp = System.currentTimeMillis(),
            labels = listOf(LabelConfidence(explanation.title, 1f)),
            explanation = explanation
        )
        withContext(Dispatchers.IO) {
            DiscoveryStore.add(context, discovery)
        }
        onDone(discovery.id)
    }

    fun startOnlineAnalysis() {
        running = true
        error = null
        scope.launch {
            try {
                val config = OnlineAnalyzer.loadConfig(context)
                if (config == null) {
                    configured = false
                    error = "Kein KI-Anbieter konfiguriert. Hinterlege in den Einstellungen einen API-Schlüssel."
                } else {
                    val explanation = OnlineAnalyzer.analyze(imagePath, config)
                    saveAndFinish(explanation)
                }
            } catch (e: Exception) {
                error = "Analyse fehlgeschlagen: " + (e.message ?: "unbekannter Fehler")
            } finally {
                running = false
            }
        }
    }

    if (showConsent) {
        AlertDialog(
            onDismissRequest = { if (!running) showConsent = false },
            title = { Text("Online analysieren?") },
            text = {
                Text(
                    "Das Foto wird einmalig an den in den Einstellungen gewählten " +
                        "KI-Anbieter ($providerLabel) gesendet und mit deinem eigenen " +
                        "API-Schlüssel analysiert.\n\n" +
                        "Andere Daten verlassen dein Gerät nicht."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConsent = false
                        startOnlineAnalysis()
                    }
                ) { Text("Ja, analysieren") }
            },
            dismissButton = {
                TextButton(
                    onClick = { if (!running) showConsent = false }
                ) { Text("Abbrechen") }
            }
        )
    }

    val message = error
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        bitmap?.let {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Aufgenommenes Foto",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    contentScale = ContentScale.Crop
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        if (!configured) {
            // ---------- Kein KI-Anbieter eingerichtet ----------
            Text(
                text = "Online-Analyse erforderlich",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Die Foto-Analyse läuft ausschließlich online über einen " +
                    "KI-Anbieter mit deinem eigenen API-Schlüssel. " +
                    "Hinterlege in den Einstellungen einen Anbieter und einen API-Schlüssel, " +
                    "um Fotos analysieren zu können.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onSettings,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Einstellungen öffnen") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Zurück") }
        } else if (running) {
            // ---------- Analyse läuft ----------
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Analysiere dein Foto …",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Wird online von $providerLabel analysiert.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        } else {
            // ---------- Bereit zur Analyse ----------
            if (message != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.height(24.dp))
            } else {
                Text(
                    text = "Das Foto wird von $providerLabel analysiert – " +
                        "mit deinem eigenen API-Schlüssel.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(24.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showConsent = true }) {
                    Text("Online analysieren")
                }
                OutlinedButton(onClick = onCancel) { Text("Abbrechen") }
            }
        }
    }
}
