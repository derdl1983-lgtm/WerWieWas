package de.derdl1983.weriewas.ml

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import de.derdl1983.weriewas.data.Explanation
import de.derdl1983.weriewas.data.KnowledgeBase
import de.derdl1983.weriewas.data.LabelConfidence
import de.derdl1983.weriewas.util.Images
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Ergebnis der lokalen Analyse: gefundene Labels + fertige Erklärung. */
data class AnalysisResult(val labels: List<LabelConfidence>, val explanation: Explanation)

/**
 * Lokale, offline lauffähige Bilderkennung mit Google ML Kit.
 * Keine Cloud, keine Kosten, keine Anmeldung.
 *
 * Verbesserte Erkennung: Das Bild wird mehrfach analysiert –
 * einmal komplett und zusätzlich auf einen zugeschnittenen Bildauschnitt,
 * damit auch kleine oder abseits liegende Objekte erkannt werden.
 * Doppelt erkannte Labels werden verstärkt (höhere Konfidenz).
 */
object ImageAnalyzer {

    private const val LABELER_CONFIDENCE = 0.3f
    private const val MIN_CONFIDENCE = 0.35f
    private const val MAX_RELEVANT = 6

    /** Analysiert das Bild unterhalb des Pfads und liefert Labels + Erklärung. */
    suspend fun analyze(imagePath: String): AnalysisResult {
        val bitmap: Bitmap = Images.loadThumbnail(imagePath, 1024)
            ?: throw IllegalStateException("Das Bild konnte nicht gelesen werden.")

        val full = runCatching { labelImage(bitmap) }.getOrDefault(emptyList())

        // Detailansicht: mittiger Ausschnitt (60 %) für kleine Objekte
        val detail = runCatching {
            val crop = centerCrop(bitmap, 0.6f)
            val r = if (crop != null) labelImage(crop) else emptyList()
            crop?.recycle()
            r
        }.getOrDefault(emptyList())

        // Nahaufnahme: kleinerer mittiger Ausschnitt (35 %)
        val closeup = runCatching {
            val crop = centerCrop(bitmap, 0.35f)
            val r = if (crop != null) labelImage(crop) else emptyList()
            crop?.recycle()
            r
        }.getOrDefault(emptyList())

        val merged = merge(listOf(full, detail, closeup))
        if (merged.isEmpty()) {
            throw IllegalStateException(
                "Es wurden keine Objekte erkannt. Versuche es mit mehr Licht " +
                    "oder halte die Kamera näher ans Objekt."
            )
        }
        val usable = merged.filter { it.confidence >= MIN_CONFIDENCE }
        val relevant = (if (usable.isEmpty()) merged.take(3) else usable)
            .sortedByDescending { it.confidence }
            .take(MAX_RELEVANT)
        val explanation = KnowledgeBase.explain(relevant)
        return AnalysisResult(relevant, explanation)
    }

    /** Schneidet einen zentrierten quadratischen Ausschnitt aus dem Bild. */
    private fun centerCrop(src: Bitmap, fraction: Float): Bitmap? {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return null
        val side = (minOf(w, h) * fraction).toInt().coerceAtLeast(64)
        if (side >= w && side >= h) return null
        val left = ((w - side) / 2).coerceAtLeast(0)
        val top = ((h - side) / 2).coerceAtLeast(0)
        return Bitmap.createBitmap(src, left, top, minOf(side, w - left), minOf(side, h - top))
    }

    /**
     * Fasst die Label aller Durchläufe zusammen:
     * Ein Label, das in mehreren Durchläufen erkannt wurde, bekommt die
     * höchste Konfidenz plus einen Bonus pro zusätzlichem Fund.
     */
    private fun merge(runs: List<List<LabelConfidence>>): List<LabelConfidence> {
        val best = HashMap<String, Float>()
        val found = HashMap<String, Int>()
        for (run in runs) {
            for (label in run) {
                val key = label.label.trim().lowercase()
                if (key.isBlank()) continue
                val prevBest = best[key] ?: 0f
                if (label.confidence > prevBest) best[key] = label.confidence
                found[key] = (found[key] ?: 0) + 1
            }
        }
        return best.map { (key, conf) ->
            val bonus = ((found[key] ?: 1) - 1) * 0.08f
            LabelConfidence(key.replaceFirstChar { it.uppercase() }, (conf + bonus).coerceAtMost(0.99f))
        }.sortedByDescending { it.confidence }
    }

    private suspend fun labelImage(bitmap: Bitmap): List<LabelConfidence> =
        suspendCancellableCoroutine { cont ->
            val options = ImageLabelerOptions.Builder()
                .setConfidenceThreshold(LABELER_CONFIDENCE)
                .build()
            val labeler = ImageLabeling.getClient(options)
            labeler.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { list: List<ImageLabel> ->
                    cont.resume(list.map { LabelConfidence(it.text, it.confidence) })
                }
                .addOnFailureListener { e ->
                    cont.resumeWithException(
                        IllegalStateException("Die Erkennung ist fehlgeschlagen: " + e.message)
                    )
                }
        }
}
