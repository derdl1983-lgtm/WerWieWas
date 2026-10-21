package de.derdl1983.weriewas.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.core.content.FileProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import de.derdl1983.weriewas.ml.OnlineAnalyzer
import de.derdl1983.weriewas.util.EdgeTTS
import de.derdl1983.weriewas.util.Images
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Ein Abschnitt der schriftlichen Erklärung zum Vorlesen. */
private data class ReadSection(val title: String, val text: String)

/** Baut die vorlesbaren Abschnitte aus einer Entdeckung. */
private fun buildReadSections(d: Discovery): List<ReadSection> {
    val list = mutableListOf<ReadSection>()
    if (d.explanation.summary.isNotBlank()) {
        list.add(ReadSection("Was ist das?", d.explanation.summary))
    }
    if (d.explanation.howItWorks.isNotBlank()) {
        list.add(ReadSection("Wie funktioniert es?", d.explanation.howItWorks))
    }
    if (d.explanation.usage.isNotBlank()) {
        list.add(ReadSection("Wofür wird es verwendet?", d.explanation.usage))
    }
    if (d.explanation.practical.isNotBlank()) {
        list.add(ReadSection("Praktische Tipps", d.explanation.practical))
    }
    if (d.explanation.facts.isNotEmpty()) {
        list.add(ReadSection("Interessante Fakten", d.explanation.facts.joinToString("\n") { "• " + it }))
    }
    if (d.explanation.safety.isNotBlank()) {
        list.add(ReadSection("Sicherheit", d.explanation.safety))
    }
    return list
}

