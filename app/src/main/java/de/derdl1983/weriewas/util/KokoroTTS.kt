package de.derdl1983.weriewas.util

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Gratis Offline-Sprachausgabe mit der Open-Source-Stimme „Victoria"
 * (Kokoro-82M, int8, deutsch) über die sherpa-onnx-Engine:
 *
 *  - funktioniert komplett ohne Internet – kein API-Schlüssel, keine Drosselung
 *    (im Unterschied zu Edge-TTS, das Microsoft stark bewacht)
 *  - wird einmalig heruntergeladen (~124 MB), danach dauerhaft auf dem Gerät
 *  - Modell: Benjamin-Wegener/kokoro-german-victoria-onnx-int8 (Apache-2.0),
 *    ausgebaut für sherpa-onnx; Engine: sherpa-onnx 1.13.8
 *    (linkt statisch espeak-ng, GPL-3.0 – Credits siehe Repo-README)
 *
 * Die Dateien liegen unter filesDir/kokoro. Downloads werden atomar
 * (.part → endgültiger Name) und mit SHA-256-Prüfung durchgeführt;
 * teilt Downloads mit sich selbst, so dass ein Abbruch jederzeit sicher ist.
 */
object KokoroTTS {

    /** Synthese-Ergebnis: 16-Bit-PCM (mono) und zugehörige Abtastrate. */
    data class KokoroAudio(val pcm: ByteArray, val sampleRate: Int)

    // Download-Quellen (Version + Prüfsummen festgenagelt, damit ein
    // ausgetauschtes Modell nicht unbemerkt in die App gelangt).
    private const val URL_MODEL =
        "https://huggingface.co/Benjamin-Wegener/kokoro-german-victoria-onnx-int8/resolve/main/model.int8.onnx"
    private const val URL_VOICES =
        "https://huggingface.co/Benjamin-Wegener/kokoro-german-victoria-onnx-int8/resolve/main/voices.bin"
    private const val URL_TOKENS =
        "https://huggingface.co/Benjamin-Wegener/kokoro-german-victoria-onnx-int8/resolve/main/tokens.txt"
    private const val URL_ESPEAK =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/espeak-ng-data.tar.bz2"

    private const val MODEL_BYTES = 114163026L
    private const val MODEL_SHA = "19fb607aaa7ee9ce4bc3b708c9c4c3defa24786211e3d58bc4d2ad922b3890dd"
    private const val VOICES_BYTES = 522240L
    private const val VOICES_SHA = "12f217e95cd8bed185f1e0571cfa1fe12d2d4b86902855ec440a9272f79e221a"
    private const val TOKENS_BYTES = 687L
    private const val ESPEAK_SHA = "4135ccf82e1f40613491c0874d4945ae9e9c7840933d8e25a6f9e003d9ebf533"

    /** Die native Engine ist teuer im Speicher: lazy laden und nach Gebrauch freigeben. */
    @Volatile
    private var engine: OfflineTts? = null

    fun dir(context: Context): File = File(context.filesDir, "kokoro")

    /** Liegt die Offline-Stimme vollständig heruntergeladen auf dem Gerät? */
    fun isDownloaded(context: Context): Boolean {
        val d = dir(context)
        val model = File(d, "model.int8.onnx")
        val voices = File(d, "voices.bin")
        val tokens = File(d, "tokens.txt")
        val data = espeakDataDir(context)
        return model.length() == MODEL_BYTES &&
            voices.length() == VOICES_BYTES &&
            tokens.length() == TOKENS_BYTES &&
            data != null && (data.listFiles()?.size ?: 0) > 0
    }

    /** Verzeichnis der espeak-ng-Aussprache-Daten (Lage hängt vom Tar-Aufbau ab). */
    fun espeakDataDir(context: Context): File? {
        val d = dir(context)
        val nested = File(d, "espeak-ng-data")
        if (nested.isDirectory) return nested
        // Fallback: die Tar-Einträge lagen direkt im Zielordner (z. B. „de/…“)
        return if (d.listFiles()?.any { it.isDirectory } == true) d else null
    }

