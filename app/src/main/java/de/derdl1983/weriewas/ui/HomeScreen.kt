package de.derdl1983.weriewas.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.derdl1983.weriewas.data.Discovery
import de.derdl1983.weriewas.data.DiscoveryStore
import de.derdl1983.weriewas.data.LabelConfidence
import de.derdl1983.weriewas.data.PendingItem
import de.derdl1983.weriewas.data.PendingStore
import de.derdl1983.weriewas.ml.OnlineAnalyzer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Startseite der App mit großem Kamera-Schnellstart, Galerie-, Verlauf- und
 * Einstellungs-Karten auf einem frischen Farbverlauf. Schnellfoto-Wege:
 * Kamera-Button, App-Symbol-Long-Press-Shortcuts und die Schnelleinstellungs-Kachel.
 */
@Composable
fun HomeScreen(
    autoOpenCamera: Boolean,
    onAutoOpened: () -> Unit,
    autoOpenGallery: Boolean,
    onAutoGalleryOpened: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onVideoArchive: () -> Unit = {},
    onAiProfile: () -> Unit = {}
) {
    LaunchedEffect(autoOpenCamera) {
        if (autoOpenCamera) {
            onAutoOpened()
            onCamera()
        }
    }
    LaunchedEffect(autoOpenGallery) {
        if (autoOpenGallery) {
            onAutoGalleryOpened()
            onGallery()
        }
    }

    // Mini-Statistik der bisherigen Entdeckungen – Datei-I/O im Hintergrund laden,
    // damit die Startseite auch bei vielen Einträgen sofort bedienbar bleibt.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf(listOf<Discovery>()) }
    var pending by remember { mutableStateOf(listOf<PendingItem>()) }
    var queueRunning by remember { mutableStateOf(false) }
    var queueMessage by remember { mutableStateOf<String?>(null) }

    /** Prüft, ob aktuell eine Internetverbindung besteht. */
    fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Sendet alle wartenden Fotos erneut an die KI – wird automatisch beim App-Start versucht. */
    fun processQueue() {
        if (queueRunning || pending.isEmpty()) return
        val config = OnlineAnalyzer.loadConfig(context) ?: return
        queueRunning = true
        queueMessage = null
        scope.launch {
            var doneCount = 0
            try {
                for (item in pending.toList()) {
                    val explanation = withContext(Dispatchers.IO) {
                        OnlineAnalyzer.analyze(item.paths, config)
                    }
                    val discovery = Discovery(
                        id = UUID.randomUUID().toString(),
                        imagePath = item.paths.first(),
                        timestamp = System.currentTimeMillis(),
                        labels = listOf(LabelConfidence(explanation.title, 1f)),
                        explanation = explanation,
                        extraPaths = item.paths.drop(1)
                    )
                    withContext(Dispatchers.IO) {
                        DiscoveryStore.add(context, discovery)
                        PendingStore.remove(context, item.id)
                    }
                    doneCount++
                }
            } catch (e: CancellationException) {
                // Bildschirm wurde während der Analyse verlassen – sauber beenden,
                // damit das Abbrechen nicht wie ein Fehler behandelt wird.
                throw e
            } catch (e: Exception) {
                // Netz bleibt weg oder API-Fehler: Der Rest bleibt in der Warteschlange
                queueMessage = "Nachsenden abgebrochen: " + (e.message ?: "unbekannter Fehler")
            } finally {
                queueRunning = false
                // NonCancellable: Der Zustand wird auch dann sicher aktualisiert, wenn
                // der Nutzer den Bildschirm während der Analyse verlässt. Ein normales
                // withContext würde in einer bereits abgebrochenen Coroutine mit einer
                // unbehandelten Ausnahme die komplette App zum Absturz bringen.
                withContext(Dispatchers.IO + NonCancellable) {
                    pending = PendingStore.loadAll(context)
                    stats = DiscoveryStore.loadAll(context)
                }
                if (doneCount > 0) {
                    queueMessage = "$doneCount gemerkte Fotos erfolgreich analysiert."
                }
            }
        }
    }

    // Laden + automatischer Nachsende-Versuch, sobald die Startseite offen
    // ist und Netz da ist (Datei-I/O bewusst nicht auf dem Haupt-Thread).
    LaunchedEffect(Unit) {
        val loadedPending = withContext(Dispatchers.IO) { PendingStore.loadAll(context) }
        pending = loadedPending
        stats = withContext(Dispatchers.IO) { DiscoveryStore.loadAll(context) }
        if (loadedPending.isNotEmpty() && isOnline()) {
            processQueue()
        }
    }

    // Tipp-Feedback für den großen Kamera-Button
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.45f),
        label = "cameraScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // App-Logo aus den Logo-Elementen: Kamera mit Gehirn, Lupe und Sprechblasen
            WerWieWasLogo(
                markInk = MaterialTheme.colorScheme.onBackground,
                textColor = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Fotografiere ein Objekt – die App erklärt dir, was es ist, " +
                    "wie es funktioniert und wofür man es benutzt.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
            Spacer(Modifier.height(36.dp))

            // Großer Kamera-Button mit Druck-Animation und Schatten
            Button(
                onClick = onCamera,
                modifier = Modifier
                    .size(110.dp)
                    .shadow(16.dp, CircleShape, spotColor = MaterialTheme.colorScheme.primary)
                    .scale(scale),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                interactionSource = interaction,
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.PhotoCamera,
                    contentDescription = "Foto machen",
                    modifier = Modifier.size(48.dp)
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "Foto machen",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Noch schneller: App-Symbol gedrückt halten oder die Schnellstart-Kachel nutzen",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
            Spacer(Modifier.height(28.dp))

            // Galerie-Karte (voll breit)
            ActionCard(
                icon = Icons.Outlined.PhotoLibrary,
                title = "Foto aus der Galerie",
                subtitle = "Vorhandenes Bild auswählen und analysieren",
                onClick = onGallery
            )
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionCard(
                    icon = Icons.Outlined.History,
                    title = "Verlauf",
                    subtitle = "Frühere Entdeckungen",
                    onClick = onHistory,
                    modifier = Modifier.weight(1f)
                )
                ActionCard(
                    icon = Icons.Outlined.Settings,
                    title = "Einstellungen",
                    subtitle = "KI, Stimmen & mehr",
                    onClick = onSettings,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(12.dp))

            ActionCard(
                icon = Icons.Outlined.VideoLibrary,
                title = "Video-Archiv",
                subtitle = "Alle erstellten Videos – hochladen auf YouTube, TikTok & Co.",
                onClick = onVideoArchive
            )
            Spacer(Modifier.height(12.dp))

            ActionCard(
                icon = Icons.Outlined.AutoAwesome,
                title = "KI-Profil",
                subtitle = "Erzählstil, Zielgruppe, Ton & Sprache festlegen",
                onClick = onAiProfile
            )

            // Analyse-Warteschlange: gemerkte Fotos automatisch/manuell nachsenden
            if (pending.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "Analyse-Warteschlange",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "${pending.size} " +
                                (if (pending.size == 1) "Fotoset wartet" else "Fotosets warten") +
                                " auf die Analyse – sie werden automatisch gesendet, sobald wieder Internet da ist.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        val qm = queueMessage
                        if (qm != null) {
                            Text(
                                qm,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                        if (queueRunning) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    "Analysiere gemerkte Fotos …",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        } else {
                            Button(onClick = { processQueue() }) {
                                Text("Jetzt erneut analysieren")
                            }
                        }
                    }
                }
            }

            // Mini-Statistik: Anzahl Entdeckungen + Top-Kategorien
            if (stats.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                val topCategories = stats
                    .map { it.explanation.category }
                    .filter { it.isNotBlank() }
                    .groupingBy { it }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.value }
                    .take(3)
                    .joinToString(", ") { "${it.key} (${it.value})" }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "Deine Entdeckungen",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "${stats.size} analysierte Objekte",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        val weekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                        val weekCount = stats.count { it.timestamp > weekAgo }
                        val categoryCount = stats.map { it.explanation.category }
                            .filter { it.isNotBlank() }
                            .distinct()
                            .size
                        if (weekCount > 0) {
                            Text(
                                "Diese Woche entdeckt: $weekCount",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                        if (categoryCount > 1) {
                            Text(
                                "Bereits $categoryCount verschiedene Kategorien entdeckt",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                        if (topCategories.isNotBlank()) {
                            Text(
                                "Top-Kategorien: $topCategories",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Text(
                text = "Deine Fotos werden online von der KI deines gewählten Anbieters analysiert – mit deinem eigenen API-Schlüssel.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }
    }
}

/** Karte mit Icon, Titel und Untertitel – für die Startseiten-Aktionen. */
@Composable
private fun ActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}


