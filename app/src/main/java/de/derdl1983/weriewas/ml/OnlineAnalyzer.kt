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
 * Online-Bildanalyse über einen wählbaren KI-Anbieter
 * (Mistral, OpenAI-kompatibel, OpenRouter, Google Gemini,
 * Anthropic Claude oder xAI Grok).
 * Läuft ausschließlich online mit dem eigenen API-Schlüssel des Nutzers und
 * nur nach ausdrücklicher Einwilligung pro Foto.
 */
object OnlineAnalyzer {

    /** Konfiguration des gewählten KI-Anbieters. */
    data class ProviderConfig(
        val provider: String,
        val model: String,
        val baseUrl: String,
        val apiKey: String,
        val profilePrompt: String = ""
    )

    /** Persönliches Antwortprofil des Nutzers aus den Einstellungen. */
    data class ProfileConfig(
        val audience: String = "all",
        val level: String = "laie",
        val tone: String = "sachlich",
        val role: String = "erzaehler",
        val custom: String = ""
    )

    val PROVIDERS = listOf("mistral", "openai", "openrouter", "gemini", "anthropic", "xai")

    fun label(provider: String): String = when (provider) {
        "openai" -> "OpenAI-kompatibel"
        "openrouter" -> "OpenRouter"
        "gemini" -> "Google Gemini"
        "anthropic" -> "Anthropic Claude"
        "xai" -> "xAI Grok"
        else -> "Mistral"
    }

    fun defaultModel(provider: String): String = when (provider) {
        "openai" -> "gpt-6-luna"
        "openrouter" -> "google/gemini-3.8-flash"
        "gemini" -> "gemini-3.8-flash"
        "anthropic" -> "claude-sonnet-5-5"
        "xai" -> "grok-4.7"
        else -> "mistral-medium-2604"
    }

    /** Gängige Bildverständnis-Modelle je Anbieter für die Schnellauswahl in den Einstellungen. */
    fun suggestedModels(provider: String): List<String> = when (provider) {
        "openai" -> listOf(
            "gpt-6-luna",
            "gpt-6.1-sol",
            "gpt-6-astra",
            "gpt-5.6-terra",
            "gpt-5.5"
        )
        "openrouter" -> listOf(
            "google/gemini-3.8-flash",
            "google/gemini-3.6-flash",
            "openai/gpt-6.1-sol",
            "openai/gpt-6-luna",
            "anthropic/claude-sonnet-5-5",
            "anthropic/claude-haiku-5-5",
            "x-ai/grok-4.7",
            "meta-llama/llama-4-scout"
        )
        "gemini" -> listOf(
            "gemini-3.8-flash",
            "gemini-3.6-flash",
            "gemini-3.5-flash-lite",
            "gemini-3.1-pro-preview",
            "gemini-4-argon"
        )
        "anthropic" -> listOf(
            "claude-haiku-5-5",
            "claude-sonnet-5-5",
            "claude-opus-5-5",
            "claude-fable-5-1"
        )
        "xai" -> listOf(
            "grok-4.7",
            "grok-4.6",
            "grok-4.5"
        )
        else -> listOf(
            "mistral-medium-2604",
            "mistral-large-2512",
            "mistral-small-2506",
            "ministral-14b-2512"
        )
    }

