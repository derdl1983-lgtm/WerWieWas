package de.derdl1983.weriewas.ui

import android.content.Context
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Auswahlliste einer Profil-Eigenschaft (aktuelle Wahl ist hervorgehoben). */
@Composable
private fun ProfileChoice(
    title: String,
    options: List<Pair<String, String>>,
    current: String,
    onSelect: (String) -> Unit
) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (key, label) ->
            if (key == current) {
                Button(onClick = { }, modifier = Modifier.fillMaxWidth()) { Text(label) }
            } else {
                OutlinedButton(
                    onClick = { onSelect(key) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(label) }
            }
        }
    }
}

/** KI-Profil: Hier legst du fest, wie dir die KI antwortet – Erzählstil, Zielgruppe, Vorwissen, Ton, Sprache und eigene Hinweise. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiProfileScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    var audience by rememberSaveable { mutableStateOf(prefs.getString("ai_audience", "all") ?: "all") }
    var level by rememberSaveable { mutableStateOf(prefs.getString("ai_level", "laie") ?: "laie") }
    var tone by rememberSaveable { mutableStateOf(prefs.getString("ai_tone", "sachlich") ?: "sachlich") }
    var role by rememberSaveable { mutableStateOf(prefs.getString("ai_role", "erzaehler") ?: "erzaehler") }
    var custom by rememberSaveable { mutableStateOf(prefs.getString("ai_custom", "") ?: "") }
    var language by rememberSaveable { mutableStateOf(prefs.getString("ai_language", "de") ?: "de") }

    fun saveProfileValue(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun resetProfile() {
        audience = "all"
        level = "laie"
        tone = "sachlich"
        role = "erzaehler"
        custom = ""
        language = "de"
        prefs.edit()
            .remove("ai_audience")
            .remove("ai_level")
            .remove("ai_tone")
            .remove("ai_role")
            .remove("ai_custom")
            .remove("ai_language")
            .apply()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KI-Profil") },
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
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "KI-Profil & Antwortstil",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "Hier legst du fest, wie dir die KI antwortet: Zielgruppe, Vorwissen, Ton, " +
                    "Erzählstil und Antwort-Sprache (z. B. für den Urlaub oder zum Sprachenlernen). " +
                    "Zusätzlich kannst du eigene Hinweise hinterlegen " +
                    "(z. B. Interessen, gewünschte Anrede, Lieblingsthemen). " +
                    "Gilt für jede Online-Analyse deiner Fotos.",
                style = MaterialTheme.typography.bodyMedium
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ProfileChoice(
                        "Erzählstil",
                        listOf(
                            "erzaehler" to "Erzähler (Standard)",
                            "detektiv" to "Detektiv",
                            "professor" to "Professor",
                            "freund" to "Guter Freund"
                        ),
                        role
                    ) {
                        role = it
                        saveProfileValue("ai_role", it)
                    }
                    ProfileChoice(
                        "Zielgruppe",
                        listOf(
                            "all" to "Erwachsene",
                            "kind" to "Kinder (8-12 Jahre)",
                            "jugend" to "Jugendliche",
                            "senior" to "Senioren"
                        ),
                        audience
                    ) {
                        audience = it
                        saveProfileValue("ai_audience", it)
                    }
                    ProfileChoice(
                        "Vorwissen",
                        listOf(
                            "laie" to "Laie",
                            "fortgeschritten" to "Fortgeschritten",
                            "experte" to "Experte"
                        ),
                        level
                    ) {
                        level = it
                        saveProfileValue("ai_level", it)
                    }
                    ProfileChoice(
                        "Ton",
                        listOf(
                            "sachlich" to "Sachlich",
                            "lustig" to "Lustig",
                            "verruckt" to "Verrückte Geschichte (frei erfunden)",
                            "kurz" to "Kurz & knackig",
                            "ausfuehrlich" to "Ausführlich"
                        ),
                        tone
                    ) {
                        tone = it
                        saveProfileValue("ai_tone", it)
                    }
                    ProfileChoice(
                        "Antwort-Sprache",
                        listOf(
                            "de" to "Deutsch",
                            "en" to "English",
                            "fr" to "Français (Französisch)",
                            "es" to "Español (Spanisch)",
                            "tr" to "Türkçe (Türkisch)",
                            "it" to "Italiano (Italienisch)"
                        ),
                        language
                    ) {
                        language = it
                        saveProfileValue("ai_language", it)
                    }
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { custom = it },
                        label = { Text("Eigene Hinweise (z. B. Interessen, Anrede, Themen)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                saveProfileValue("ai_custom", custom.trim())
                                Toast.makeText(context, "KI-Profil gespeichert.", Toast.LENGTH_SHORT).show()
                            }
                        ) { Text("Profil speichern") }
                        OutlinedButton(
                            onClick = {
                                resetProfile()
                                Toast.makeText(context, "KI-Profil zurückgesetzt.", Toast.LENGTH_SHORT).show()
                            }
                        ) { Text("Zurücksetzen") }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
