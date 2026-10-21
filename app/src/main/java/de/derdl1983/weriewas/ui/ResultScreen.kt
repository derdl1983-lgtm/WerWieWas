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
                    e.setLanguage(Locale.GERMANY)
                    val germanVoices = try {
                        e.voices?.filter { it.locale.language == "de" } ?: emptyList()
                    } catch (ex: Exception) {
                        emptyList<Voice>()
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