/** Ergebnis-Screen: zeigt die Erklärung zu einer Entdeckung mit Vorlesen-Funktion, Links, Teilen und Video. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    discoveryId: String,
    onVideo: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var discovery by remember(discoveryId) { mutableStateOf(DiscoveryStore.get(context, discoveryId)) }
    var showDelete by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var showChatDialog by remember { mutableStateOf(false) }
    var showOnlineDialog by remember { mutableStateOf(false) }
    var onlineRunning by remember { mutableStateOf(false) }
    var onlineError by remember { mutableStateOf<String?>(null) }

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
    val sections = remember(d.id) { buildReadSections(d) }

    // ---------- Vorlesen ----------
    // Edge-TTS-Online-Stimme hat Vorrang (Erklärbär-Stimme, sonst Moderator),
    // ansonsten die System-Sprachausgabe mit der gewählten Erklärbär-Stimme.
    val edgeVoice = remember {
        val b = prefs.getString("edge_voice_b", "") ?: ""
        val a = prefs.getString("edge_voice_a", "") ?: ""
        if (b.isNotBlank()) b else a
    }
    val useEdge = edgeVoice.isNotBlank()

    var readActive by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var readIndex by remember { mutableIntStateOf(-1) }
    var readVoiceReady by remember { mutableStateOf(false) }
    var readLoading by remember { mutableStateOf(false) }
    var readError by remember { mutableStateOf<String?>(null) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsFailed by remember { mutableStateOf(false) }
    var readVoice by remember { mutableStateOf<Voice?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playerIndex by remember { mutableIntStateOf(-1) }

    fun stopReading() {
        readActive = false
        playing = false
        readIndex = -1
        readError = null
        tts?.stop()
        player?.let { runCatching { it.stop() } }
        player?.release()
        player = null
        playerIndex = -1
    }

    fun startOrToggleReading() {
        readError = null
        if (!useEdge && ttsFailed) {
            readError = "Die Sprachausgabe dieses Geräts ist nicht verfügbar. " +
                "Wähle in den Einstellungen eine kostenlose Online-Stimme " +
                "oder installiere eine Sprachausgabe (z. B. Google-Sprachausgabe)."
            return
        }
        if (!readActive) {
            readActive = true
            playing = true
            readIndex = 0
            return
        }
        if (playing) {
            // Pause
            playing = false
            if (useEdge) {
                player?.let { runCatching { it.pause() } }
            } else {
                tts?.stop()
            }
        } else {
            // Weiter
            playing = true
            if (readIndex < 0) readIndex = 0
        }
    }

    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        engine = try {
            TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.let { e ->
                    // Sprache der Analyse (Einstellung) –Fallback Deutsch
                    val lang = prefs.getString("ai_language", "de") ?: "de"
                    val locale = if (lang == "de") Locale.GERMANY else Locale.forLanguageTag(lang)
                    e.setLanguage(locale)
                    val langVoices = try {
                        e.voices?.filter { it.locale.language == lang } ?: emptyList()
                    } catch (ex: Exception) {
                        emptyList<Voice>()
                    }
                    val germanVoices = if (langVoices.isEmpty() && lang != "de") {
                        try {
                            e.voices?.filter { it.locale.language == "de" } ?: emptyList()
                        } catch (ex: Exception) {
                            emptyList<Voice>()
                        }
                    } else {
                        langVoices
                    }
                    val sorted = germanVoices.sortedBy { it.name }
                    val nameB = prefs.getString("tts_voice_b", null)
                    val nameA = prefs.getString("tts_voice_a", null)
                    readVoice = sorted.firstOrNull { it.name == nameB }
                        ?: sorted.firstOrNull { it.name == nameA }
                        ?: sorted.firstOrNull()
                    e.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onDone(utteranceId: String?) {
                            if (utteranceId?.startsWith("read_") == true) {
                                mainHandler.post {
                                    if (readActive && playing) {
                                        val idx = utteranceId.removePrefix("read_").toIntOrNull() ?: -1
                                        if (idx >= 0 && idx < sections.size - 1) {
                                            readIndex = idx + 1
                                        } else {
                                            readActive = false
                                            playing = false
                                            readIndex = -1
                                        }
                                    }
                                }
                            }
                        }

                        override fun onError(utteranceId: String?) {
                            mainHandler.post {
                                readActive = false
                                playing = false
                                readIndex = -1
                            }
                        }

                        override fun onStart(utteranceId: String?) {
                        }
                    })
                    readVoiceReady = true
                }
            } else {
                ttsFailed = true
            }
            }
        } catch (e: Exception) {
            ttsFailed = true
            null
        }
        tts = engine
        onDispose {
            engine?.stop()
            engine?.shutdown()
            tts = null
            player?.release()
            player = null
            readVoiceReady = false
        }
    }

    // Vorlesen mit der System-Sprachausgabe
    LaunchedEffect(readActive, playing, readIndex, readVoiceReady) {
        if (useEdge) return@LaunchedEffect
        if (!readActive || !playing || readIndex < 0 || !readVoiceReady) return@LaunchedEffect
        val engine = tts ?: return@LaunchedEffect
        val section = sections.getOrNull(readIndex) ?: return@LaunchedEffect
        val voice = readVoice
        if (voice != null) engine.setVoice(voice)
        engine.setPitch(1f)
        engine.setSpeechRate(1f)
        engine.speak(
            section.title + ". " + section.text,
            TextToSpeech.QUEUE_FLUSH,
            Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "read_" + readIndex)
            },
            "read_" + readIndex
        )
    }

    // Vorlesen mit der gratis Edge-TTS-Online-Stimme
    LaunchedEffect(readActive, playing, readIndex) {
        if (!useEdge) return@LaunchedEffect
        if (!readActive || !playing || readIndex < 0) return@LaunchedEffect
        val section = sections.getOrNull(readIndex) ?: return@LaunchedEffect
        // Fortsetzen einer pausierten Wiedergabe derselben Stelle
        val existing = player
        if (existing != null && playerIndex == readIndex) {
            runCatching { existing.start() }
            return@LaunchedEffect
        }
        val old = existing
        old?.let { runCatching { it.stop() } }
        old?.release()
        player = null
        playerIndex = -1
        readLoading = true
        readError = null
        try {
            val mp3 = withContext(Dispatchers.IO) {
                val f = File(context.cacheDir, "www_read_" + d.id + "_" + readIndex + ".mp3")
                if (!f.exists() || f.length() < 200L) {
                    EdgeTTS.synthesizeToMp3(section.title + ". " + section.text, edgeVoice, 0, f)
                }
                f
            }
            if (!readActive || !playing) return@LaunchedEffect
            val p = MediaPlayer()
            p.setDataSource(mp3.absolutePath)
            p.prepare()
            p.setOnCompletionListener {
                mainHandler.post {
                    if (readActive && playing) {
                        if (readIndex < sections.size - 1) {
                            readIndex = readIndex + 1
                        } else {
                            readActive = false
                            playing = false
                            readIndex = -1
                        }
                    }
                }
            }
            player = p
            playerIndex = readIndex
            p.start()
        } catch (e: Exception) {
            readError = "Vorlesen fehlgeschlagen: " + (e.message ?: "unbekannter Fehler")
            readActive = false
            playing = false
            readIndex = -1
        } finally {
            readLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(d.explanation.title) },
                navigationIcon = {
                    IconButton(onClick = { stopReading(); onClose() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Schließen")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            stopReading()
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
                        (if (d.explanation.source == "online")
                            "online (" + OnlineAnalyzer.label(OnlineAnalyzer.currentProvider(context)) + ")"
                        else
                            "lokal (auf dem Gerät)"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            // ---------- Vorlesen-Steuerung ----------
            if (sections.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(onClick = { startOrToggleReading() }) {
                                Icon(
                                    imageVector = if (readActive && playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    when {
                                        readActive && playing -> "Pause"
                                        readActive && !playing -> "Weiter"
                                        else -> "Vorlesen"
                                    }
                                )
                            }
                            if (readActive) {
                                OutlinedButton(onClick = { stopReading() }) {
                                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Stopp")
                                }
                            }
                        }
                        if (readLoading) {
                            Text(
                                "Online-Stimme wird geladen …",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        } else if (readActive && readIndex >= 0) {
                            Text(
                                "Liest vor: " + sections[readIndex].title +
                                    (if (useEdge) "  (Online-Stimme)" else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text(
                                "Liest die Erklärung Abschnitt für Abschnitt vor " +
                                    (if (useEdge) "mit der gewählten Online-Stimme." else "mit der gewählten Erklärbär-Stimme."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                        val errMsg = readError
                        if (errMsg != null) {
                            Text(
                                errMsg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
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

            sections.forEachIndexed { index, section ->
                SectionCard(
                    title = section.title,
                    text = section.text,
                    highlight = readActive && index == readIndex
                )
            }

            if (d.explanation.links.isNotEmpty()) {
                Text("Weiterführende Links", style = MaterialTheme.typography.titleMedium)
                d.explanation.links.forEach { link ->
                    OutlinedButton(
                        onClick = {
                            stopReading()
                            openUrl(context, link.url)
                        },
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

            // ---------- Optionale Online-Tiefenanalyse ----------
            if (d.explanation.source != "online" && OnlineAnalyzer.isConfigured(context)) {
                if (onlineRunning) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Tiefenanalyse läuft …", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    OutlinedButton(
                        onClick = { onlineError = null; showOnlineDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Tiefenanalyse online (" +
                                OnlineAnalyzer.label(OnlineAnalyzer.currentProvider(context)) +
                                ")"
                        )
                    }
                }
                onlineError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            Button(
                onClick = { stopReading(); onVideo() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Erklärvideo ansehen")
            }
            OutlinedButton(
                onClick = { stopReading(); showShareDialog = true },
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
            OutlinedButton(
                onClick = { stopReading(); showChatDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Chat,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Nachfragen")
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showChatDialog) {
        ChatDialog(context = context, d = d, scope = scope, onDismiss = { showChatDialog = false })
    }

    if (showShareDialog) {
        AlertDialog(
            onDismissRequest = { showShareDialog = false },
            title = { Text("Entdeckung teilen") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "Foto und Erklärung teilen über:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    listOf(
                        "WhatsApp" to "com.whatsapp",
                        "Instagram" to "com.instagram.android",
                        "Facebook" to "com.facebook.katana",
                        "Telegram" to "org.telegram.messenger",
                        "Signal" to "org.thoughtcrime.securesms",
                        "Snapchat" to "com.snapchat.android"
                    ).forEach { (name, pkg) ->
                        TextButton(
                            onClick = { showShareDialog = false; shareVia(context, d, pkg) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(name) }
                    }
                    TextButton(
                        onClick = { showShareDialog = false; shareViaEmail(context, d) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("E-Mail") }
                    TextButton(
                        onClick = { showShareDialog = false; shareViaSms(context, d) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("SMS") }
                    TextButton(
                        onClick = { showShareDialog = false; shareVia(context, d, null) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Mehr Apps …") }
                }
            },
            confirmButton = {
                TextButton(onClick = { showShareDialog = false }) { Text("Schließen") }
            }
        )
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

    if (showOnlineDialog) {
        AlertDialog(
            onDismissRequest = { showOnlineDialog = false },
            title = { Text("Online-Tiefenanalyse?") },
            text = {
                Text(
                    "Das Foto wird einmalig an " +
                        OnlineAnalyzer.label(OnlineAnalyzer.currentProvider(context)) +
                        " gesendet und mit deinem eigenen API-Schlüssel analysiert. " +
                        "Die ausführliche Online-Erklärung ersetzt dann die lokale."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showOnlineDialog = false
                        onlineRunning = true
                        onlineError = null
                        scope.launch {
                            try {
                                val config = OnlineAnalyzer.loadConfig(context)
                                if (config == null) {
                                    onlineError = "Kein KI-Anbieter konfiguriert. Prüfe die Einstellungen."
                                } else {
                                    val explanation = OnlineAnalyzer.analyze(
                                        listOf(d.imagePath) + d.extraPaths,
                                        config
                                    )
                                    val updated = d.copy(explanation = explanation)
                                    withContext(Dispatchers.IO) {
                                        DiscoveryStore.update(context, updated)
                                    }
                                    discovery = DiscoveryStore.get(context, d.id)
                                }
                            } catch (e: Exception) {
                                onlineError = "Online-Analyse fehlgeschlagen: " +
                                    (e.message ?: "unbekannter Fehler")
                            } finally {
                                onlineRunning = false
                            }
                        }
                    }
                ) { Text("Jetzt analysieren") }
            },
            dismissButton = {
                TextButton(onClick = { showOnlineDialog = false }) { Text("Abbrechen") }
            }
        )
    }
}

/** Eine thematische Karte mit Überschrift und Text; optional hervorgehoben, wenn sie gerade vorgelesen wird. */
@Composable
private fun SectionCard(title: String, text: String, highlight: Boolean = false) {
    if (text.isBlank()) return
    val container = if (highlight) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (highlight) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(12.dp)
                    )
                } else {
                    Modifier
                }
            ),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
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

