package de.derdl1983.weriewas.ml

import android.util.Base64
import de.derdl1983.weriewas.data.Explanation
import de.derdl1983.weriewas.data.InfoLink
import de.derdl1983.weriewas.data.KnowledgeBase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optionale Online-Tiefenanalyse über die Mistral-API (Bildverständnis-Modell).
 * Wird nur mit eigenem API-Schlüssel und nach ausdrücklicher Einwilligung
 * des Nutzers pro Foto aufgerufen.
 */
object OnlineAnalyzer {

    private const val ENDPOINT = "https://api.mistral.ai/v1/chat/completions"
    private const val MODEL = "pixtral-12b-2409"

    private val PROMPT = "Du bist ein hilfreicher Erklärassistent für die App „Wer Wie Was“.\nAnalysiere das Foto und beantworte: Was ist das, wie funktioniert es, wofür wird es verwendet?\nAntworte AUSSCHLIESSLICH mit einem JSON-Objekt (ohne Markdown, ohne Erklärtext) mit genau diesen Feldern:\n{\"title\": \"Kurzer deutscher Name des Objekts\", \"category\": \"Kategorie (z. B. Haushalt, Werkzeug, Elektronik, Natur, Lebensmittel, Sport, Kleidung, Verkehr, Medizin)\", \"summary\": \"2-3 Sätze: Was ist das Objekt?\", \"howItWorks\": \"2-4 Sätze: Wie funktioniert es?\", \"usage\": \"1-3 Sätze: Wofür wird es typischerweise verwendet?\", \"practical\": \"1-2 praktische Tipps für den Alltag\", \"facts\": [\"2-3 kurze interessante Fakten\"], \"safety\": \"Sicherheitshinweise (bei Lebensmitteln, Pilzen, Medikamenten, Chemikalien, Tieren, Werkzeugen: deutliche Warnung)\", \"uncertain\": true falls du dir nicht sicher bist, sonst false}\nSchreibe alles auf Deutsch und für Laien verständlich."

    /** Führt die Tiefenanalyse für ein lokal gespeichertes Foto aus. */
    suspend fun analyze(imagePath: String, apiKey: String): Explanation =
        withContext(Dispatchers.IO) {
            val bytes = File(imagePath).readBytes()
            val dataUrl = "data:image/jpeg;base64," +
                Base64.encodeToString(bytes, Base64.NO_WRAP)

            val body = JSONObject()
                .put("model", MODEL)
                .put("temperature", 0.2)
                .put("max_tokens", 2000)
                .put("messages", JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", JSONArray()
                            .put(JSONObject().put("type", "text").put("text", PROMPT))
                            .put(JSONObject().put("type", "image_url").put("image_url", dataUrl))
                        )
                ))

            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 30000
                readTimeout = 90000
                setRequestProperty("Authorization", "Bearer " + apiKey)
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    throw IllegalStateException("Mistral-API-Fehler (HTTP " + code + ")")
                }
                val content = JSONObject(text)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                parse(content)
            } finally {
                conn.disconnect()
            }
        }

    private fun parse(content: String): Explanation {
        // Modell-Antwort kann von Markdown-Codezäunen umschlossen sein
        var cleaned = content.trim()
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.removePrefix("```json").removePrefix("```")
            cleaned = cleaned.removeSuffix("```").trim()
        }
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw IllegalStateException("Antwort der Online-Analyse war unlesbar.")
        }
        val o = JSONObject(cleaned.substring(start, end + 1))
        val title = o.optString("title", "Online-Analyse")
        val facts = mutableListOf<String>()
        val factsArray = o.optJSONArray("facts") ?: JSONArray()
        for (i in 0 until factsArray.length()) facts.add(factsArray.optString(i))
        val links: List<InfoLink> = KnowledgeBase.buildPublicLinks(title)
        return Explanation(
            title = title,
            category = o.optString("category", ""),
            summary = o.optString("summary", ""),
            howItWorks = o.optString("howItWorks", ""),
            usage = o.optString("usage", ""),
            practical = o.optString("practical", ""),
            facts = facts,
            safety = o.optString("safety", ""),
            links = links,
            uncertain = o.optBoolean("uncertain", false),
            source = "online"
        )
    }
}
