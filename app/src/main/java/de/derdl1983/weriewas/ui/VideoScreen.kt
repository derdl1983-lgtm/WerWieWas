package de.derdl1983.weriewas.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.derdl1983.weriewas.data.Discovery
import de.derdl1983.weriewas.data.DiscoveryStore
import de.derdl1983.weriewas.util.EdgeTTS
import de.derdl1983.weriewas.util.ExportBus
import de.derdl1983.weriewas.util.ExportService
import de.derdl1983.weriewas.util.Images
import de.derdl1983.weriewas.util.VideoExporter
import de.derdl1983.weriewas.util.WebImages
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Eine Textkarte des Erklärvideos mit passendem Themenbild-Zeichen. */
private data class Slide(val title: String, val text: String, val emoji: String, val showPhoto: Boolean = false)

private data class SlidePalette(val top: Color, val bottom: Color)

private val slidePalettes = listOf(
    SlidePalette(Color(0xFF1A237E), Color(0xFF3949AB)),
    SlidePalette(Color(0xFF00695C), Color(0xFF00897B)),
    SlidePalette(Color(0xFF4A148C), Color(0xFF6A1B9A)),
    SlidePalette(Color(0xFF0277BD), Color(0xFF0288D1)),
    SlidePalette(Color(0xFF283593), Color(0xFF3F51B5)),
    SlidePalette(Color(0xFF33691E), Color(0xFF558B2F)),
    SlidePalette(Color(0xFFBF360C), Color(0xFFD84315)),
    SlidePalette(Color(0xFF37474F), Color(0xFF455A64))
)

/** Wandelt die Geschwindigkeit (0.75x-2x) in eine Edge-TTS-Prozentangabe um. */
private fun ratePercent(speed: Float): Int =
    ((speed - 1f) * 100f).toInt().coerceIn(-50, 100)

/** Baut die Seiten des Erklärvideos aus einer Entdeckung. */
private fun buildSlides(d: Discovery): List<Slide> = listOf(
    Slide("Das Objekt", d.explanation.title, "📸", showPhoto = true),
    Slide("Was ist das?", d.explanation.summary, "❓"),
    Slide("Wie funktioniert es?", d.explanation.howItWorks, "⚙️"),
    Slide("Wofür wird es verwendet?", d.explanation.usage, "🎯"),
    Slide("Praktische Tipps", d.explanation.practical, "💡"),
    Slide("Interessante Fakten", d.explanation.facts.joinToString("\n") { "• " + it }, "🌟"),
    Slide(
        "Sicherheit",
        d.explanation.safety.ifBlank {
            "Für dieses Objekt sind keine besonderen Sicherheitshinweise nötig."
        },
        "⚠️"
    ),
    Slide(
        "Weiter lernen",
        "Unter dem Ergebnis findest du echte Links zu Wikipedia, YouTube und weiteren Quellen. " +
            "Viel Spaß beim Entdecken!",
        "🎓"
    )
)