    fun consoleUrl(provider: String): String = when (provider) {
        "openai" -> "https://platform.openai.com/api-keys"
        "openrouter" -> "https://openrouter.ai/settings/keys"
        "gemini" -> "https://aistudio.google.com/apikey"
        "anthropic" -> "https://console.anthropic.com/"
        "xai" -> "https://console.x.ai/"
        else -> "https://console.mistral.ai/"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun currentProvider(context: Context): String =
        prefs(context).getString("ai_provider", "mistral") ?: "mistral"

    fun apiKeyFor(context: Context, provider: String): String =
        prefs(context).getString(provider + "_api_key", "") ?: ""

    /** Lädt das gespeicherte Antwortprofil des Nutzers. */
    fun loadProfile(context: Context): ProfileConfig {
        val p = prefs(context)
        return ProfileConfig(
            audience = p.getString("ai_audience", "all") ?: "all",
            level = p.getString("ai_level", "laie") ?: "laie",
            tone = p.getString("ai_tone", "sachlich") ?: "sachlich",
            role = p.getString("ai_role", "erzaehler") ?: "erzaehler",
            custom = p.getString("ai_custom", "") ?: ""
        )
    }

    /** Baut aus dem Profil zusätzliche Anweisungen für den KI-Prompt. */
    private fun profilePrompt(profile: ProfileConfig): String {
        val sb = StringBuilder()
        when (profile.role) {
            "detektiv" -> sb.append("Erzähle im Stil eines neugierigen Detektivs, der das Rätsel um das Objekt löst: spannend, aber immer korrekt.\n")
            "professor" -> sb.append("Erzähle im Stil eines freundlichen Professors: fundiert, gern mit kleinen Fachbegriffen, die du sofort erklärst.\n")
            "freund" -> sb.append("Erzähle so, wie ein guter Freund es erklären würde: locker, persönlich mit „du“ und Alltagsbeispielen.\n")
        }
        when (profile.audience) {
            "kind" -> sb.append("Zielgruppe: Kinder von 8 bis 12 Jahren. Nutze kurze, einfache Sätze und viele Alltagsbeispiele.\n")
            "jugend" -> sb.append("Zielgruppe: Jugendliche. Sprich locker und informativ.\n")
            "senior" -> sb.append("Zielgruppe: ältere Menschen. Sprich respektvoll mit „Sie“, ruhig und deutlich.\n")
            else -> sb.append("Zielgruppe: Erwachsene.\n")
        }
        when (profile.level) {
            "fortgeschritten" -> sb.append("Vorwissen: fortgeschritten. Du darfst Fachbegriffe verwenden und erklärst sie kurz.\n")
            "experte" -> sb.append("Vorwissen: Experte. Du darfst technische Details und Fachsprache verwenden.\n")
            else -> sb.append("Vorwissen: Laie. Erkläre alles Schritt für Schritt ohne Vorwissen.\n")
        }
        when (profile.tone) {
            "lustig" -> sb.append("Ton: humorvoll und mit Charme – aber immer sachlich korrekt.\n")
            "kurz" -> sb.append("Ton: kurz und knackig, keine überflüssigen Wörter.\n")
            "ausfuehrlich" -> sb.append("Ton: ausführlich und gründlich, mit Beispielen.\n")
            else -> sb.append("Ton: sachlich und freundlich.\n")
        }
        if (profile.custom.isNotBlank()) {
            sb.append("Persönliche Hinweise des Nutzers: ").append(profile.custom.trim()).append("\n")
        }
        return sb.toString()
    }

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
            "openrouter" -> "https://openrouter.ai/api/v1"
            "gemini" -> "https://generativelanguage.googleapis.com/v1beta"
            "anthropic" -> "https://api.anthropic.com/v1"
            "xai" -> "https://api.x.ai/v1"
            else -> "https://api.mistral.ai/v1"
        }
        return ProviderConfig(
            provider,
            model,
            baseUrl.trimEnd('/'),
            key,
            profilePrompt(loadProfile(context))
        )
    }

    fun isConfigured(context: Context): Boolean = loadConfig(context) != null

    private val PROMPT = "Du bist ein hilfreicher Erklärassistent für die App „Wer Wie Was“.\nAnalysiere das Foto und beantworte: Was ist das, wie funktioniert es, wofür wird es verwendet?\nAntworte AUSSCHLIESSLICH mit einem JSON-Objekt (ohne Markdown, ohne Erklärtext) mit genau diesen Feldern:\n{\"title\": \"Kurzer deutscher Name des Objekts\", \"category\": \"Kategorie (z. B. Haushalt, Werkzeug, Elektronik, Natur, Lebensmittel, Sport, Kleidung, Verkehr, Medizin)\", \"summary\": \"2-3 Sätze: Was ist das Objekt?\", \"howItWorks\": \"2-4 Sätze: Wie funktioniert es?\", \"usage\": \"1-3 Sätze: Wofür wird es typischerweise verwendet?\", \"practical\": \"1-2 praktische Tipps für den Alltag\", \"facts\": [\"2-3 kurze interessante Fakten\"], \"safety\": \"Sicherheitshinweise (bei Lebensmitteln, Pilzen, Medikamenten, Chemikalien, Tieren, Werkzeugen: deutliche Warnung)\", \"uncertain\": true falls du dir nicht sicher bist, sonst false}\nSchreibe alles auf Deutsch."

    private fun promptFor(config: ProviderConfig): String = PROMPT + config.profilePrompt

    /** Führt die Online-Analyse für ein gespeichertes Foto mit dem gewählten Anbieter aus. */
    suspend fun analyze(imagePath: String, config: ProviderConfig): Explanation =
        withContext(Dispatchers.IO) {
            val bytes = File(imagePath).readBytes()
            val dataUrl = "data:image/jpeg;base64," +
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            val content = when (config.provider) {
                "gemini" -> requestGemini(config, dataUrl)
                "anthropic" -> requestAnthropic(config, dataUrl)
                else -> requestChatCompletions(config, dataUrl)
            }
            parse(content)
        }

    /** Mistral und alle OpenAI-kompatiblen APIs (chat/completions, Bild als Data-URL). */
    private fun requestChatCompletions(config: ProviderConfig, dataUrl: String): String {
        val textPart = JSONObject().put("type", "text").put("text", promptFor(config))
        val imagePart = JSONObject().put("type", "image_url").put("image_url", dataUrl)
        val contentParts = JSONArray().put(textPart).put(imagePart)
        val message = JSONObject().put("role", "user").put("content", contentParts)
        val messages = JSONArray().put(message)
        val body = JSONObject()
            .put("model", config.model)
            .put("messages", messages)
        if (config.provider == "openai") {
            // Aktuelle GPT-Modelle: max_completion_tokens statt max_tokens,
            // niedrige Temperature wird nicht mehr unterstützt.
            body.put("max_completion_tokens", 2000)
        } else {
            body.put("temperature", 0.2)
            body.put("max_tokens", 2000)
        }
        val conn = openPost(config.baseUrl + "/chat/completions", config.apiKey)
        val text = sendAndRead(conn, body.toString())
        val o = JSONObject(text)
        if (o.has("error")) {
            val err = o.optJSONObject("error")
            throw IllegalStateException(
                "KI-API-Fehler: " + (err?.optString("message") ?: err.toString())
            )
        }
        return o.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
    }

    /** Google Gemini (generateContent, Bild als inline_data). */
    private fun requestGemini(config: ProviderConfig, dataUrl: String): String {
        val base64 = dataUrl.substringAfter("base64,")
        val textPart = JSONObject().put("text", promptFor(config))
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
        val o = JSONObject(text)
        if (o.has("error")) {
            val err = o.optJSONObject("error")
            throw IllegalStateException(
                "Gemini-Fehler: " + (err?.optString("message") ?: err.toString())
            )
        }
        val candidates = o.getJSONArray("candidates")
        val responseParts = candidates.getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until responseParts.length()) {
            sb.append(responseParts.getJSONObject(i).optString("text"))
        }
        return sb.toString()
    }

    /** Anthropic Claude (Messages API, Bild als Base64-Quelle). */
    private fun requestAnthropic(config: ProviderConfig, dataUrl: String): String {
        val base64 = dataUrl.substringAfter("base64,")
        val textPart = JSONObject().put("type", "text").put("text", promptFor(config))
        val source = JSONObject()
            .put("type", "base64")
            .put("media_type", "image/jpeg")
            .put("data", base64)
        val imagePart = JSONObject().put("type", "image").put("source", source)
        val contentParts = JSONArray().put(textPart).put(imagePart)
        val message = JSONObject().put("role", "user").put("content", contentParts)
        val body = JSONObject()
            .put("model", config.model)
            .put("max_tokens", 2000)
            .put("temperature", 0.2)
            .put("messages", JSONArray().put(message))
        val conn = openPost(
            config.baseUrl + "/messages", "",
            mapOf(
                "x-api-key" to config.apiKey,
                "anthropic-version" to "2023-06-01"
            )
        )
        val text = sendAndRead(conn, body.toString())
        val o = JSONObject(text)
        if (o.has("error")) {
            val err = o.optJSONObject("error")
            throw IllegalStateException(
                "Claude-Fehler: " + (err?.optString("message") ?: err.toString())
            )
        }
        val contentArr = o.getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until contentArr.length()) {
            val block = contentArr.getJSONObject(i)
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        return sb.toString()
    }

    private fun openPost(url: String, bearer: String, extraHeaders: Map<String, String> = emptyMap()): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30000
            readTimeout = 90000
            setRequestProperty("Content-Type", "application/json")
            if (bearer.isNotBlank()) setRequestProperty("Authorization", "Bearer " + bearer)
            for ((k, v) in extraHeaders) setRequestProperty(k, v)
        }

    private fun sendAndRead(conn: HttpURLConnection, body: String): String {
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                // Fehlerursache aus der API-Antwort heraussuchen und verständlich melden
                val reason = extractApiError(text)
                val hint = when {
                    code == 401 || code == 403 ->
                        "Zugriff verweigert (HTTP " + code + "). Prüfe deinen API-Schlüssel."
                    code == 404 ->
                        "Adresse oder Modell nicht gefunden (HTTP 404). Prüfe Modellname und Basis-URL."
                    code == 429 ->
                        "Anfragelimit erreicht (HTTP 429). Warte kurz und versuche es erneut."
                    code >= 500 ->
                        "Der KI-Dienst hat ein Problem (HTTP " + code + "). Versuche es später erneut."
                    else -> "HTTP-Fehler " + code
                }
                throw IllegalStateException(hint + (if (reason.isNotBlank()) " – " + reason else ""))
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** Extrahiert eine kurze Fehlermeldung aus einer JSON-Fehlerantwort der API. */
    private fun extractApiError(text: String): String {
        if (text.isBlank()) return ""
        return runCatching {
            val o = JSONObject(text)
            val err = o.optJSONObject("error")
            when {
                err != null -> err.optString("message", "").take(200)
                o.has("message") -> o.optString("message", "").take(200)
                else -> ""
            }
        }.getOrDefault("")
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


