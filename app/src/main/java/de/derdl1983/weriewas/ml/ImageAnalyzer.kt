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
 */
object ImageAnalyzer {

    private const val MIN_CONFIDENCE = 0.35f

    /** Analysiert das Bild unterhalb des Pfads und liefert Labels + Erklärung. */
    suspend fun analyze(imagePath: String): AnalysisResult {
        val bitmap: Bitmap = Images.loadThumbnail(imagePath, 1024)
            ?: throw IllegalStateException("Das Bild konnte nicht gelesen werden.")
        val labels = labelImage(bitmap)
        if (labels.isEmpty()) {
            throw IllegalStateException(
                "Es wurden keine Objekte erkannt. Versuche es mit mehr Licht " +
                    "oder halte die Kamera näher ans Objekt."
            )
        }
        val usable = labels.filter { it.confidence >= MIN_CONFIDENCE }
        val relevant = if (usable.isEmpty()) labels.take(3) else usable
        val explanation = KnowledgeBase.explain(relevant)
        return AnalysisResult(relevant, explanation)
    }

    private suspend fun labelImage(bitmap: Bitmap): List<LabelConfidence> =
        suspendCancellableCoroutine { cont ->
            val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
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
