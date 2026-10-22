package de.derdl1983.weriewas.util

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import de.derdl1983.weriewas.MainActivity
import de.derdl1983.weriewas.R
import org.json.JSONArray
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Globaler Zustand des Video-Exports: Compose-Zustand, damit jeder Screen –
 * auch nach dem erneuten Öffnen der App – denselben Fortschritt und dasselbe
 * Ergebnis sieht.
 */
object ExportBus {
    /** Läuft gerade ein Export? */
    var running by mutableStateOf(false)
    /** Gesamt-Fortschritt 0..1. */
    var frac by mutableStateOf(0f)
    /** Fortschritt als Text („42 %“). */
    var pctText by mutableStateOf("0 %")
    /** Ergebnis des letzten abgeschlossenen Exports. */
    var result by mutableStateOf<VideoExporter.ExportResult?>(null)
    /** Entdeckungs-ID, zu der das Ergebnis gehört. */
    var resultId by mutableStateOf<String?>(null)

    fun startRun(id: String) {
        running = true
        frac = 0f
        pctText = "0 %"
        result = null
        resultId = id
    }

    fun finishRun(id: String, r: VideoExporter.ExportResult) {
        running = false
        frac = 1f
        pctText = "100 %"
        result = r
        resultId = id
    }
}

/**
 * Erstellt das Erklärvideo als Vordergrund-Dienst: Der Export läuft weiter,
 * während die App im Hintergrund ist oder geschlossen wird, und zeigt seinen
 * Fortschritt als Benachrichtigung. Der sichtbare Zustand liegt in [ExportBus],
 * damit der Video-Screen auch nach dem Wiederöffnen der App den Fortschritt
 * und das Ergebnis anzeigt.
 */
class ExportService : Service() {

    companion object {
        private const val CHANNEL_ID = "video_export"
        private const val NOTIF_ID = 71

        /** Startet den Export-Dienst mit allen Einstellungen des Video-Screens. */
        fun start(
            context: Context,
            discoveryId: String,
            slidesJson: String,
            videoFormat: String,
            wantSrt: Boolean,
            burnedSubs: Boolean,
            wantMusic: Boolean,
            musicMood: String,
            speed: Float,
            useEdge: Boolean,
            edgeVoiceA: String,
            edgeVoiceB: String,
            sysVoiceNameA: String,
            sysVoiceNameB: String,
            speechLang: String
        ) {
            val intent = Intent(context, ExportService::class.java).apply {
                putExtra("discovery_id", discoveryId)
                putExtra("slides", slidesJson)
                putExtra("format", videoFormat)
                putExtra("want_srt", wantSrt)
                putExtra("burn_subs", burnedSubs)
                putExtra("music", wantMusic)
                putExtra("mood", musicMood)
                putExtra("speed", speed)
                putExtra("use_edge", useEdge)
                putExtra("edge_a", edgeVoiceA)
                putExtra("edge_b", edgeVoiceB)
                putExtra("sys_a", sysVoiceNameA)
                putExtra("sys_b", sysVoiceNameB)
                putExtra("sys_lang", speechLang)
            }
            context.startForegroundService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification((ExportBus.frac * 100).toInt()))
        val discoveryId = intent?.getStringExtra("discovery_id")
        if (intent == null || discoveryId.isNullOrBlank() || ExportBus.running) {
            // Ungültiger oder paralleler Start: einen laufenden Export nicht
            // stören, sonst sauber beenden.
            if (!ExportBus.running) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        ExportBus.startRun(discoveryId)
        thread(name = "video-export", priority = Thread.NORM_PRIORITY - 1) {
            runExport(discoveryId, intent)
        }
        return START_NOT_STICKY
    }

    private fun nm(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** Benachrichtigungen ab Android 13 nur mit erteilter Erlaubnis. */
    private fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun buildNotification(pct: Int = 0): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video-Erstellung",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Zeigt den Fortschritt, während ein Erklärvideo erstellt wird."
                setShowBadge(false)
            }
            nm().createNotificationChannel(channel)
        }
        val pending = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Wer Wie Was – Video wird erstellt")
            .setContentText(
                if (pct >= 100) "Fast fertig …"
                else "Fortschritt: $pct % – die App kann geschlossen werden."
            )
            .setProgress(100, pct.coerceIn(0, 100), false)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun buildDoneNotification(result: VideoExporter.ExportResult): Notification {
        val ok = result.shareUri != null
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(if (ok) "Video fertig 🎬" else "Video-Export fehlgeschlagen")
            .setContentText(result.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(result.message))
            .setAutoCancel(true)
            .build()
    }

