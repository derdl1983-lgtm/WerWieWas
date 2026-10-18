package de.derdl1983.weriewas.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Exportiert das Erklärvideo als abspielbare MP4-Datei:
 * Szenen werden als H.264-Videospur encodiert, die Sprachausgabe als AAC-Tonspur,
 * beides über MediaMuxer in eine Datei gemuxt und in die Mediathek gespeichert.
 * Blockierend mit viel I/O – nur ausserhalb des Main-Threads aufrufen.
 */
object VideoExporter {

    /** Eine Szene des Videos (Titel, Text, Themenbild-Zeichen, optionales Foto). */
    data class SlideData(
        val title: String,
        val text: String,
        val emoji: String,
        val photoPath: String?
    )

    private class SlideAudio(val pcm: ByteArray, val sampleRate: Int, val channels: Int)
    private class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)

    class ExportException(message: String) : Exception(message)

    private const val WIDTH = 720
    private const val HEIGHT = 1280
    private const val FRAME_INTERVAL_US = 250000L
    private const val TARGET_RATE = 22050
    private const val SILENCE_SECONDS = 0.6

    /**
     * Erstellt das Video und gibt eine Beschreibung des Speicherorts zurück.
     * onProgress wird mit (erledigte Schritte, Gesamtschritte) aufgerufen.
     */
    fun export(
        context: Context,
        slides: List<SlideData>,
        voiceA: Voice?,
        voiceB: Voice?,
        rate: Float,
        onProgress: (done: Int, total: Int) -> Unit
    ): String {
        if (slides.isEmpty()) throw ExportException("Keine Inhalte zum Export vorhanden.")
        val totalSteps = 3 + slides.size
        var done = 0

        val audios = synthesizeSlides(context, slides, voiceA, voiceB, rate)
        done += slides.size
        onProgress(done, totalSteps)

        val silence = ByteArray((SILENCE_SECONDS * TARGET_RATE * 2).toInt())
        val pcmOut = ByteArrayOutputStream()
        val ranges = mutableListOf<LongRange>()
        var start = 0L
        for (a in audios) {
            val mono = toMonoTarget(a)
            pcmOut.write(mono)
            pcmOut.write(silence)
            val end = start + (mono.size + silence.size) * 1000000L / (TARGET_RATE * 2)
            ranges.add(start until end)
            start = end
        }
        val durationUs = start
        val pcm = pcmOut.toByteArray()
        done++
        onProgress(done, totalSteps)

        val pixels = slides.mapIndexed { i, s ->
            val bmp = renderSlide(s, i)
            val px = IntArray(WIDTH * HEIGHT)
            bmp.getPixels(px, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
            bmp.recycle()
            px
        }
        done++
        onProgress(done, totalSteps)

        fun pixelsAt(t: Long): IntArray {
            for (i in ranges.indices) if (ranges[i].contains(t)) return pixels[i]
            return pixels.last()
        }

        val audio = encodeAudio(pcm, TARGET_RATE)
        val video = encodeVideo(durationUs) { pixelsAt(it) }
        val file = mux(context, audio, video)
        done++
        onProgress(done, totalSteps)
        val message = saveVideo(context, file)
        done++
        onProgress(done, totalSteps)
        return message
    }

    /** Erzeugt je Szene eine gesprochene WAV-Datei über die Android-Sprachausgabe. */
    private fun synthesizeSlides(
        context: Context,
        slides: List<SlideData>,
        voiceA: Voice?,
        voiceB: Voice?,
        rate: Float
    ): List<SlideAudio> {
        val initLatch = CountDownLatch(1)
        val tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) initLatch.countDown()
        }
        try {
            if (!initLatch.await(15, TimeUnit.SECONDS)) {
                throw ExportException("Sprachausgabe konnte nicht initialisiert werden.")
            }
            tts.language = Locale.GERMANY
            tts.setSpeechRate(rate)
            val result = mutableListOf<SlideAudio>()
            for ((index, slide) in slides.withIndex()) {
                val voice = if (index % 2 == 1) (voiceB ?: voiceA) else voiceA
                if (voice != null) {
                    tts.setVoice(voice)
                    tts.setPitch(1f)
                } else {
                    tts.setPitch(if (index % 2 == 1) 0.8f else 1f)
                }
                val wav = File(context.cacheDir, "www_slide_" + index + ".wav")
                val latch = CountDownLatch(1)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onDone(utteranceId: String?) { latch.countDown() }
                    override fun onError(utteranceId: String?) { latch.countDown() }
                    override fun onStart(utteranceId: String?) { }
                })
                val ok = tts.synthesizeToFile(
                    slide.title + ". " + slide.text,
                    Bundle().apply {
                        putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "exp" + index)
                    },
                    wav,
                    "exp" + index
                )
                if (ok == TextToSpeech.SUCCESS) latch.await(60, TimeUnit.SECONDS) else Thread.sleep(1000)
                val audio = runCatching { parseWav(wav) }
                    .getOrElse { SlideAudio(ByteArray(0), TARGET_RATE, 1) }
                result.add(audio)
                wav.delete()
            }
            return result
        } finally {
            tts.stop()
            tts.shutdown()
        }
    }

    /** Liest eine WAV-Datei (16-Bit-PCM) und extrahiert die Rohdaten. */
    private fun parseWav(file: File): SlideAudio {
        val bytes = file.readBytes()
        if (bytes.size < 44) throw ExportException("Audio-Datei zu klein.")
        var sampleRate = TARGET_RATE
        var channels = 1
        var dataOffset = -1
        var dataLen = 0
        var i = 12
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4, Charsets.US_ASCII)
            val len = readIntLE(bytes, i + 4)
            if (id == "fmt ") {
                channels = readShortLE(bytes, i + 10).toInt().coerceAtLeast(1)
                sampleRate = readIntLE(bytes, i + 12)
            } else if (id == "data") {
                dataOffset = i + 8
                dataLen = minOf(len, bytes.size - dataOffset)
            }
            if (len <= 0) break
            i += 8 + len + (len % 2)
        }
        if (dataOffset < 0 || dataLen <= 0) throw ExportException("Audio-Daten nicht gefunden.")
        return SlideAudio(bytes.copyOfRange(dataOffset, dataOffset + dataLen), sampleRate, channels)
    }

    /** Wandelt eine Spur in Mono mit fester Abtastrate um (nötig für den AAC-Encoder). */
    private fun toMonoTarget(a: SlideAudio): ByteArray {
        val mono: ShortArray = if (a.channels >= 2) {
            val frames = a.pcm.size / 4
            ShortArray(frames) { i ->
                val l = readShortLE(a.pcm, i * 4)
                val r = readShortLE(a.pcm, i * 4 + 2)
                ((l.toInt() + r.toInt()) / 2).toShort()
            }
        } else {
            val frames = a.pcm.size / 2
            ShortArray(frames) { i -> readShortLE(a.pcm, i * 2) }
        }
        if (a.sampleRate == TARGET_RATE || mono.isEmpty()) return shortsToBytes(mono)
        val ratio = a.sampleRate.toDouble() / TARGET_RATE
        val outLen = (mono.size / ratio).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val pos = i * ratio
            val i0 = pos.toInt().coerceAtMost(mono.size - 1)
            val i1 = (i0 + 1).coerceAtMost(mono.size - 1)
            val frac = (pos - i0).toFloat()
            out[i] = (mono[i0] * (1f - frac) + mono[i1] * frac).toInt().toShort()
        }
        return shortsToBytes(out)
    }

    private fun shortsToBytes(a: ShortArray): ByteArray {
        val out = ByteArray(a.size * 2)
        for (i in a.indices) {
            val v = a[i].toInt()
            out[i * 2] = (v and 0xff).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }

    private fun readIntLE(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or
            ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or
            ((b[o + 3].toInt() and 0xff) shl 24)

    private fun readShortLE(b: ByteArray, o: Int): Short =
        (((b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8))).toShort()

    /** Encodiert die Tonspur als AAC und liefert Format + Samples. */
    private fun encodeAudio(pcm: ByteArray, sampleRate: Int): Pair<MediaFormat, List<Sample>> {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 64000)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val samples = mutableListOf<Sample>()
        var outFormat: MediaFormat? = null
        var offset = 0
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10000)
                    if (idx >= 0) {
                        if (offset >= pcm.size) {
                            val finalPts = pcm.size * 1000000L / (sampleRate * 2)
                            codec.queueInputBuffer(idx, 0, 0, finalPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val buf = codec.getInputBuffer(idx)
                            if (buf != null) {
                                buf.clear()
                                val n = minOf(buf.remaining(), pcm.size - offset)
                                buf.put(pcm, offset, n)
                                codec.queueInputBuffer(idx, 0, n, offset * 1000000L / (sampleRate * 2), 0)
                                offset += n
                            } else {
                                inputDone = true
                            }
                        }
                    }
                }
                val info = MediaCodec.BufferInfo()
                val oidx = codec.dequeueOutputBuffer(info, if (inputDone) 10000 else 0)
                if (oidx >= 0) {
                    val buf = codec.getOutputBuffer(oidx)
                    if (buf != null && info.size > 0) {
                        val arr = ByteArray(info.size)
                        buf.get(arr)
                        samples.add(Sample(arr, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_SYNC_ONLY))
                    }
                    codec.releaseOutputBuffer(oidx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (oidx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFormat = MediaFormat(codec.outputFormat)
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }
        return Pair(outFormat ?: format, samples)
    }

    /** Encodiert die Standbilder als H.264-Videospur. */
    private fun encodeVideo(durationUs: Long, pixelsAt: (Long) -> IntArray): Pair<MediaFormat, List<Sample>> {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 6000000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 4)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val samples = mutableListOf<Sample>()
        var outFormat: MediaFormat? = null
        var t = 0L
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10000)
                    if (idx >= 0) {
                        if (t >= durationUs) {
                            codec.queueInputBuffer(idx, 0, 0, durationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val image = codec.getInputImage(idx)
                            if (image == null) {
                                throw ExportException("Der Video-Encoder wird von diesem Gerät nicht unterstützt.")
                            }
                            fillYuv420(image, pixelsAt(t))
                            codec.queueInputBuffer(idx, 0, WIDTH * HEIGHT * 3 / 2, t, 0)
                            t += FRAME_INTERVAL_US
                        }
                    }
                }
                val info = MediaCodec.BufferInfo()
                val oidx = codec.dequeueOutputBuffer(info, if (inputDone) 10000 else 0)
                if (oidx >= 0) {
                    val buf = codec.getOutputBuffer(oidx)
                    if (buf != null && info.size > 0) {
                        val arr = ByteArray(info.size)
                        buf.get(arr)
                        samples.add(Sample(arr, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_SYNC_ONLY))
                    }
                    codec.releaseOutputBuffer(oidx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (oidx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFormat = MediaFormat(codec.outputFormat)
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }
        return Pair(outFormat ?: format, samples)
    }

    /** Schreibt ARGB-Pixel in die YUV-Ebenen eines Encoder-Eingabebildes. */
    private fun fillYuv420(image: Image, argb: IntArray) {
        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRow = yPlane.rowStride
        val yPix = yPlane.pixelStride
        for (h in 0 until HEIGHT) {
            for (w in 0 until WIDTH) {
                val c = argb[h * WIDTH + w]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBuf.put(h * yRow + w * yPix, y.toByte())
            }
        }
        val uRow = uPlane.rowStride
        val uPix = uPlane.pixelStride
        val vRow = vPlane.rowStride
        val vPix = vPlane.pixelStride
        for (h in 0 until HEIGHT / 2) {
            for (w in 0 until WIDTH / 2) {
                val c = argb[(2 * h + 1) * WIDTH + (2 * w + 1)]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                uBuf.put(h * uRow + w * uPix, u.toByte())
                vBuf.put(h * vRow + w * vPix, v.toByte())
            }
        }
    }

    /** Muxt Video- und Tonspur in eine MP4-Datei im App-Cache. */
    private fun mux(context: Context, audio: Pair<MediaFormat, List<Sample>>, video: Pair<MediaFormat, List<Sample>>): File {
        val file = File(context.cacheDir, "erklarvideo_" + System.currentTimeMillis() + ".mp4")
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val vTrack = muxer.addTrack(video.first)
            val aTrack = muxer.addTrack(audio.first)
            muxer.start()
            for (s in video.second) {
                val info = MediaCodec.BufferInfo()
                info.set(0, s.data.size, s.ptsUs, s.flags)
                muxer.writeSampleData(vTrack, ByteBuffer.wrap(s.data), info)
            }
            for (s in audio.second) {
                val info = MediaCodec.BufferInfo()
                info.set(0, s.data.size, s.ptsUs, s.flags)
                muxer.writeSampleData(aTrack, ByteBuffer.wrap(s.data), info)
            }
            muxer.stop()
        } finally {
            muxer.release()
        }
        return file
    }

    /** Speichert das fertige Video in der Mediathek (ab Android 10) bzw. im App-Ordner. */
    private fun saveVideo(context: Context, file: File): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/WerWieWas")
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw ExportException("Video konnte nicht gespeichert werden.")
            context.contentResolver.openOutputStream(uri)?.use { it.write(file.readBytes()) }
                ?: throw ExportException("Video konnte nicht gespeichert werden.")
            file.delete()
            return "Video gespeichert unter Filme/WerWieWas/" + file.name
        }
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, "videos")
        dir.mkdirs()
        val target = File(dir, file.name)
        file.copyTo(target, overwrite = true)
        file.delete()
        return "Video gespeichert unter: " + target.absolutePath
    }

    /** Rendert eine Szene als Standbild (Gradient-Hintergrund, Foto, Emoji, Titel, Text). */
    private fun renderSlide(slide: SlideData, index: Int): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val palettes = arrayOf(
            intArrayOf(0xFF1A237E.toInt(), 0xFF3949AB.toInt()),
            intArrayOf(0xFF00695C.toInt(), 0xFF00897B.toInt()),
            intArrayOf(0xFF4A148C.toInt(), 0xFF6A1B9A.toInt()),
            intArrayOf(0xFF0277BD.toInt(), 0xFF0288D1.toInt()),
            intArrayOf(0xFF283593.toInt(), 0xFF3F51B5.toInt()),
            intArrayOf(0xFF33691E.toInt(), 0xFF558B2F.toInt()),
            intArrayOf(0xFFBF360C.toInt(), 0xFFD84315.toInt()),
            intArrayOf(0xFF37474F.toInt(), 0xFF455A64.toInt())
        )
        val colors = palettes[index % palettes.size]
        val bg = Paint()
        bg.shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), colors[0], colors[1], Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), bg)

        var top = 140f
        if (slide.photoPath != null) {
            val photo = BitmapFactory.decodeFile(slide.photoPath)
            if (photo != null) {
                val target = RectF(60f, top, WIDTH - 60f, top + 600f)
                val matrix = Matrix()
                matrix.setRectToRect(
                    RectF(0f, 0f, photo.width.toFloat(), photo.height.toFloat()),
                    target,
                    Matrix.ScaleToFit.CENTER
                )
                canvas.drawBitmap(photo, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
                photo.recycle()
                top += 660f
            }
        }
        if (slide.emoji.isNotBlank()) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.textSize = 190f
            p.textAlign = Paint.Align.CENTER
            p.color = Color.WHITE
            val fm = p.fontMetrics
            canvas.drawText(slide.emoji, WIDTH / 2f, top + 95f - (fm.top + fm.bottom) / 2f, p)
            top += 280f
        }
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        titlePaint.textSize = 62f
        titlePaint.isFakeBoldText = true
        titlePaint.color = Color.WHITE
        val titleLayout = StaticLayout.Builder.obtain(
            slide.title, 0, slide.title.length, titlePaint, WIDTH - 160
        ).setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        canvas.save()
        canvas.translate((WIDTH - titleLayout.width) / 2f, top)
        titleLayout.draw(canvas)
        canvas.restore()
        top += titleLayout.height + 60f

        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        bodyPaint.textSize = 42f
        bodyPaint.color = Color.WHITE
        val bodyLayout = StaticLayout.Builder.obtain(
            slide.text, 0, slide.text.length, bodyPaint, WIDTH - 160
        ).setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 1.25f).build()
        canvas.save()
        canvas.translate((WIDTH - bodyLayout.width) / 2f, top)
        bodyLayout.draw(canvas)
        canvas.restore()
        return bmp
    }
}