/** Erklärvideo: Foto, Themenbilder und Textkarten mit mehreren Stimmen (System oder Edge-TTS), Geschwindigkeit und MP4-Download. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoScreen(
    discoveryId: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val discovery = remember(discoveryId) { DiscoveryStore.get(context, discoveryId) }

    if (discovery == null) {
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

    val slides = remember(discovery.id) { buildSlides(discovery) }
    val bitmap = remember(discovery.id) { Images.loadThumbnail(discovery.imagePath, 720) }

    // Passende, frei lizenzierte Online-Themenbilder je Szene (falls online verfügbar)
    val webImages = remember(discovery.id) { mutableStateOf<List<WebImages.WebImage?>?>(null) }
    LaunchedEffect(discovery.id) {
        webImages.value = null
        val queries = WebImages.queriesForSlides(
            discovery.explanation.title,
            slides.map { it.title },
            slides.map { it.text }
        )
        webImages.value = WebImages.findByQueries(context, queries)
    }

    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    val speedLabels = remember {
        mapOf(0.75f to "0,75x", 1f to "1x", 1.25f to "1,25x", 1.5f to "1,5x", 2f to "2x")
    }

    // Gratis Online-Stimmen (Edge-TTS) aus den Einstellungen
    val edgeA = remember { prefs.getString("edge_voice_a", "") ?: "" }
    val edgeB = remember { prefs.getString("edge_voice_b", "") ?: "" }
    val useEdge = edgeA.isNotBlank() || edgeB.isNotBlank()

    var page by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(true) }
    var ttsReady by remember { mutableStateOf(false) }
    var ttsFailed by remember { mutableStateOf(false) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var voiceA by remember { mutableStateOf<Voice?>(null) }
    var voiceB by remember { mutableStateOf<Voice?>(null) }
    var dualVoices by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1f) }
    // Export läuft in einem Vordergrund-Dienst weiter – auch bei geschlossener App.
    // Fortschritt und Ergebnis liegen global im ExportBus und überleben das
    // Verlassen und erneute Öffnen des Screens.
    val exporting = ExportBus.running
    val exportFrac = ExportBus.frac
    val exportProgress = ExportBus.pctText
    val busResult = if (ExportBus.resultId == discovery.id) ExportBus.result else null
    val exportMessage = busResult?.let { r ->
        if (r.shareUri != null) r.message + " Das Video liegt dauerhaft im Video-Archiv auf der Startseite." else r.message
    }
    val exportedUri = busResult?.shareUri
    val exportedSrt = busResult?.srtUri
    val exportedThumb = busResult?.thumbUri
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var videoFormat by rememberSaveable {
        mutableStateOf(prefs.getString("video_format", "portrait") ?: "portrait")
    }
    var wantSrt by rememberSaveable { mutableStateOf(prefs.getBoolean("video_srt", true)) }
    var burnedSubs by rememberSaveable { mutableStateOf(prefs.getBoolean("video_burn_subs", false)) }
    var wantMusic by rememberSaveable { mutableStateOf(prefs.getBoolean("video_music", true)) }
    var musicMood by rememberSaveable {
        mutableStateOf(prefs.getString("music_mood", "neugierig") ?: "neugierig")
    }
    val formatLabels = listOf(
        "portrait" to "Hochformat",
        "landscape720" to "Quer 720p",
        "landscape1080" to "Quer 1080p"
    )
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var edgeLoading by remember { mutableStateOf(false) }
    var edgeError by remember { mutableStateOf<String?>(null) }

    fun speakCurrent(engine: TextToSpeech) {
        val slide = slides[page]
        engine.setSpeechRate(speed * 0.95f)
        if (dualVoices) {
            val second = page % 2 == 1
            val va = voiceA
            val vb = voiceB
            if (second) {
                if (vb != null) {
                    engine.setVoice(vb)
                    engine.setPitch(1f)
                } else {
                    if (va != null) engine.setVoice(va)
                    engine.setPitch(0.8f)
                }
            } else {
                if (va != null) engine.setVoice(va)
                engine.setPitch(1f)
            }
        } else {
            engine.setPitch(1f)
        }
        engine.speak(
            slide.title + ". " + slide.text,
            TextToSpeech.QUEUE_FLUSH,
            Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "slide_" + page)
            },
            "slide_" + page
        )
    }

    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        engine = try {
            TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.let { ttsEngine ->
                    ttsEngine.setLanguage(Locale.GERMANY)
                    val germanVoices = try {
                        ttsEngine.voices?.filter { it.locale.language == "de" } ?: emptyList()
                    } catch (e: Exception) {
                        emptyList<Voice>()
                    }
                    val sorted = germanVoices.sortedBy { it.name }
                    if (sorted.isNotEmpty()) {
                        val nameA = prefs.getString("tts_voice_a", null)
                        val nameB = prefs.getString("tts_voice_b", null)
                        voiceA = sorted.firstOrNull { it.name == nameA } ?: sorted[0]
                        voiceB = sorted.firstOrNull { it.name == nameB } ?: sorted.getOrNull(1)
                        dualVoices = sorted.size > 1
                    }
                    ttsEngine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onDone(utteranceId: String?) {
                            if (utteranceId?.startsWith("slide_") == true) {
                                mainHandler.post {
                                    if (playing && page < slides.size - 1) {
                                        page += 1
                                    } else if (page >= slides.size - 1) {
                                        playing = false
                                    }
                                }
                            }
                        }

                        override fun onError(utteranceId: String?) {
                            mainHandler.post { playing = false }
                        }

                        override fun onStart(utteranceId: String?) {
                        }
                    })
                    ttsReady = true
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
            ttsReady = false
        }
    }

    // Zeitüberschreitung, falls die System-Sprachausgabe nie bereit wird
    LaunchedEffect(ttsReady) {
        if (!useEdge && !ttsReady) {
            delay(20000)
            if (!ttsReady) ttsFailed = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            player?.release()
            player = null
        }
    }

    LaunchedEffect(ttsReady, playing, page, speed, dualVoices) {
        val engine = tts
        if (engine == null || !ttsReady) return@LaunchedEffect
        if (useEdge) return@LaunchedEffect
        if (playing) {
            speakCurrent(engine)
        } else {
            engine.stop()
        }
    }

    // Wiedergabe über gratis Edge-TTS-Online-Stimmen
    LaunchedEffect(useEdge, playing, page, speed) {
        if (!useEdge) return@LaunchedEffect
        val old = player
        old?.stop()
        old?.release()
        player = null
        if (!playing) return@LaunchedEffect
        val slide = slides[page]
        val voice = if (page % 2 == 1) {
            if (edgeB.isNotBlank()) edgeB else edgeA
        } else {
            if (edgeA.isNotBlank()) edgeA else edgeB
        }
        val rp = ratePercent(speed)
        edgeLoading = true
        edgeError = null
        try {
            val mp3 = withContext(Dispatchers.IO) {
                val f = File(context.cacheDir, "www_edge_s_" + discovery.id + "_" + page + "_" + rp + ".mp3")
                if (!f.exists() || f.length() < 200L) {
                    EdgeTTS.synthesizeToMp3(slide.title + ". " + slide.text, voice, rp, f, ssml = true)
                }
                f
            }
            if (playing) {
                val p = MediaPlayer()
                p.setDataSource(mp3.absolutePath)
                p.prepare()
                p.setOnCompletionListener {
                    if (playing && page < slides.size - 1) {
                        page += 1
                    } else {
                        playing = false
                    }
                }
                player = p
                p.start()
            }
        } catch (e: Exception) {
            edgeError = "Online-Stimme nicht erreichbar: " + (e.message ?: "unbekannter Fehler")
            playing = false
        } finally {
            edgeLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Erklärvideo") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Schließen")
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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (!ttsReady && !useEdge) {
                if (ttsFailed) {
                    Text(
                        "Die Sprachausgabe dieses Geräts ist nicht verfügbar. " +
                            "Installiere eine Sprachausgabe (z. B. Google-Sprachausgabe) " +
                            "oder wähle in den Einstellungen eine kostenlose Online-Stimme.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = onClose) { Text("Zurück") }
                } else {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Sprachausgabe wird vorbereitet …",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
                return@Column
            }

            val slide = slides[page]
            val palette = slidePalettes[page % slidePalettes.size]

            if (slide.showPhoto && bitmap != null) {
                Card(shape = RoundedCornerShape(16.dp)) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Foto der Entdeckung",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        contentScale = ContentScale.Crop
                    )
                }
                Spacer(Modifier.height(20.dp))
            } else {
                // Online gefundenes Themenbild mit Quellenangabe (Videotext-Stil)
                val webImage = webImages.value?.getOrNull(page)
                if (webImage != null) {
                    val webBmp = Images.loadThumbnail(webImage.filePath, 900)
                    if (webBmp != null) {
                        Card(shape = RoundedCornerShape(16.dp)) {
                            Image(
                                bitmap = webBmp.asImageBitmap(),
                                contentDescription = "Themenbild (frei lizenziert)",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Text(
                            text = "Bild: " + webImage.credit,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }

            if (slide.emoji.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .background(
                            brush = Brush.linearGradient(listOf(palette.top, palette.bottom)),
                            shape = RoundedCornerShape(28.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(slide.emoji, fontSize = 64.sp)
                }
                Spacer(Modifier.height(20.dp))
            }

            Text(
                text = slide.title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = slide.text,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))

            LinearProgressIndicator(
                progress = { (page + 1f) / slides.size },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Szene " + (page + 1) + " von " + slides.size,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { if (page > 0) page -= 1 },
                    enabled = page > 0
                ) { Text("⏮") }
                Slider(
                    value = page.toFloat(),
                    onValueChange = { page = it.toInt().coerceIn(0, slides.size - 1) },
                    valueRange = 0f..(slides.size - 1).toFloat(),
                    steps = (slides.size - 2).coerceAtLeast(0),
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = { if (page < slides.size - 1) page += 1 },
                    enabled = page < slides.size - 1
                ) { Text("⏭") }
            }
            Spacer(Modifier.height(12.dp))

            if (edgeLoading) {
                Text(
                    "Online-Stimme wird geladen …",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(12.dp))
            }
            val edgeErrMsg = edgeError
            if (edgeErrMsg != null) {
                Text(
                    edgeErrMsg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        page = 0
                        playing = true
                    }
                ) {
                    Icon(Icons.Filled.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Neu starten")
                }
                Button(
                    onClick = { playing = !playing }
                ) {
                    Icon(
                        imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(if (playing) "Pause" else "Weiter")
                }
            }
            Spacer(Modifier.height(16.dp))

            if (useEdge) {
                Text(
                    text = "Online-Stimmen aktiv (Edge-TTS, gratis, ohne API-Schlüssel): Moderator " +
                        EdgeTTS.labelFor(if (edgeA.isNotBlank()) edgeA else edgeB) +
                        ", Erklärbär " + EdgeTTS.labelFor(if (edgeB.isNotBlank()) edgeB else edgeA) +
                        ". Benötigt Internet.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            } else {
                OutlinedButton(
                    onClick = {
                        dualVoices = !dualVoices
                    },
                    colors = if (dualVoices) {
                        ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    }
                ) {
                    Text(if (dualVoices) "Zwei Stimmen: abwechselnd" else "Zwei Stimmen: aus")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Moderator und Erklärbär wechseln sich pro Szene ab. " +
                        "Die Stimmen kannst du in den Einstellungen wählen." +
                        (if (voiceB == null) " Auf diesem Gerät ist nur eine deutsche Stimme installiert – die zweite wird dann über die Tonhöhe simuliert." else ""),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            Spacer(Modifier.height(16.dp))

            Text(
                "Geschwindigkeit",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                speedLabels.entries.forEach { entry ->
                    OutlinedButton(
                        onClick = { speed = entry.key },
                        colors = if (speed == entry.key) {
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        }
                    ) {
                        Text(entry.value)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            Text(
                "Videoformat",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                formatLabels.forEach { (key, label) ->
                    OutlinedButton(
                        onClick = {
                            videoFormat = key
                            prefs.edit().putString("video_format", key).apply()
                        },
                        colors = if (videoFormat == key) {
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        }
                    ) {
                        Text(label)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Switch(
                    checked = wantMusic,
                    onCheckedChange = {
                        wantMusic = it
                        prefs.edit().putBoolean("video_music", it).apply()
                    }
                )
                Text("Hintergrundmusik (dezent, automatisch leiser unter der Stimme)", style = MaterialTheme.typography.bodyMedium)
            }
            if (wantMusic) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Musik-Stimmung",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(
                        "neugierig" to "Neugierig",
                        "froehlich" to "Fröhlich",
                        "ruhig" to "Ruhig"
                    ).forEach { (key, label) ->
                        OutlinedButton(
                            onClick = {
                                musicMood = key
                                prefs.edit().putString("music_mood", key).apply()
                            },
                            colors = if (musicMood == key) {
                                ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            } else {
                                ButtonDefaults.outlinedButtonColors()
                            }
                        ) {
                            Text(label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Switch(
                    checked = burnedSubs,
                    onCheckedChange = {
                        burnedSubs = it
                        prefs.edit().putBoolean("video_burn_subs", it).apply()
                    }
                )
                Text("Untertitel ins Video einbrennen (TikTok-Stil)", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Switch(
                    checked = wantSrt,
                    onCheckedChange = {
                        wantSrt = it
                        prefs.edit().putBoolean("video_srt", it).apply()
                    }
                )
                Text("Untertitel zusätzlich als SRT-Datei speichern", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(20.dp))

            if (ttsFailed && !useEdge) {
                Text(
                    "Keine Sprachausgabe auf diesem Gerät gefunden – das Video wird ohne Ton exportiert. " +
                        "Für natürliche Stimmen einmal kostenlos Edge-TTS in den Einstellungen einrichten.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(12.dp))
            }
            Button(
                onClick = {
                    // Export startet als Vordergrund-Dienst und läuft weiter,
                    // auch wenn die App geschlossen wird.
                    playing = false
                    if (Build.VERSION.SDK_INT >= 33 &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    val arr = JSONArray()
                    val webList = webImages.value
                    slides.forEachIndexed { i, s ->
                        val wi = if (s.showPhoto) null else webList?.getOrNull(i)
                        arr.put(
                            JSONObject()
                                .put("title", s.title)
                                .put("text", s.text)
                                .put("emoji", s.emoji)
                                .put("photo", if (s.showPhoto) (discovery.imagePath ?: "") else "")
                                .put("web", wi?.filePath ?: "")
                                .put("credit", wi?.credit ?: "")
                        )
                    }
                    ExportService.start(
                        context,
                        discovery.id,
                        arr.toString(),
                        videoFormat,
                        wantSrt,
                        burnedSubs,
                        wantMusic,
                        musicMood,
                        speed,
                        useEdge,
                        edgeA,
                        edgeB,
                        voiceA?.name ?: "",
                        voiceB?.name ?: ""
                    )
                },
                enabled = !exporting
            ) {
                if (exporting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text("Video wird erstellt …")
                } else {
                    Text("Als Video speichern (MP4)")
                }
            }
            if (exporting) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { exportFrac },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Fortschritt: " + (if (exportProgress.isBlank()) "0 %" else exportProgress) +
                        " – Text, Szenen, Intro & Outro werden eingebaut. Die App kann geschlossen werden, der Export läuft im Hintergrund weiter.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            val message = exportMessage
            if (message != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            val vUri = exportedUri
            if (vUri != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Video hochladen & teilen:",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { shareVideo(context, vUri, "com.google.android.youtube") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Auf YouTube hochladen") }
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = { shareVideo(context, vUri, "com.zhiliaoapp.musically") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Auf TikTok hochladen") }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { shareVideo(context, vUri, "com.instagram.android") },
                        modifier = Modifier.weight(1f)
                    ) { Text("Instagram") }
                    OutlinedButton(
                        onClick = { shareVideo(context, vUri, "com.whatsapp") },
                        modifier = Modifier.weight(1f)
                    ) { Text("WhatsApp") }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { shareVideo(context, vUri, "org.telegram.messenger") },
                        modifier = Modifier.weight(1f)
                    ) { Text("Telegram") }
                    OutlinedButton(
                        onClick = { shareVideo(context, vUri, "com.facebook.katana") },
                        modifier = Modifier.weight(1f)
                    ) { Text("Facebook") }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { shareVideo(context, vUri, null) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Video teilen … (alle Apps)") }
                val tUri = exportedThumb
                if (tUri != null) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { shareThumb(context, tUri) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Thumbnail (Cover) teilen …") }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Das automatisch erstellte Cover (Bilder/WerWieWas) eignet sich als Thumbnail für YouTube, TikTok & Instagram.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "YouTube und TikTok öffnen ihren Upload mit dem Video als Entwurf – " +
                        "Titel, Beschreibung und Veröffentlichen legst du dort fest.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                val sUri = exportedSrt
                if (sUri != null) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { shareSrt(context, sUri) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Untertitel (SRT) teilen") }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Die Untertitel-Datei liegt im Download-Ordner und eignet sich " +
                            "für YouTube-Uploads und barrierefreie Untertitel.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Übergibt die exportierte Untertiteldatei (SRT) an eine Ziel-App (z. B. YouTube-Upload). */
