package de.derdl1983.weriewas.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import de.derdl1983.weriewas.data.Discovery
import de.derdl1983.weriewas.data.DiscoveryStore
import de.derdl1983.weriewas.data.Explanation
import de.derdl1983.weriewas.data.LabelConfidence
import de.derdl1983.weriewas.data.PendingStore
import de.derdl1983.weriewas.ml.OnlineAnalyzer
import de.derdl1983.weriewas.util.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Analyse-Screen: analysiert das Foto ausschließlich online mit dem in den
 * Einstellungen gewählten KI-Anbieter (eigener API-Schlüssel des Nutzers).
 * Ohne konfigurierten Anbieter wird zum Einrichten der Einstellungen geführt.
 * Ist in den Einstellungen die „Direktanalyse ohne Rückfrage“ aktiviert,
 * startet die Analyse automatisch, sobald das Foto ankommt.
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
    // Foto im Hintergrund dekodieren, damit der Screen sofort aufbaut
    var bitmap by remember(imagePath) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(imagePath) {
        bitmap = withContext(Dispatchers.IO) { Images.loadThumbnail(imagePath, 640) }
    }

    var configured by remember { mutableStateOf(OnlineAnalyzer.isConfigured(context)) }
    var showConsent by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val autoAnalyze = remember {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean("auto_analyze", false)
    }
    var autoStarted by remember { mutableStateOf(false) }

    // Mehrere Fotos pro Objekt: weitere Ansichten ergänzen, die KI kombiniert alle
    var extraPaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingCapture by remember { mutableStateOf<File?>(null) }

    val addPhotoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val f = pendingCapture
        pendingCapture = null
        if (success && f != null && f.exists()) {
            scope.launch {
                val path = withContext(Dispatchers.IO) {
                    val copied = Images.copyToInternal(context, Uri.fromFile(f))
                    f.delete()
                    copied
                }
                if (path != null) {
                    extraPaths = extraPaths + path
                } else {
                    Toast.makeText(context, "Foto konnte nicht gespeichert werden.", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            f?.delete()
        }
    }

    fun addPhoto() {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "photo_add_${System.currentTimeMillis()}.jpg")
        pendingCapture = file
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        runCatching { addPhotoLauncher.launch(uri) }
    }

    val providerLabel = OnlineAnalyzer.label(OnlineAnalyzer.currentProvider(context))

    // Speichern schreibt Dateien – bewusst außerhalb des Main-Threads.
    suspend fun saveAndFinish(explanation: Explanation) {
        val discovery = Discovery(
            id = UUID.randomUUID().toString(),
            imagePath = imagePath,
            timestamp = System.currentTimeMillis(),
            labels = listOf(LabelConfidence(explanation.title, 1f)),
            explanation = explanation,
            extraPaths = extraPaths
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
                    val explanation = OnlineAnalyzer.analyze(listOf(imagePath) + extraPaths, config)
                    saveAndFinish(explanation)
                }
            } catch (e: Exception) {
                error = "Analyse fehlgeschlagen: " + (e.message ?: "unbekannter Fehler")
            } finally {
                running = false
            }
        }
    }

    /** Legt die Fotos in die Warteschlange, wenn die Analyse (z. B. ohne Netz) fehlgeschlagen ist. */
    fun saveForLater() {
        scope.launch {
            withContext(Dispatchers.IO + NonCancellable) {
                // NonCancellable: Das Foto wird garantiert in der Warteschlange
                // gespeichert, auch wenn der Nutzer den Bildschirm sofort verlässt.
                PendingStore.add(context, listOf(imagePath) + extraPaths)
            }
            Toast.makeText(
                context,
                "Foto gemerkt – die Analyse wird später automatisch nachgeholt.",
                Toast.LENGTH_LONG
            ).show()
            onCancel()
        }
    }

    // Direktanalyse: ohne Rückfrage automatisch starten.
    LaunchedEffect(configured) {
        if (configured && autoAnalyze && !autoStarted && !running && error == null) {
            autoStarted = true
            startOnlineAnalysis()
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
                        "Andere Daten verlassen dein Gerät nicht.\n\n" +
                        "Tipp: In den Einstellungen kannst du die „Direktanalyse ohne Rückfrage“ " +
                        "aktivieren – dann startet die Analyse direkt nach dem Foto."
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
    ) {
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
                    shape = RoundedCornerShape(24.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
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

            // Übersicht der weiteren Ansichten (Mehrere Fotos pro Objekt)
            if (extraPaths.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    extraPaths.forEach { path -> ExtraThumb(path) }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "${1 + extraPaths.size} Fotos – die KI kombiniert alle Ansichten für die Erkennung.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(8.dp))
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
                CircularProgressIndicator(strokeWidth = 5.dp)
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
                        shape = RoundedCornerShape(16.dp),
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
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { if (!running) saveForLater() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Für später merken – Analyse automatisch nachsenden")
                    }
                } else {
                    Text(
                        text = if (autoAnalyze)
                            "Direktanalyse aktiv – das Foto wird sofort von $providerLabel analysiert."
                        else
                            "Das Foto wird von $providerLabel analysiert – " +
                                "mit deinem eigenen API-Schlüssel.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { if (!running) addPhoto() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (extraPaths.isEmpty())
                            "Weitere Ansicht fotografieren (optional)"
                        else
                            "Noch ein Foto hinzufügen"
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (autoAnalyze) startOnlineAnalysis() else showConsent = true
                    }) {
                        Text("Online analysieren")
                    }
                    OutlinedButton(onClick = onCancel) { Text("Abbrechen") }
                }
            }
        }
    }
}

/** Kleine Vorschau einer weiteren Ansicht – Dekodieren im Hintergrund. */
@Composable
private fun ExtraThumb(path: String) {
    var thumb by remember(path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(path) {
        thumb = withContext(Dispatchers.IO) { Images.loadThumbnail(path, 128) }
    }
    thumb?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = "Weitere Ansicht",
            modifier = Modifier.size(56.dp),
            contentScale = ContentScale.Crop
        )
    }
}