    /** Gesamtgröße des Downloads in MB – für die Anzeige vor dem Herunterladen. */
    fun downloadSizeMb(): Int = ((MODEL_BYTES + VOICES_BYTES + TOKENS_BYTES + 9_500_000L) / 1_000_000L).toInt()

    /**
     * Lädt die Offline-Stimme einmalig herunter (blockierend, nur außerhalb
     * des Haupt-Threads aufrufen). onProgress erhält 0..1 und eine kurze
     * Statusmeldung für die Anzeige. Bereits vollständig vorhandene Teile
     * werden übersprungen – ein abgebrochener Download kann einfach neu
     * gestartet werden.
     */
    fun download(context: Context, onProgress: (Float, String) -> Unit) {
        if (isDownloaded(context)) {
            onProgress(1f, "Offline-Stimme ist bereits vorhanden.")
            return
        }
        val d = dir(context)
        if (!d.exists()) d.mkdirs()
        onProgress(0.01f, "Download wird vorbereitet …")
        fetch(context, URL_TOKENS, File(d, "tokens.txt"), TOKENS_BYTES, null, 0.01f, 0.02f, onProgress, "Sprachbausteine …")
        fetch(context, URL_VOICES, File(d, "voices.bin"), VOICES_BYTES, VOICES_SHA, 0.02f, 0.04f, onProgress, "Stimmprofil (Victoria) …")
        // espeak-ng-Aussprache-Daten: komprimiertes Tar-Archiv entpacken
        val tar = File(context.cacheDir, "kokoro_espeak.tar.bz2")
        fetch(context, URL_ESPEAK, tar, null, ESPEAK_SHA, 0.04f, 0.08f, onProgress, "Aussprache-Regeln …")
        extractTarBz2(tar, d)
        runCatching { tar.delete() }
        // Das Sprachmodell ist der große Teil (~114 MB) – Smooth-Fortschritt.
        fetch(context, URL_MODEL, File(d, "model.int8.onnx"), MODEL_BYTES, MODEL_SHA, 0.08f, 0.99f, onProgress, "KI-Sprachmodell (Victoria) …")
        onProgress(1f, "Offline-Stimme ist bereit.")
    }

