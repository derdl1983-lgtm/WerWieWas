package de.derdl1983.weriewas.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import de.derdl1983.weriewas.data.DiscoveryStore
import de.derdl1983.weriewas.ml.OnlineAnalyzer

/** Einstellungen: wählbarer KI-Anbieter mit API-Schlüssel, Daten verwalten, Datenschutz. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var provider by rememberSaveable { mutableStateOf(OnlineAnalyzer.currentProvider(context)) }
    var model by rememberSaveable { mutableStateOf(prefs.getString("ai_model", "") ?: "") }
    var baseUrl by rememberSaveable { mutableStateOf(prefs.getString("ai_base_url", "") ?: "") }
    var apiKey by rememberSaveable { mutableStateOf(OnlineAnalyzer.apiKeyFor(context, provider)) }
    var showClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Optionale Online-Tiefenanalyse (KI)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Die App erkennt Objekte standardmäßig komplett offline. " +
                            "Wenn du möchtest, kannst du einen eigenen API-Schlüssel hinterlegen: " +
                            "Bei unsicheren Erkennungen fragt dich die App dann pro Foto, " +
                            "ob sie eine genauere Analyse online durchführen darf. " +
                            "Du kannst zwischen verschiedenen KI-Anbietern wählen.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Anbieter",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OnlineAnalyzer.PROVIDERS.forEach { p ->
                            if (p == provider) {
                                Button(
                                    onClick = { },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(OnlineAnalyzer.label(p)) }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        provider = p
                                        model = ""
                                        baseUrl = ""
                                        apiKey = OnlineAnalyzer.apiKeyFor(context, p)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(OnlineAnalyzer.label(p)) }
                            }
                        }
                    }
                    Text(
                        when (provider) {
                            "openai" -> "Funktioniert mit jedem OpenAI-kompatiblen Dienst " +
                                "(OpenAI, OpenRouter, Groq, lokaler Server). " +
                                "Bei einem anderen Dienst dessen Basis-URL eintragen."
                            "gemini" -> "Google Gemini mit eigenem API-Schlüssel aus Google AI Studio."
                            else -> "Mistral mit Bildverständnis-Modell (Standard)."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it.trim() },
                        label = { Text("Modell (leer = Standard: " + OnlineAnalyzer.defaultModel(provider) + ")") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (provider == "openai") {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it.trim() },
                            label = { Text("Basis-URL (leer = https://api.openai.com/v1)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it.trim() },
                        label = { Text("API-Schlüssel (" + OnlineAnalyzer.label(provider) + ")") },
                        singleLine = true,
                        visualTransformation = if (apiKey.isBlank()) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                prefs.edit()
                                    .putString("ai_provider", provider)
                                    .putString("ai_model", model)
                                    .putString("ai_base_url", baseUrl)
                                    .putString(provider + "_api_key", apiKey)
                                    .apply()
                                Toast.makeText(context, "Einstellungen gespeichert.", Toast.LENGTH_SHORT).show()
                            },
                            enabled = apiKey.isNotBlank()
                        ) { Text("Speichern") }
                        OutlinedButton(
                            onClick = {
                                prefs.edit().remove(provider + "_api_key").apply()
                                apiKey = ""
                                Toast.makeText(context, "API-Schlüssel entfernt.", Toast.LENGTH_SHORT).show()
                            },
                            enabled = apiKey.isNotBlank()
                        ) { Text("Entfernen") }
                    }
                    TextButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(OnlineAnalyzer.consoleUrl(provider)))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    ) { Text("Schlüssel kostenlos erstellen") }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Daten",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Alle Fotos und Entdeckungen liegen ausschließlich im App-internen Speicher deines Geräts. " +
                            "Exportierte Erklärvideos werden in deiner Mediathek gespeichert. " +
                            "Ein Backup oder eine Übertragung auf andere Geräte ist nicht vorgesehen.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedButton(
                        onClick = { showClear = true },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("Alle Entdeckungen löschen") }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Datenschutz",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Wer Wie Was sendet ohne deine ausdrückliche Zustimmung keine Daten an Dritte. " +
                            "Die Bilderkennung läuft lokal auf dem Gerät.\n\n" +
                            "Fotos verlassen dein Gerät nur dann, wenn du die optionale Online-Analyse " +
                            "aktiviert hast UND vor jedem einzelnen Foto ausdrücklich zustimmst – " +
                            "dann nur an den von dir gewählten KI-Anbieter. " +
                            "Der API-Schlüssel wird ausschließlich lokal gespeichert.\n\n" +
                            "Es gibt kein Tracking, keine Werbung und keine Konten. " +
                            "Details stehen in der Datei DATENSCHUTZ.md im Projekt-Repository.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text("Alle Entdeckungen löschen?") },
            text = { Text("Alle Fotos und Erklärungen werden dauerhaft vom Gerät gelöscht.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        DiscoveryStore.clearAll(context)
                        showClear = false
                        Toast.makeText(context, "Alle Entdeckungen gelöscht.", Toast.LENGTH_SHORT).show()
                    }
                ) { Text("Alles löschen") }
            },
            dismissButton = {
                TextButton(onClick = { showClear = false }) { Text("Abbrechen") }
            }
        )
    }
}
