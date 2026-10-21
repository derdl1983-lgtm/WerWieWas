package de.derdl1983.weriewas.ui

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import de.derdl1983.weriewas.WerWieWasApp
import de.derdl1983.weriewas.data.DiscoveryStore
import de.derdl1983.weriewas.ml.OnlineAnalyzer
import de.derdl1983.weriewas.util.EdgeTTS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

/** Einstellungen: Stimmen (System + Edge-TTS), KI-Profil & Antwortstil, KI-Anbieter, Daten verwalten, Datenschutz. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var provider by rememberSaveable { mutableStateOf(OnlineAnalyzer.currentProvider(context)) }
    var model by rememberSaveable { mutableStateOf(prefs.getString("ai_model", "") ?: "") }
    var baseUrl by rememberSaveable { mutableStateOf(prefs.getString("ai_base_url", "") ?: "") }
    var apiKey by rememberSaveable { mutableStateOf(OnlineAnalyzer.apiKeyFor(context, provider)) }
    var showClear by remember { mutableStateOf(false) }

    // Direktanalyse ohne Rückfrage
    var autoAnalyze by rememberSaveable { mutableStateOf(prefs.getBoolean("auto_analyze", false)) }
    var showAutoDialog by remember { mutableStateOf(false) }

    // KI-Profil & Antwortstil
    var audience by rememberSaveable { mutableStateOf(prefs.getString("ai_audience", "all") ?: "all") }
    var level by rememberSaveable { mutableStateOf(prefs.getString("ai_level", "laie") ?: "laie") }
    var tone by rememberSaveable { mutableStateOf(prefs.getString("ai_tone", "sachlich") ?: "sachlich") }
    var role by rememberSaveable { mutableStateOf(prefs.getString("ai_role", "erzaehler") ?: "erzaehler") }
    var custom by rememberSaveable { mutableStateOf(prefs.getString("ai_custom", "") ?: "") }

    fun saveProfileValue(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun resetProfile() {
        audience = "all"
        level = "laie"
        tone = "sachlich"
        role = "erzaehler"
        custom = ""
        prefs.edit()
            .remove("ai_audience")
            .remove("ai_level")
            .remove("ai_tone")
            .remove("ai_role")
            .remove("ai_custom")
            .apply()
    }

    // Verfügbare deutsche Stimmen der Sprachausgabe laden
    var germanVoices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var voicesReady by remember { mutableStateOf(false) }
    var ttsError by remember { mutableStateOf(false) }
    var crashLog by remember {
        mutableStateOf(
            try {
                WerWieWasApp.latestCrashLog(context)?.takeIf { it.isFile }?.readText()?.take(800)
            } catch (e: Exception) {
                null
            }
        )
    }
    var voiceAName by remember { mutableStateOf(prefs.getString("tts_voice_a", "") ?: "") }
    var voiceBName by remember { mutableStateOf(prefs.getString("tts_voice_b", "") ?: "") }
    var pickA by remember { mutableStateOf(false) }
    var pickB by remember { mutableStateOf(false) }

    // Gratis Online-Stimmen (Edge-TTS)
    var edgeVoiceA by rememberSaveable { mutableStateOf(prefs.getString("edge_voice_a", "") ?: "") }
    var edgeVoiceB by rememberSaveable { mutableStateOf(prefs.getString("edge_voice_b", "") ?: "") }
    var pickEdgeA by remember { mutableStateOf(false) }
    var pickEdgeB by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var edgeTestMessage by remember { mutableStateOf<String?>(null) }
    var testPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        engine = try {
            TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val e = engine
                    if (e != null) {
                        germanVoices = try {
                            e.voices?.filter { it.locale.language == "de" }?.sortedBy { it.name } ?: emptyList()
                        } catch (ex: Exception) {
                            emptyList<Voice>()
                        }
                        voicesReady = true
                    }
                } else {
                    ttsError = true
                }
            }
        } catch (e: Exception) {
            ttsError = true
            null
        }
        onDispose {
            engine?.stop()
            engine?.shutdown()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            testPlayer?.release()
            testPlayer = null
        }
    }

    fun displayName(name: String): String {
        val v = germanVoices.firstOrNull { it.name == name }
        return v?.name?.replace("de-de-x-", "")?.replace("#", " ")?.ifBlank { name } ?: name
    }

    fun selectVoiceA(name: String) {
        voiceAName = name
        prefs.edit().putString("tts_voice_a", name).apply()
    }

    fun selectVoiceB(name: String) {
        voiceBName = name
        prefs.edit().putString("tts_voice_b", name).apply()
    }

    fun selectEdgeA(id: String) {
        edgeVoiceA = id
        prefs.edit().putString("edge_voice_a", id).apply()
    }

    fun selectEdgeB(id: String) {
        edgeVoiceB = id
        prefs.edit().putString("edge_voice_b", id).apply()
    }

    fun openTtsSettings() {
        runCatching {
            context.startActivity(
                Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            Toast.makeText(
                context,
                "Sprachausgabe-Einstellungen konnten nicht geöffnet werden.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun testEdge() {
        val voice = if (edgeVoiceA.isNotBlank()) edgeVoiceA else edgeVoiceB
        if (voice.isBlank()) {
            edgeTestMessage = "Wähle zuerst eine Online-Stimme aus."
            return
        }
        testing = true
        edgeTestMessage = null
        scope.launch(Dispatchers.IO) {
            try {
                val f = File(context.cacheDir, "www_edge_test.mp3")
                f.delete()
                EdgeTTS.synthesizeToMp3(
                    "Hallo! Ich bin eine kostenlose Online-Stimme für dein Erklärvideo.",
                    voice,
                    0,
                    f
                )
                withContext(Dispatchers.Main) {
                    testPlayer?.release()
                    val p = MediaPlayer()
                    p.setDataSource(f.absolutePath)
                    p.prepare()
                    p.start()
                    testPlayer = p
                    edgeTestMessage = "Test läuft: " + EdgeTTS.labelFor(voice)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    edgeTestMessage = "Test fehlgeschlagen: " + (e.message ?: "unbekannter Fehler")
                }
            } finally {
                withContext(Dispatchers.Main) { testing = false }
            }
        }
    }

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
            // ---------- Stimmen fürs Erklärvideo (System) ----------
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Stimmen fürs Erklärvideo (offline)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Wähle, welche installierten Stimmen als Moderator und Erklärbär " +
                            "im Erklärvideo abwechselnd sprechen.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (ttsError) {
                        Text(
                            "Sprachausgabe konnte nicht geladen werden. " +
                                "Du kannst unten eine kostenlose Online-Stimme wählen – " +
                                "sie funktioniert unabhängig von der Geräte-Sprachausgabe.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else if (!voicesReady) {
                        Text(
                            "Sprachausgabe wird geladen …",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    } else if (germanVoices.isEmpty()) {
                        Text(
                            "Keine deutschen Stimmen gefunden. Installiere in den Android-Einstellungen " +
                                "unter Sprachausgabe eine deutsche Stimme.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        OutlinedButton(
                            onClick = { pickA = true },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Moderator: " + if (voiceAName.isBlank()) "(Standard)" else displayName(voiceAName)) }
                        OutlinedButton(
                            onClick = { pickB = true },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Erklärbär: " + if (voiceBName.isBlank()) "(Standard)" else displayName(voiceBName)) }
                    }
                    Text(
                        "Kostenlose Zusatzstimmen offline: Installiere die App „RHVoice“ (F-Droid, gratis, " +
                            "funktioniert ohne Internet) oder lade in den Google-Sprachausgabe-Einstellungen " +
                            "deutsche Sprachpakete. Nach dem Setzen als Standard-Engine erscheinen die Stimmen " +
                            "hier automatisch in der Auswahl.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    OutlinedButton(
                        onClick = { openTtsSettings() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Android-Sprachausgabe öffnen") }
                }
            }

            // ---------- Gratis KI-Stimmen (Edge-TTS, online) ----------
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Gratis KI-Stimmen (Edge-TTS, online)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Sehr natürliche Stimmen von Microsoft Edge – komplett gratis und ohne " +
                            "API-Schlüssel, benötigt nur Internet. Werden für Wiedergabe und Export " +
                            "des Erklärvideos bevorzugt, sobald mindestens eine gewählt ist.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedButton(
                        onClick = { pickEdgeA = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Moderator (online): " +
                                if (edgeVoiceA.isBlank()) "(aus)" else EdgeTTS.labelFor(edgeVoiceA)
                        )
                    }
                    OutlinedButton(
                        onClick = { pickEdgeB = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Erklärbär (online): " +
                                if (edgeVoiceB.isBlank()) "(aus)" else EdgeTTS.labelFor(edgeVoiceB)
                        )
                    }
                    Text(
                        "Wähle bei beiden „(aus)“, um nur die offline System-Stimmen zu nutzen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    OutlinedButton(
                        onClick = { testEdge() },
                        enabled = !testing,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (testing) "Teste Online-Stimme …" else "Online-Stimme testen") }
                    val edgeMsg = edgeTestMessage
                    if (edgeMsg != null) {
                        Text(
                            edgeMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // ---------- KI-Profil & Antwortstil ----------
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "KI-Profil & Antwortstil",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Hier legst du fest, wie dir die KI antwortet: Zielgruppe, Vorwissen, Ton und " +
                            "Erzählstil. Zusätzlich kannst du eigene Hinweise hinterlegen " +
                            "(z. B. Interessen, gewünschte Anrede, Lieblingsthemen). " +
                            "Gilt für jede Online-Analyse deiner Fotos.",
                        style = MaterialTheme.typography.bodyMedium
                    )
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
                            "kurz" to "Kurz & knackig",
                            "ausfuehrlich" to "Ausführlich"
                        ),
                        tone
                    ) {
                        tone = it
                        saveProfileValue("ai_tone", it)
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

            // ---------- KI-Anbieter ----------
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "KI-Anbieter für die Foto-Analyse",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Die Foto-Analyse läuft ausschließlich online über den KI-Anbieter " +
                            "deiner Wahl – mit deinem eigenen API-Schlüssel. " +
                            "Ohne hinterlegten Schlüssel kann keine Analyse gestartet werden. " +
                            "Standardmäßig stimmst du dem Foto-Versand vor jedem Foto ausdrücklich zu; " +
                            "über die „Direktanalyse ohne Rückfrage“ weiter unten kannst du das Abschalten. " +
                            "Du kannst zwischen verschiedenen KI-Anbietern und Modellen wählen.",
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
                                        prefs.edit()
                                            .putString("ai_provider", p)
                                            .remove("ai_model")
                                            .remove("ai_base_url")
                                            .apply()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(OnlineAnalyzer.label(p)) }
                            }
                        }
                    }
                    Text(
                        when (provider) {
                            "openai" -> "Funktioniert mit jedem OpenAI-kompatiblen Dienst " +
                                "(OpenAI selbst oder ein eigener Server). " +
                                "Bei einem anderen Dienst dessen API-Basis-URL eintragen."
                            "openrouter" -> "OpenRouter bündelt viele Modelle (OpenAI, Google, Meta, " +
                                "Anthropic, xAI und weitere) hinter einem einzigen API-Schlüssel – ideal, um " +
                                "verschiedene Modelle auszuprobieren."
                            "gemini" -> "Google Gemini mit eigenem API-Schlüssel aus Google AI Studio. " +
                                "Das Sprachmodell kannst du unten wählen oder frei eintragen."
                            "anthropic" -> "Anthropic Claude mit eigenem API-Schlüssel aus der Anthropic Console. " +
                                "Alle aktuellen Claude-Modelle (Haiku, Sonnet, Opus, Fable) verstehen Bilder."
                            "xai" -> "Grok von xAI mit eigenem API-Schlüssel aus der xAI Console. " +
                                "Aktuelle Grok-Modelle verstehen Fotos und werden über eine " +
                                "OpenAI-kompatible API angesprochen."
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
                        Text(
                            "Für OpenAI selbst dieses Feld LEER lassen. Nur bei einem anderen " +
                                "OpenAI-kompatiblen Dienst (z. B. eigener oder lokaler Server) " +
                                "dessen API-Endpunkt eintragen – das ist NICHT die Website-Adresse, " +
                                "sondern die Server-Adresse, die meist auf /v1 endet. " +
                                "OpenRouter ist eine eigene Auswahloption und braucht keine Basis-URL.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    val suggestions = OnlineAnalyzer.suggestedModels(provider)
                    if (suggestions.size > 1) {
                        Text(
                            "Bekannte Modelle des Anbieters (antippen zum Übernehmen):",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        suggestions.forEach { m ->
                            if (m == model) {
                                Button(onClick = { }, modifier = Modifier.fillMaxWidth()) { Text(m) }
                            } else {
                                OutlinedButton(
                                    onClick = { model = m },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(m) }
                            }
                        }
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
                            }
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
                    Text(
                        if (apiKey.isNotBlank()) {
                            "Analyse bereit: API-Schlüssel für " + OnlineAnalyzer.label(provider) + " hinterlegt."
                        } else {
                            "Kein API-Schlüssel hinterlegt – ohne Schlüssel kann keine Foto-Analyse " +
                                "gestartet werden. Stimmen und Erklärvideo funktionieren weiterhin."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
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

            // ---------- Direktanalyse ohne Rückfrage ----------
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Analyse-Start",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Standardmäßig fragt die App vor jedem Foto nach, ob es online analysiert " +
                            "werden darf. Aktiviere die Direktanalyse, um die Online-Analyse " +
                            "sofort nach dem Fotografieren oder der Bildauswahl zu starten – " +
                            "ohne zusätzlichen Bestätigungsdialog.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Direktanalyse ohne Rückfrage",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                if (autoAnalyze)
                                    "Aktiv: Jedes neue Foto wird sofort online analysiert."
                                else
                                    "Aus: Vor jedem Foto wirst du gefragt.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        Switch(
                            checked = autoAnalyze,
                            onCheckedChange = { wanted ->
                                if (wanted) {
                                    showAutoDialog = true
                                } else {
                                    autoAnalyze = false
                                    prefs.edit().putBoolean("auto_analyze", false).apply()
                                    Toast.makeText(context, "Direktanalyse deaktiviert.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
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
                    val log = crashLog
                    if (log != null) {
                        Text(
                            "Letzter Fehlerbericht der App (entsteht nach einem Absturz):",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            log,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                runCatching {
                                    val text = try {
                                        WerWieWasApp.latestCrashLog(context)?.readText()?.take(8000)
                                    } catch (e: Exception) {
                                        null
                                    }
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, text ?: "Kein Protokoll gefunden.")
                                    }
                                    context.startActivity(
                                        Intent.createChooser(send, "Fehlerbericht teilen")
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }) { Text("Fehlerbericht teilen") }
                            OutlinedButton(onClick = {
                                WerWieWasApp.clearCrashLogs(context)
                                crashLog = null
                                Toast.makeText(context, "Protokoll gelöscht.", Toast.LENGTH_SHORT).show()
                            }) { Text("Protokoll löschen") }
                        }
                    }
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
                        "Wer Wie Was sendet ohne deine Zustimmung keine Daten an Dritte. " +
                            "Die Foto-Analyse läuft online über den von dir gewählten KI-Anbieter.\n\n" +
                            "Standardmäßig verlassen Fotos dein Gerät erst, nachdem du vor jedem " +
                            "einzelnen Foto ausdrücklich zugestimmt hast. Hast du die " +
                            "„Direktanalyse ohne Rückfrage“ aktiviert, wird jedes neue Foto " +
                            "sofort automatisch an den gewählten KI-Anbieter übertragen. " +
                            "Der API-Schlüssel wird ausschließlich lokal gespeichert.\n\n" +
                            "Die optionalen Edge-TTS-Stimmen senden den jeweils vorzulesenden Text " +
                            "an den Microsoft-Lesedienst, um die Sprache zu erzeugen. " +
                            "Es gibt kein Tracking, keine Werbung und keine Konten. " +
                            "Details stehen in der Datei DATENSCHUTZ.md im Projekt-Repository.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (pickA) {
        AlertDialog(
            onDismissRequest = { pickA = false },
            title = { Text("Moderator-Stimme") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    germanVoices.forEach { v ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            RadioButton(selected = v.name == voiceAName, onClick = { selectVoiceA(v.name); pickA = false })
                            TextButton(onClick = { selectVoiceA(v.name); pickA = false }) {
                                Text(v.name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickA = false }) { Text("Fertig") }
            }
        )
    }

    if (pickB) {
        AlertDialog(
            onDismissRequest = { pickB = false },
            title = { Text("Erklärbär-Stimme") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    germanVoices.forEach { v ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            RadioButton(selected = v.name == voiceBName, onClick = { selectVoiceB(v.name); pickB = false })
                            TextButton(onClick = { selectVoiceB(v.name); pickB = false }) {
                                Text(v.name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickB = false }) { Text("Fertig") }
            }
        )
    }

    if (pickEdgeA) {
        AlertDialog(
            onDismissRequest = { pickEdgeA = false },
            title = { Text("Moderator (Edge-TTS, online)") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(selected = edgeVoiceA.isBlank(), onClick = { selectEdgeA(""); pickEdgeA = false })
                        TextButton(onClick = { selectEdgeA(""); pickEdgeA = false }) {
                            Text("(aus – offline System-Stimme)", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    EdgeTTS.GERMAN_VOICES.forEach { v ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            RadioButton(selected = v.id == edgeVoiceA, onClick = { selectEdgeA(v.id); pickEdgeA = false })
                            TextButton(onClick = { selectEdgeA(v.id); pickEdgeA = false }) {
                                Text(v.label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickEdgeA = false }) { Text("Fertig") }
            }
        )
    }

    if (pickEdgeB) {
        AlertDialog(
            onDismissRequest = { pickEdgeB = false },
            title = { Text("Erklärbär (Edge-TTS, online)") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(selected = edgeVoiceB.isBlank(), onClick = { selectEdgeB(""); pickEdgeB = false })
                        TextButton(onClick = { selectEdgeB(""); pickEdgeB = false }) {
                            Text("(aus – offline System-Stimme)", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    EdgeTTS.GERMAN_VOICES.forEach { v ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            RadioButton(selected = v.id == edgeVoiceB, onClick = { selectEdgeB(v.id); pickEdgeB = false })
                            TextButton(onClick = { selectEdgeB(v.id); pickEdgeB = false }) {
                                Text(v.label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickEdgeB = false }) { Text("Fertig") }
            }
        )
    }

    if (showAutoDialog) {
        AlertDialog(
            onDismissRequest = { showAutoDialog = false },
            title = { Text("Direktanalyse aktivieren?") },
            text = {
                Text(
                    "Künftig wird jedes aufgenommene oder gewählte Foto sofort und ohne Rückfrage " +
                        "an den KI-Anbieter " + OnlineAnalyzer.label(OnlineAnalyzer.currentProvider(context)) +
                        " gesendet und mit deinem API-Schlüssel analysiert.\n\n" +
                        "Du kannst die Direktanalyse hier jederzeit wieder ausschalten."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        autoAnalyze = true
                        prefs.edit().putBoolean("auto_analyze", true).apply()
                        showAutoDialog = false
                        Toast.makeText(context, "Direktanalyse aktiviert.", Toast.LENGTH_SHORT).show()
                    }
                ) { Text("Aktivieren") }
            },
            dismissButton = {
                TextButton(onClick = { showAutoDialog = false }) { Text("Abbrechen") }
            }
        )
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