    /**
     * Lädt eine Datei mit Prüfsumme und atomarem Umbenennen. Erwartete Größe
     * bekannt → Fortschritt innerhalb from..to; sonst orientiert an der
     * Content-Length der Server-Antwort.
     */
    private fun fetch(
        context: Context,
        url: String,
        out: File,
        expectedBytes: Long?,
        sha256: String?,
        from: Float,
        to: Float,
        onProgress: (Float, String) -> Unit,
        label: String
    ) {
        if (out.exists() && out.length() > 0 && (expectedBytes == null || out.length() == expectedBytes)) {
            return // Teil liegt bereits vollständig vor (Downloads sind atomar)
        }
        val part = File(out.parentFile, out.name + ".part")
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        try {
            if (!response.isSuccessful) {
                throw IOException("Server antwortete mit HTTP " + response.code + " (" + label.trimEnd(' ', '…', '.') + ")")
            }
            val body = response.body ?: throw IOException("Leere Server-Antwort beim Download.")
            val total = expectedBytes ?: (if (body.contentLength() > 0) body.contentLength() else -1L)
            val md = sha256?.let { MessageDigest.getInstance("SHA-256") }
            var read = 0L
            FileOutputStream(part).use { sink ->
                val src = body.byteStream()
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    md?.update(buf, 0, n)
                    read += n
                    if (total > 0) {
                        val f = (from + (to - from) * (read.toDouble() / total)).toFloat().coerceIn(from, to)
                        onProgress(f, label)
                    } else {
                        onProgress(from, label)
                    }
                }
            }
            if (expectedBytes != null && read != expectedBytes) {
                runCatching { part.delete() }
                throw IOException("Download unvollständig (" + read + " von " + expectedBytes + " Byte)")
            }
            if (md != null) {
                val hex = md.digest().joinToString("") { "%02x".format(it) }
                if (!hex.equals(sha256, ignoreCase = true)) {
                    runCatching { part.delete() }
                    throw IOException("Prüfsumme stimmt nicht überein – Download beschädigt. Bitte erneut versuchen.")
                }
            }
            if (out.exists()) out.delete()
            if (!part.renameTo(out)) {
                part.copyTo(out, overwrite = true)
                runCatching { part.delete() }
            }
        } finally {
            response.close()
        }
    }

    /** Entpackt das espeak-ng-Tar-Archiv (bzip2) in das Zielverzeichnis. */
    private fun extractTarBz2(tar: File, destDir: File) {
        try {
            TarArchiveInputStream(BZip2CompressorInputStream(tar.inputStream().buffered())).use { tin ->
                var entry = tin.nextTarEntry
                while (entry != null) {
                    val name = entry.name
                    // Schutz vor Pfad-Flucht („../“) in Archiv-Einträgen
                    if (!name.contains("..")) {
                        val target = File(destDir, name)
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { sink -> tin.copyTo(sink) }
                        }
                    }
                    entry = tin.nextTarEntry
                }
            }
        } catch (e: Exception) {
            throw IOException("Aussprache-Daten konnten nicht entpackt werden: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    /**
     * Synthetisiert einen Text mit der Offline-Stimme und schreibt ihn als
     * 16-Bit-PCM-WAV (mono) in outWav. Liegt die Datei schon im Cache, wird
     * sie direkt gelesen, statt erneut zu synthetisieren. Nur außerhalb des
     * Haupt-Threads aufrufen.
     */
    @Synchronized
    fun synthesize(context: Context, text: String, speed: Float, outWav: File): KokoroAudio {
        if (outWav.exists() && outWav.length() > 44L) return readWav(outWav)
        val clean = EdgeTTS.speakableText(text)
        val e = ensureEngine(context)
        val audio = e.generate(clean, sid = 0, speed = speed.coerceIn(0.5f, 2f))
        val pcm = toPcm16(audio.samples)
        writeWav(outWav, pcm, audio.sampleRate, 1)
        return KokoroAudio(pcm, audio.sampleRate)
    }

    /** Liest eine eigene WAV-Datei (16-Bit-PCM) zurück in Rohdaten. */
    fun readWav(wav: File): KokoroAudio {
        val bytes = wav.readBytes()
        if (bytes.size < 44) throw IOException("Audio-Datei ist unvollständig.")
        var sampleRate = 24000
        var dataOffset = 44
        var dataLen = bytes.size - 44
        var i = 12
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4, Charsets.US_ASCII)
            val len = ((bytes[i + 4].toInt() and 0xff) or ((bytes[i + 5].toInt() and 0xff) shl 8) or
                ((bytes[i + 6].toInt() and 0xff) shl 16) or ((bytes[i + 7].toInt() and 0xff) shl 24))
            if (id == "fmt ") {
                sampleRate = (bytes[i + 12].toInt() and 0xff) or ((bytes[i + 13].toInt() and 0xff) shl 8) or
                    ((bytes[i + 14].toInt() and 0xff) shl 16) or ((bytes[i + 15].toInt() and 0xff) shl 24)
            } else if (id == "data") {
                dataOffset = i + 8
                dataLen = minOf(len, bytes.size - dataOffset)
                break
            }
            if (len <= 0) break
            i += 8 + len + (len % 2)
        }
        if (dataLen <= 0) throw IOException("Audio-Daten fehlen in der Datei.")
        return KokoroAudio(bytes.copyOfRange(dataOffset, dataOffset + dataLen), sampleRate)
    }

    /** Verpackt 16-Bit-PCM (mono) in WAV-Bytes (RIFF-Header + Rohdaten). */
    fun wavBytes(pcm: ByteArray, sampleRate: Int): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        val blockAlign = 2
        val byteRate = sampleRate * blockAlign
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeIntLE(out, 36 + pcm.size)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        writeIntLE(out, 16)
        out.write(byteArrayOf(1, 0, 1, 0))
        writeIntLE(out, sampleRate)
        writeIntLE(out, byteRate)
        out.write(byteArrayOf((blockAlign and 0xff).toByte(), (blockAlign shr 8).toByte(), 16, 0))
        out.write("data".toByteArray(Charsets.US_ASCII))
        writeIntLE(out, pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    /** Gibt die native Engine frei (RAM schonen). Ein neuerlicher Aufruf lädt neu. */
    @Synchronized
    fun release() {
        engine?.let { runCatching { it.free() } }
        engine = null
    }

    /** Löscht die Offline-Stimme komplett vom Gerät (Einstellungen → Daten). */
    fun delete(context: Context) {
        release()
        dir(context).deleteRecursively()
    }

    /** Baut die native sherpa-onnx-Engine (teuer: erst bei der ersten Synthese). */
    private fun ensureEngine(context: Context): OfflineTts {
        engine?.let { return it }
        if (!isDownloaded(context)) {
            throw IOException("Die Offline-Stimme ist noch nicht vollständig heruntergeladen.")
        }
        val d = dir(context)
        val dataDir = espeakDataDir(context) ?: throw IOException("Aussprache-Daten fehlen.")
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
        try {
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = File(d, "model.int8.onnx").absolutePath,
                        voices = File(d, "voices.bin").absolutePath,
                        tokens = File(d, "tokens.txt").absolutePath,
                        dataDir = dataDir.absolutePath,
                        lexicon = "",
                        lang = "de"
                    ),
                    numThreads = threads,
                    debug = false,
                    provider = "cpu"
                ),
                ruleFsts = "",
                ruleFars = "",
                maxNumSentences = 1,
                silenceScale = 0.2f
            )
            val e = OfflineTts(assetManager = null, config = config)
            engine = e
            return e
        } catch (t: Throwable) {
            // Auch Linker-/JNI-Fehler (z. B. fehlende native Bibliothek) sauber
            // als Fehler melden statt die App abstürzen zu lassen.
            throw IOException("Offline-Stimme konnte nicht gestartet werden: " +
                (t.message ?: t.javaClass.simpleName))
        }
    }

    /** Float-Samples [-1..1] in 16-Bit-PCM umwandeln. */
    private fun toPcm16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = (samples[i] * 32767f).toInt().coerceIn(-32768, 32767)
            out[i * 2] = (v and 0xff).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }

    /** Schreibt 16-Bit-PCM als WAV-Datei (gleicher Header-Aufbau wie Edge-TTS). */
    private fun writeWav(out: File, pcm: ByteArray, sampleRate: Int, channels: Int) {
        val blockAlign = channels * 2
        val byteRate = sampleRate * blockAlign
        DataOutputStream(FileOutputStream(out)).use { w ->
            w.writeBytes("RIFF")
            w.writeInt(Integer.reverseBytes(36 + pcm.size))
            w.writeBytes("WAVE")
            w.writeBytes("fmt ")
            w.writeInt(Integer.reverseBytes(16))
            w.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
            w.writeShort(java.lang.Short.reverseBytes(channels.toShort()).toInt())
            w.writeInt(Integer.reverseBytes(sampleRate))
            w.writeInt(Integer.reverseBytes(byteRate))
            w.writeShort(java.lang.Short.reverseBytes(blockAlign.toShort()).toInt())
            w.writeShort(java.lang.Short.reverseBytes(16.toShort()).toInt())
            w.writeBytes("data")
            w.writeInt(Integer.reverseBytes(pcm.size))
            w.write(pcm)
        }
    }

    private fun writeIntLE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xff)
        out.write((v shr 8) and 0xff)
        out.write((v shr 16) and 0xff)
        out.write((v shr 24) and 0xff)
    }
}
