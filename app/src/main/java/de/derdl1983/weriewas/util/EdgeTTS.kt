package de.derdl1983.weriewas.util

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import okhttp3.ByteString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Gratis Online-Sprachausgabe über den Lesedienst von Microsoft Edge (Edge-TTS):
 * sehr natürliche neuronale Stimmen, kein API-Schlüssel nötig, benötigt Internet.
 * Nutzt dieselbe Schnittstelle wie der Edge-Browser beim Vorlesen.
 */
object EdgeTTS {

    data class EdgeVoice(val id: String, val label: String)

    /** Deutsche neuronale Stimmen (alle gratis). */
    val GERMAN_VOICES = listOf(
        EdgeVoice("de-DE-KatjaNeural", "Katja (weiblich, sehr natürlich)"),
        EdgeVoice("de-DE-ConradNeural", "Conrad (männlich, sehr natürlich)"),
        EdgeVoice("de-DE-AmalaNeural", "Amala (weiblich)"),
        EdgeVoice("de-DE-ElkeNeural", "Elke (weiblich)"),
        EdgeVoice("de-DE-KillianNeural", "Killian (männlich)"),
        EdgeVoice("de-DE-KlarissaNeural", "Klarissa (weiblich)"),
        EdgeVoice("de-DE-KlausNeural", "Klaus (männlich)"),
        EdgeVoice("de-DE-LouisaNeural", "Louisa (weiblich)"),
        EdgeVoice("de-DE-MajaNeural", "Maja (weiblich)"),
        EdgeVoice("de-DE-RalfNeural", "Ralf (männlich)"),
        EdgeVoice("de-DE-TanjaNeural", "Tanja (weiblich)")
    )

    fun labelFor(id: String): String =
        GERMAN_VOICES.firstOrNull { it.id == id }?.label ?: id

    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491A6F4"

    /** Zugriffs-Token der Edge-Schnittstelle (Sec-MS-GEC). */
    private fun secMsGec(): String {
        val ticks = (System.currentTimeMillis() / 1000L + 11644473600L + 5L) * 10000000L + 5000000L
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((ticks.toString() + TRUSTED_CLIENT_TOKEN).toByteArray(Charsets.US_ASCII))
        val sb = StringBuilder()
        for (b in digest) {
            val v = b.toInt() and 0xff
            if (v < 16) sb.append('0')
            sb.append(Integer.toHexString(v).uppercase())
        }
        return sb.toString()
    }

    private fun timestamp(): String {
        val fmt = SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
            Locale.US
        )
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun requestId(): String = UUID.randomUUID().toString().replace("-", "")

    /**
     * Synthetisiert Text zu einer MP3-Datei (24 kHz, 48 kbit/s, mono).
     * Blockierend – im Hintergrund-Thread aufrufen. ratePercent: -50 bis +100.
     */
    fun synthesizeToMp3(text: String, voice: String, ratePercent: Int, outFile: File): File {
        val url = ("wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
            + "?TrustedClientToken=" + TRUSTED_CLIENT_TOKEN
            + "&Sec-MS-GEC=" + secMsGec()
            + "&Sec-MS-GEC-Version=1-130.0.2849.68"
            + "&ConnectionId=" + requestId())
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val audio = ByteArrayOutputStream()
        val done = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val ws = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val ts = timestamp()
                    val config = ("X-Timestamp:" + ts + "\r\n"
                        + "Content-Type:application/json; charset=utf-8\r\n"
                        + "Path:speech.config\r\n\r\n"
                        + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":"
                        + "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},"
                        + "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}")
                    webSocket.send(config)
                    val escaped = text
                        .replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                    val rate = (if (ratePercent >= 0) "+" + ratePercent else ratePercent.toString()) + "%"
                    val ssml = ("<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='de-DE'>"
                        + "<voice name='" + voice + "'>"
                        + "<prosody rate='" + rate + "'>" + escaped + "</prosody>"
                        + "</voice></speak>")
                    val message = ("X-RequestId:" + requestId() + "\r\n"
                        + "Content-Type:application/ssml+xml\r\n"
                        + "X-Timestamp:" + ts + "\r\n"
                        + "Path:ssml\r\n\r\n"
                        + ssml)
                    webSocket.send(message)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val data = bytes.toByteArray()
                    if (data.size < 3) return
                    val headerLen = ((data[0].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff)
                    val audioStart = 2 + headerLen
                    if (data.size > audioStart) {
                        audio.write(data, audioStart, data.size - audioStart)
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("Path:turn.end")) {
                        webSocket.close(1000, null)
                        done.countDown()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failure.set("Verbindung fehlgeschlagen: " + (t.message ?: "unbekannter Fehler"))
                    done.countDown()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    done.countDown()
                }
            }
        )
        try {
            if (!done.await(90, TimeUnit.SECONDS)) {
                throw IOException("Zeitüberschreitung bei der Sprachsynthese.")
            }
            val err = failure.get()
            if (err != null) throw IOException(err)
            val mp3 = audio.toByteArray()
            if (mp3.size < 200) throw IOException("Keine Audiodaten empfangen.")
            outFile.parentFile?.mkdirs()
            outFile.writeBytes(mp3)
            return outFile
        } finally {
            ws.cancel()
            client.dispatcher.executorService.shutdown()
        }
    }

    /** Dekodiert eine MP3-Datei in eine WAV-Datei (16-Bit-PCM) für den Video-Export. */
    fun mp3ToWav(mp3File: File, wavFile: File): File {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(mp3File.absolutePath)
        } catch (e: Exception) {
            throw IOException("MP3 konnte nicht gelesen werden.")
        }
        try {
            var trackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME) == "audio/mpeg") {
                    trackIndex = i
                    trackFormat = f
                    break
                }
            }
            if (trackIndex < 0 || trackFormat == null) throw IOException("Keine MP3-Spur gefunden.")
            extractor.selectTrack(trackIndex)
            val codec = MediaCodec.createDecoderByType("audio/mpeg")
            val pcm = ByteArrayOutputStream()
            val input = ByteBuffer.allocate(1 shl 16)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            codec.configure(trackFormat, null, null, 0)
            codec.start()
            try {
                while (!outputDone) {
                    if (!inputDone) {
                        val idx = codec.dequeueInputBuffer(10000)
                        if (idx >= 0) {
                            val n = extractor.readSampleData(input, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(idx, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val oidx = codec.dequeueOutputBuffer(info, 10000)
                    if (oidx >= 0) {
                        val out = codec.getOutputBuffer(oidx)
                        if (out != null && info.size > 0) {
                            val arr = ByteArray(info.size)
                            out.get(arr)
                            pcm.write(arr)
                        }
                        codec.releaseOutputBuffer(oidx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            } finally {
                codec.stop()
                codec.release()
            }
            val sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            writeWav(wavFile, pcm.toByteArray(), sampleRate, channels)
            return wavFile
        } finally {
            extractor.release()
        }
    }

    /** Schreibt 16-Bit-PCM als WAV-Datei. */
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
}
