package de.derdl1983.weriewas.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.File

/** Hilfsfunktionen für Bitmaps: skalierendes Dekodieren (inkl. EXIF-Drehung) und lokales Speichern. */
object Images {

    private const val TAG = "WerWieWasImages"

    /**
     * Dekodiert ein Bild (Content-URI oder Datei-URI) grob skalierend.
     *
     * Strategie (jeder Schritt mit eigenem Fallback):
     *  1. Bild einmalig komplett in den Speicher lesen und per BitmapFactory dekodieren
     *     (robust gegen Streams, die nur einmal gelesen werden können), danach anhand der
     *     EXIF-Ausrichtung richtig herum drehen.
     *  2. Falls BitmapFactory nichts liefern kann (typisch bei HEIC/HEIF), wird ab
     *     Android 9 (API 28) ImageDecoder verwendet – er versteht HEIC/HEIF, GIF, WebP
     *     und wendet die EXIF-Rotation selbst korrekt an.
     *  3. Zuletzt wird ImageDecoder ohne Größenbegrenzung versucht.
     */
    fun decodeScaled(context: Context, uri: Uri, maxDim: Int = 1024): Bitmap? {
        val classic = decodeViaBitmapFactory(context, uri, maxDim)
        if (classic != null) return classic

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                val sized = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > maxDim) {
                        val scale = maxDim.toFloat() / longest
                        decoder.setTargetResize(
                            (info.size.width * scale).toInt().coerceAtLeast(1),
                            (info.size.height * scale).toInt().coerceAtLeast(1)
                        )
                    }
                }
                if (sized != null && !sized.isRecycled) return sized
            } catch (e: Exception) {
                Log.w(TAG, "ImageDecoder (skaliert) fehlgeschlagen für $uri: ${e.message}")
            }

            try {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                val raw = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                if (raw != null && !raw.isRecycled) return raw
            } catch (e: Exception) {
                Log.w(TAG, "ImageDecoder (unskaliert) fehlgeschlagen für $uri: ${e.message}")
            }
        }

        Log.e(TAG, "Alle Dekodier-Wege fehlgeschlagen für $uri")
        return null
    }

    /** BitmapFactory-Pfad: Bild einmalig lesen, aus dem Byte-Puffer dekodieren, EXIF-Drehung anwenden. */
    private fun decodeViaBitmapFactory(context: Context, uri: Uri, maxDim: Int): Bitmap? {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                Log.w(TAG, "Stream leer für $uri")
                return null
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val sample = if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                sampleSize(bounds.outWidth, bounds.outHeight, maxDim)
            } else {
                1
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (bmp == null) {
                Log.w(TAG, "BitmapFactory lieferte null (${bytes.size} Bytes, Format vermutlich HEIC/nicht unterstützt)")
                return null
            }
            applyOrientation(bmp, readOrientation(bytes))
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OutOfMemory beim Dekodieren von $uri")
            null
        } catch (e: Exception) {
            Log.e(TAG, "BitmapFactory-Pfad fehlgeschlagen für $uri", e)
            null
        }
    }

    /** Liest die EXIF-Ausrichtung aus den bereits gelesenen Bytes. */
    private fun readOrientation(bytes: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /** Dreht ein Bitmap anhand der EXIF-Ausrichtung; bei Fehlern wird das Original zurückgegeben. */
    private fun applyOrientation(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return src
        }
        return try {
            val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            if (rotated != src) src.recycle()
            rotated
        } catch (e: OutOfMemoryError) {
            src
        }
    }

    /**
     * Kopiert ein Bild skalierend in den App-internen Speicher; gibt den Pfad zurück.
     * Blockierend (Datei-I/O) – nicht auf dem Main-Thread aufrufen.
     */
    fun copyToInternal(context: Context, uri: Uri): String? {
        val bmp = decodeScaled(context, uri, 1280) ?: return null
        val dir = File(context.filesDir, "images").apply { mkdirs() }
        val file = File(dir, "img_${System.currentTimeMillis()}.jpg")
        return try {
            file.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 88, out) }
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Speichern fehlgeschlagen", e)
            file.delete()
            null
        } finally {
            bmp.recycle()
        }
    }

    /** Lädt ein bereits intern gespeichertes Bild als skalierte Bitmap (bereits richtig gedreht). */
    fun loadThumbnail(path: String, maxDim: Int = 512): Bitmap? {
        return try {
            val file = File(path)
            if (!file.exists()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDim) }
            )
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun sampleSize(w: Int, h: Int, maxDim: Int): Int {
        var sample = 1
        val longest = maxOf(w, h)
        while (longest / (sample * 2) >= maxDim) sample *= 2
        return sample
    }
}