private fun shareSrt(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Wer Wie Was – Untertitel")
        clipData = ClipData.newRawUri("srt", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(send, "Untertitel teilen")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Übergibt das automatisch erstellte Cover-Bild (Thumbnail) an eine Ziel-App (z. B. YouTube-Upload). */
private fun shareThumb(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Wer Wie Was – Video-Cover")
        clipData = ClipData.newRawUri("cover", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(send, "Cover teilen")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Übergibt das exportierte Video an eine Ziel-App (z. B. YouTube, TikTok) oder die System-Auswahl. */
private fun shareVideo(context: Context, uri: Uri, pkg: String?) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Wer Wie Was – Erklärvideo")
        clipData = ClipData.newRawUri("video", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (pkg != null) setPackage(pkg)
    }
    // Installations-Check: Nicht installierte Ziel-Apps sofort abfangen,
    // statt den Start später crashen zu lassen (Fallback: System-Auswahl).
    if (pkg != null) {
        val installed = try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: Exception) {
            false
        }
        if (!installed) {
            send.setPackage(null)
            Toast.makeText(context, "Ziel-App nicht installiert – System-Auswahl wird geöffnet.", Toast.LENGTH_SHORT).show()
        }
    }
    try {
        context.startActivity(send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // Ziel-App nicht installiert: System-Auswahl anbieten
        send.setPackage(null)
        Toast.makeText(context, "Ziel-App nicht gefunden – bitte aus der Auswahl wählen.", Toast.LENGTH_SHORT).show()
        runCatching {
            context.startActivity(
                Intent.createChooser(send, "Video teilen")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    } catch (e: Exception) {
        // Still ignorieren
    }
}



