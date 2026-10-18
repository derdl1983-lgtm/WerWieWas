package de.derdl1983.weriewas.ml

import android.content.Context
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
 * Optionale Online-Tiefenanalyse über einen wählbaren KI-Anbieter
 * (Mistral, OpenAI-kompatibel oder Google Gemini).
 * Wird nur mit eigenem API-Schlüssel und nach ausdrücklicher Einwilligung
 * des Nutzers pro Foto aufgerufen.
 */
object OnlineAnalyzer {

    /** Konfiguration des gewählten KI-Anbieters. */
    data class ProviderConfig(
        val provider: String,
        val model: String,
        val baseUrl: String,
        val apiKey: String
    )

    val PROVIDERS = listOf("mistral", "openai", "gemini")

    fun label(provider: String): String = when (provider) {
        "openai" -> "OpenAI-kompatibel"
        "gemini" -> "Google Gemini"
        else -> "Mistral"
    }

    fun defaultModel(provider: String): String = when (provider) {
        "openai" -> "gpt-4o-mini"
        "gemini" -> "gemini-2.0-flash"
        else -> "pixtral-12b-2409"
    }

    fun consoleUrl(provider: String): String = when (provider) {
        "openai" -> "https://platform.openai.com/api-keys"
        "gemini" -> "https://aistudio.google.com/apikey"
        else -> "https://console.mistral.ai/"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun currentProvider(context: Context): String =
        prefs(context).getString("ai_provider", "mistral") ?: "mistral"

    fun apiKeyFor(context: Context, provider: String): String =
        prefs(context).getString(provider + "_api_key", "") ?: ""

    /** Lädt die gespeicherte Konfiguration; null, wenn kein API-Schlüssel hinterlegt ist. */
    fun loadConfig(context: Context): ProviderConfig? {
        val provider = currentProvider(context)
        val key = apiKeyFor(context, provider)
        if (key.isBlank()) return null
        val model = prefs(context).getString("ai_model", "")?.takeIf { it.isNotBlank() }
            ?: defaultModel(provider)
        val baseUrl = when (provider) {
            "openai" -> prefs(context).getString("ai_base_url", "")?.takeIf { it.isNotBlank() }
                ?: "https://api.openai.com/v1"
            "gemini" -> "https://generativelanguage.googleapis.com/v1beta"
            else -> "https://api.mistral.ai/v1"
        }
        return ProviderConfig(provider, model, baseUrl.trimEnd('/'), key)
    }

    fun isConfigured(context: Context): Boolean = loadConfig(context) != null

    private val PROMPT = "Du bist ein hilfreicher Erklärassistent für die App „Wer Wie Was“.\nAnalysiere das Foto und beantworte: Was ist das, wie funktioniert es, wofür wird es verwendet?\nAntworte AUSSCHLIESSLICH mit einem JSON-Objekt (ohne Markdown, ohne Erklärtext) mit genau diesen Feldern:\n{\"title\": \"Kurzer deutscher Name des Objekts\", \"category\": \"Kategorie (z. B. Haushalt, Werkzeug, Elektronik, Natur, Lebensmittel, Sport, Kleidung, Verkehr, Medizin)\", \"summary\": \"2-3 Sätze: Was ist das Objekt?\", \"howItWorks\": \"2-4 Sätze: Wie funktioniert es?\", \"usage\": \"1-3 Sätze: Wofür wird es typischerweise verwendet?\", \"practical\": \"1-2 praktische Tipps für den Alltag\", \"facts\": [\"2-3 kurze interessante Fakten\"], \"safety\": \"Sicherheitshinweise (bei Lebensmitteln, Pilzen, Medikamenten, Chemikalien, Tieren, Werkzeugen: deutliche Warnung)\", \"uncertain\": true falls du dir nicht sicher bist, sonst false}\nSchreibe alles auf Deutsch und für Laien verständlich."

    /** Führt die Tiefenanalyse für ein lokal gespeichertes Foto mit dem gewählten Anbieter aus. */
    suspend fun analyze(imagePath: String, config: ProviderConfig): Explanation =
        withContext(Dispatchers.IO) {
            val bytes = File(imagePath).readBytes()
            val dataUrl = "data:image/jpeg;base64," +
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            val content = when (config.provider) {
                "gemini" -> requestGemini(config, dataUrl)
                else -> requestChatCompletions(config, dataUrl)
            }
            parse(content)
        }

    /** Mistral und alle OpenAI-kompatiblen APIs (chat/completions, Bild als Data-URL). */
    private fun requestChatCompletions(config: ProviderConfig, dataUrl: String): String {
        val textPart = JSONObject().put("type", "text").put("text", PROMPT)
        val imagePart = JSONObject().put("type", "image_url").put("image_url", dataUrl)
        val contentParts = JSONArray().put(textPart).put(imagePart)
        val message = JSONObject().put("role", "user").put("content", contentParts)
        val messages = JSONArray().put(message)
        val body = JSONObject()
            .put("model", config.model)
            .put("temperature", 0.2)
            .put("max_tokens", 2000)
            .put("messages", messages)
        val conn = openPost(config.baseUrl + "/chat/completions", config.apiKey)
        val text = sendAndRead(conn, body.toString())
        return JSONObject(text)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
    }

    /** Google Gemini (generateContent, Bild als inline_data). */
    private fun requestGemini(config: ProviderConfig, dataUrl: String): String {
        val base64 = dataUrl.substringAfter("base64,")
        val textPart = JSONObject().put("text", PROMPT)
        val inlineData = JSONObject().put("mime_type", "image/jpeg").put("data", base64)
        val imagePart = JSONObject().put("inline_data", inlineData)
        val parts = JSONArray().put(textPart).put(imagePart)
        val content = JSONObject().put("parts", parts)
        val contents = JSONArray().put(content)
        val generationConfig = JSONObject().put("temperature", 0.2)
        val body = JSONObject()
            .put("contents", contents)
            .put("generationConfig", generationConfig)
        val url = config.baseUrl + "/models/" + config.model + ":generateContent?key=" + config.apiKey
        val conn = openPost(url, "")
        val text = sendAndRead(conn, body.toString())
        val candidates = JSONObject(text).getJSONArray("candidates")
        val responseParts = candidates.getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until responseParts.length()) {
            sb.append(responseParts.getJSONObject(i).optString("text"))
        }
        return sb.toString()
    }

    private fun openPost(url: String, bearer: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30000
            readTimeout = 90000
            setRequestProperty("Content-Type", "application/json")
            if (bearer.isNotBlank()) setRequestProperty("Authorization", "Bearer " + bearer)
        }

    private fun sendAndRead(conn: HttpURLConnection, body: String): String {
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw IllegalStateException("KI-API-Fehler (HTTP " + code + ")")
            }
            return text
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