    /** Baut aus dem JSON-Array die Szenenliste für den VideoExporter. */
    private fun parseSlides(json: String): List<VideoExporter.SlideData> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            VideoExporter.SlideData(
                o.optString("title"),
                o.optString("text"),
                o.optString("emoji"),
                o.optString("photo").ifBlank { null },
                o.optString("web").ifBlank { null },
                o.optString("credit").ifBlank { null }
            )
        }
    }

    private fun runExport(discoveryId: String, intent: Intent) {
        val appContext = applicationContext
        val manager = nm()
        var lastPct = -1
        fun report(p: Float) {
            val f = p.coerceIn(0f, 1f)
            ExportBus.frac = f
            val pct = (f * 100).toInt()
            ExportBus.pctText = pct.toString() + " %"
            if (pct != lastPct) {
                lastPct = pct
                if (canNotify()) {
                    runCatching { manager.notify(NOTIF_ID, buildNotification(pct)) }
                }
            }
        }
        var done: Notification? = null
        try {
            val slides = parseSlides(intent.getStringExtra("slides") ?: "[]")
            val speed = intent.getFloatExtra("speed", 1f)
            val useEdge = intent.getBooleanExtra("use_edge", false)
            val edgeA = intent.getStringExtra("edge_a") ?: ""
            val edgeB = intent.getStringExtra("edge_b") ?: ""
            val wantMusic = intent.getBooleanExtra("music", true)
            val mood = intent.getStringExtra("mood") ?: "neugierig"
            val format = intent.getStringExtra("format") ?: "portrait"
            val videoSize = when (format) {
                "landscape720" -> 1280 to 720
                "landscape1080" -> 1920 to 1080
                else -> 720 to 1280
            }
            // Gratis Edge-TTS-Stimmen vorbereiten (mit SSML-Pausen für
            // natürlichere Sprechpausen) – wie zuvor im Video-Screen.
            // Der Cache-Dateiname enthält die Stimme, damit nach einem
            // Sprachwechsel im KI-Profil keine alte Sprachversion läuft.
            val preAudio: List<File?>? = if (useEdge) {
                val vA = if (edgeA.isNotBlank()) edgeA else edgeB
                val vB = if (edgeB.isNotBlank()) edgeB else edgeA
                val rp = ((speed - 1f) * 100f).toInt().coerceIn(-50, 100)
                slides.mapIndexed { i, s ->
                    try {
                        val voice = if (i % 2 == 1) vB else vA
                        val tag = voice.hashCode()
                        val mp3 = File(cacheDir, "www_exps_" + discoveryId + "_" + i + "_" + rp + "_" + tag + ".mp3")
                        val wav = File(cacheDir, "www_exps_" + discoveryId + "_" + i + "_" + rp + "_" + tag + ".wav")
                        if (!mp3.exists() || mp3.length() < 200L) {
                            EdgeTTS.synthesizeToMp3(
                                s.title + ". " + s.text,
                                voice,
                                rp,
                                mp3,
                                ssml = true
                            )
                        }
                        if (!wav.exists() || wav.lastModified() < mp3.lastModified()) {
                            EdgeTTS.mp3ToWav(mp3, wav)
                        }
                        wav
                    } catch (e: Exception) {
                        null
                    }
                }
            } else {
                null
            }
            // Antwort-Sprache des KI-Profils: Die System-Stimmen folgen ihr
            // automatisch; sind dafür keine Stimmen installiert, greifen die
            // deutschen Stimmen als Fallback.
            val speechLang = (intent.getStringExtra("sys_lang") ?: "de").ifBlank { "de" }
            // System-Sprachausgabe: Stimmen aus den Einstellungen wiederfinden
            var voiceA: Voice? = null
            var voiceB: Voice? = null
            if (preAudio == null || preAudio.any { it == null }) {
                val latch = CountDownLatch(1)
                var tts: TextToSpeech? = null
                try {
                    tts = TextToSpeech(appContext) { _ -> latch.countDown() }
                    if (latch.await(15, TimeUnit.SECONDS)) {
                        val allVoices = try {
                            tts.voices ?: emptyList<Voice>()
                        } catch (e: Exception) {
                            emptyList<Voice>()
                        }
                        val langVoices = allVoices.filter { it.locale.language == speechLang }
                        val sorted = (if (langVoices.isNotEmpty()) langVoices else allVoices.filter { it.locale.language == "de" })
                            .sortedBy { it.name }
                        if (sorted.isNotEmpty()) {
                            val nameA = intent.getStringExtra("sys_a") ?: ""
                            val nameB = intent.getStringExtra("sys_b") ?: ""
                            voiceA = sorted.firstOrNull { it.name == nameA } ?: sorted[0]
                            voiceB = sorted.firstOrNull { it.name == nameB } ?: sorted.getOrNull(1)
                        }
                    }
                } catch (e: Exception) {
                    // Ohne System-Stimmen exportiert der VideoExporter ohne Ton
                } finally {
                    runCatching { tts?.stop() }
                    runCatching { tts?.shutdown() }
                }
            }
            val result = VideoExporter.export(
                appContext,
                slides,
                voiceA,
                voiceB,
                speed,
                preAudio,
                videoSize.first,
                videoSize.second,
                intent.getBooleanExtra("want_srt", true),
                intent.getBooleanExtra("burn_subs", false),
                wantMusic,
                mood,
                discoveryId,
                speechLang
            ) { p -> report(p) }
            ExportBus.finishRun(discoveryId, result)
            done = buildDoneNotification(result)
        } catch (t: Throwable) {
            de.derdl1983.weriewas.WerWieWasApp.logExportError(appContext, t)
            val result = VideoExporter.ExportResult(
                "Export fehlgeschlagen: " + (t.message ?: "unbekannter Fehler") +
                    " – Details im Fehlerbericht (Einstellungen → Daten → Fehlerbericht teilen).",
                null
            )
            ExportBus.finishRun(discoveryId, result)
            done = buildDoneNotification(result)
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (done != null && canNotify()) {
                runCatching { manager.notify(NOTIF_ID + 1, done) }
            }
            stopSelf()
        }
    }
}