/** Baut den Klartext der Entdeckung zum Teilen. */
private fun buildShareText(d: Discovery): String {
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
    return sb.toString()
}

/**
 * Teilt die Entdeckung als Foto + Text an eine bestimmte App (pkg != null)
 * oder über die System-Auswahl (pkg == null). Ist die Ziel-App nicht installiert,
 * fällt die App auf die System-Auswahl zurück.
 */
private fun shareVia(context: Context, d: Discovery, pkg: String?) {
    val text = buildShareText(d)
    val imageFile = File(d.imagePath)
    val hasImage = imageFile.exists()
    val imageUri = if (hasImage) {
        runCatching {
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", imageFile)
        }.getOrNull()
    } else {
        null
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = if (imageUri != null) "image/*" else "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, "Wer Wie Was: " + d.explanation.title)
        if (imageUri != null) {
            putExtra(Intent.EXTRA_STREAM, imageUri)
            clipData = ClipData.newRawUri("foto", imageUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (pkg != null) setPackage(pkg)
    }
    try {
        val target = if (pkg == null) {
            Intent.createChooser(send, "Entdeckung teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(target)
    } catch (e: ActivityNotFoundException) {
        // Ziel-App nicht installiert: System-Auswahl anbieten
        send.setPackage(null)
        runCatching {
            context.startActivity(
                Intent.createChooser(send, "Entdeckung teilen")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    } catch (e: Exception) {
        // Still ignorieren wie bisher
    }
}

/** Teilt die Entdeckung als E-Mail (Betreff, Text, Foto als Anhang). */
private fun shareViaEmail(context: Context, d: Discovery) {
    val text = buildShareText(d)
    val imageFile = File(d.imagePath)
    val imageUri = if (imageFile.exists()) {
        runCatching {
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", imageFile)
        }.getOrNull()
    } else {
        null
    }
    val mail = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
        putExtra(Intent.EXTRA_SUBJECT, "Wer Wie Was: " + d.explanation.title)
        putExtra(Intent.EXTRA_TEXT, text)
        if (imageUri != null) {
            putExtra(Intent.EXTRA_STREAM, imageUri)
            clipData = ClipData.newRawUri("foto", imageUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    runCatching {
        context.startActivity(mail.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Teilt die Entdeckung als SMS (Text). */
private fun shareViaSms(context: Context, d: Discovery) {
    val sms = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).apply {
        putExtra("sms_body", buildShareText(d))
    }
    runCatching {
        context.startActivity(sms.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Rückfrage-Dialog: Fragen zur Entdeckung an die KI stellen – per Tastatur oder
 * Spracheingabe (Spracherkennung). Der Chatverlauf bleibt im Dialog erhalten.
 */
@Composable
private fun ChatDialog(
    context: Context,
    d: Discovery,
    scope: CoroutineScope,
    onDismiss: () -> Unit
) {
    val chatHistory = remember { mutableStateListOf<Pair<String, String>>() }
    var question by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var listening by remember { mutableStateOf(false) }

    val recognizerAvailable = remember { SpeechRecognizer.isRecognitionAvailable(context) }
    val recognizer = remember {
        if (recognizerAvailable) SpeechRecognizer.createSpeechRecognizer(context) else null
    }
    DisposableEffect(Unit) {
        onDispose { recognizer?.destroy() }
    }

    fun startListening() {
        val rec = recognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Stelle deine Frage zur Entdeckung")
        }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() { }
            override fun onRmsChanged(rmsdB: Float) { }
            override fun onBufferReceived(buffer: ByteArray?) { }
            override fun onEndOfSpeech() { }
            override fun onError(errorCode: Int) {
                listening = false
                if (errorCode == SpeechRecognizer.ERROR_NO_MATCH ||
                    errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) {
                    error = "Nichts verstanden – bitte nochmal sprechen oder tippen."
                }
            }
            override fun onResults(results: Bundle?) {
                listening = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) question = text
            }
            override fun onPartialResults(partialResults: Bundle?) { }
            override fun onEvent(eventType: Int, params: Bundle?) { }
        })
        error = null
        rec.startListening(intent)
    }

    fun ask() {
        val q = question.trim()
        if (q.isEmpty() || running) return
        running = true
        error = null
        val history = chatHistory.toList()
        scope.launch {
            try {
                val answer = OnlineAnalyzer.followUp(context, d, q, history)
                chatHistory.add(q to answer)
                question = ""
            } catch (e: Exception) {
                error = e.message ?: "Die Anfrage ist fehlgeschlagen."
            } finally {
                running = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("Nachfragen") },
        text = {
            Column {
                Text(
                    "Stelle Fragen zu „" + d.explanation.title + "“ – " +
                        "die KI antwortet auf Basis der Erklärung.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                if (chatHistory.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(chatHistory) { (q, a) ->
                            Column {
                                Text(
                                    "Du: " + q,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(a, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Deine Frage …") },
                    maxLines = 3
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (recognizerAvailable) {
                        IconButton(
                            onClick = {
                                if (listening) recognizer?.stopListening() else startListening()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = "Frage sprechen",
                                tint = if (listening) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    LocalContentColor.current
                                }
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { ask() },
                        enabled = !running && question.isNotBlank()
                    ) {
                        if (running) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Fragen …")
                        } else {
                            Text("Fragen")
                        }
                    }
                }
                val err = error
                if (err != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { if (!running) onDismiss() }) { Text("Schließen") }
        }
    )
}




