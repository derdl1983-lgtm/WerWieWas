package de.derdl1983.weriewas.ui

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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import de.derdl1983.weriewas.data.DiscoveryStore

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
    onSettings: () -> Unit
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

    // Mini-Statistik der bisherigen Entdeckungen
    val context = LocalContext.current
    val stats = remember { DiscoveryStore.loadAll(context) }

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
            // Logo-Medaille
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .shadow(8.dp, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Wer Wie Was",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
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
