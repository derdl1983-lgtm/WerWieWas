package de.derdl1983.weriewas.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.derdl1983.weriewas.data.Discovery
import de.derdl1983.weriewas.data.DiscoveryStore
import de.derdl1983.weriewas.ml.AnalysisResult
import de.derdl1983.weriewas.ml.ImageAnalyzer
import de.derdl1983.weriewas.ml.OnlineAnalyzer
import de.derdl1983.weriewas.util.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Liest den API-Schlüssel des aktuell gewählten KI-Anbieters aus den App-Einstellungen. */
fun getApiKey(context: Context): String =
    OnlineAnalyzer.apiKeyFor(context, OnlineAnalyzer.currentProvider(context))

/** Analyse-Screen: erkennt das Foto lokal und fragt bei Unsicherheit ggf. um Einwilligung. */
@Composable
fun AnalyzeScreen(
    imagePath: String,
    onDone: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bitmap = remember(imagePath) { Images.loadThumbnail(imagePath, 640) }

    var error by remember { mutableStateOf<String?>(null) }
    var waitingForConsent by remember { mutableStateOf(false) }
    var pendingResult by remember { mutableStateOf<AnalysisResult?>(null) }

    // Speichern schreibt Dateien – bewusst außerhalb des Main-Threads.
    suspend fun saveAndFinish(result: AnalysisResult) {
        val discovery = Discovery(
            id = UUID.randomUUID().toString(),
            imagePath = imagePath,
            timestamp = System.currentTimeMillis(),
            labels = result.labels,
            explanation = result.explanation
        )
        withContext(Dispatchers.IO) {
            DiscoveryStore.add(context, discovery)
        }
        onDone(discovery.id)
    }

    LaunchedEffect(imagePath) {
        try {
            val result = ImageAnalyzer.analyze(imagePath)
            if (result.explanation.uncertain && OnlineAnalyzer.isConfigured(context)) {
                pendingResult = result
                waitingForConsent = true
            } else {
                saveAndFinish(result)
            }
        } catch (e: Exception) {
            error = e.message ?: "Unbekannter Fehler bei der Analyse."
        }
    }

    if (waitingForConsent && pendingResult != null) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Online-Tiefenanalyse?") },
            text = {
                Text(
                    "Die lokale Erkennung ist sich bei diesem Foto nicht ganz sicher.\n\n" +
                        "Mit deiner Erlaubnis wird das Foto einmalig an den in den Einstellungen " +
                        "gewählten KI-Anbieter geschickt, um eine genauere Analyse zu erhalten. " +
                        "Dafür ist dein eigener API-Schlüssel hinterlegt.\n\n" +
                        "Ohne Erlaubnis behalten wir die lokale Analyse."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        waitingForConsent = false
                        val localResult = pendingResult
                        val config = OnlineAnalyzer.loadConfig(context)
                        if (localResult != null && config != null) {
                            scope.launch {
                                val online = try {
                                    OnlineAnalyzer.analyze(imagePath, config)
                                } catch (e: Exception) {
                                    Toast.makeText(
                                        context,
                                        "Online-Analyse fehlgeschlagen – lokale Analyse wird verwendet.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    null
                                }
                                saveAndFinish(
                                    if (online != null) AnalysisResult(localResult.labels, online) else localResult
                                )
                            }
                        } else if (localResult != null) {
                            scope.launch { saveAndFinish(localResult) }
                        }
                    }
                ) { Text("Ja, online analysieren") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        waitingForConsent = false
                        val localResult = pendingResult
                        if (localResult != null) {
                            scope.launch { saveAndFinish(localResult) }
                        }
                    }
                ) { Text("Nein, lokal bleiben") }
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

        if (message == null) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Analysiere dein Foto …",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Die Erkennung läuft offline auf deinem Gerät.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        } else {
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
            Button(onClick = onCancel) { Text("Zurück") }
        }
    }
}
