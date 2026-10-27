package de.derdl1983.weriewas.ml

import android.content.Context
import android.util.Base64
import de.derdl1983.weriewas.data.Discovery
import de.derdl1983.weriewas.data.Explanation
import de.derdl1983.weriewas.data.KnowledgeBase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

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
        val profilePrompt: String = "",
        val language: String = "de"
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

    /** Antwort-Sprache der KI (für Prompts und Rückfragen). */
    val LANGUAGES = listOf("de", "en", "fr", "es", "tr", "it")

    fun languageName(code: String): String = when (code) {
        "en" -> "Englisch"
        "fr" -> "Französisch"
        "es" -> "Spanisch"
        "tr" -> "Türkisch"
        "it" -> "Italienisch"
        else -> "Deutsch"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun currentProvider(context: Context): String =
        prefs(context).getString("ai_provider", "mistral") ?: "mistral"

    fun apiKeyFor(context: Context, provider: String): String =
        de.derdl1983.weriewas.util.SecretStore.get(context, provider + "_api_key")

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
            "detektiv" -> sb.append("Erzähle im Stil eines neugierigen Detektivs, der das Rätsel um das Objekt knackt: baue Spannung auf, tue so, als würdest du Ermittlungsdaten sammeln („Am Tatort fallen mir sofort drei Dinge auf …“), und löse den Fall am Ende auf. Fakten bleiben immer korrekt.\n")
            "professor" -> sb.append("Erzähle im Stil eines begeisterten Professors, der von seinem Lieblingsthema brennt: mit Feuereifer, kleinen Fachbegriffen, die du sofort erklärst, und mindestens einem „Aha!“-Moment pro Abschnitt.\n")
            "freund" -> sb.append("Erzähle so, wie dein bester Freund beim Kaffee erzählen würde: locker, persönlich mit „du“, Alltagsbeispielen und echtem Enthusiasmus.\n")
        }
        when (profile.audience) {
            "kind" -> sb.append(
                "Zielgruppe: Kinder von 8 bis 12 Jahren. Erzähle, wie man es einem " +
                    "neugierigen Kind erklärt, das zum ersten Mal davon hört: kurze, " +
                    "einfache Sätze mit einem Gedanken pro Satz, begeistert und voller " +
                    "Neugier, viele vertraute Dinge aus dem Alltag des Kindes als " +
                    "Beispiele (Schule, Spielen, Sport, Haustiere, Frühstückstisch). " +
                    "Nutze liebevolle, kindgerechte Vergleiche, damit sich das Kind " +
                    "das Objekt richtig vorstellen kann, und erkläre jeden Fachbegriff " +
                    "sofort in Kinderworten. Baue zwischendurch eine kleine Ratefrage " +
                    "ein („Rate mal, wofür das gut ist!“) und sprich das Kind direkt " +
                    "und ermutigend an. Wirke warm und geduldig – niemals belehrend, " +
                    "herablassend oder wie ein Babybuch.\n"
            )
            "jugend" -> sb.append(
                "Zielgruppe: Jugendliche. Erzähle wie der coolste Typ aus dem " +
                    "Freundeskreis, der sein Zeug einfach draufhat: locker und direkt " +
                    "mit „du“, prägnant ohne Gelaber und mit echtem Enthusiasmus " +
                    "statt Belehrung. Nutze Beispiele aus der Welt von Schule, Social " +
                    "Media, Gaming und Alltag und erkläre kurz, warum das Objekt " +
                    "relevant ist („Das benutzt du täglich, ohne es zu merken“). " +
                    "Vermeide krampfigen Jugend-Slang und Moralpredigten – bleib " +
                    "authentisch und auf Augenhöhe. Fakten bleiben trotzdem sauber " +
                    "korrekt, und bei Sicherheitshinweisen wird ernst gewarnt.\n"
            )
            "senior" -> sb.append(
                "Zielgruppe: Senioren. Erkläre, wie man es einem alten, etwas " +
                    "vergesslichen Herrn erklärt, dem man Zeit und Geduld schenkt: " +
                    "respektvoll mit „Sie“, ruhig, freundlich und ganz ohne Hetze. " +
                    "Schreibe ausführlicher als sonst: kurze, klare Sätze mit einem " +
                    "Gedanken pro Satz, erkläre jeden Schritt einzeln und nenne " +
                    "vertraute Dinge aus dem Alltag als Beispiele. Kündige jeden " +
                    "Abschnitt sanft an („Jetzt kommt der wichtige Teil …“) und fasse " +
                    "am Ende jedes Abschnitts das Wichtigste in einem Merksatz " +
                    "zusammen („Merken Sie sich: …“), damit auch nach einer kleinen " +
                    "Pause nichts verloren geht. Wirke warm und ermutigend – " +
                    "niemals bevormundend oder kindisch.\n"
            )
            else -> sb.append(
                "Zielgruppe: Erwachsene. Erzähle auf Augenhöhe: klar strukturiert " +
                    "mit einem Gedanken pro Satz, konzentriert auf das Wesentliche " +
                    "und ohne Umwege. Nutze greifbare Alltagsbeispiele aus Beruf, " +
                    "Haushalt, Hobby oder Technik, erkläre Zusammenhänge so, dass " +
                    "man sie beim Zuhören sofort versteht, und verzichte auf " +
                    "Belehrungen und Floskeln. Nenne Zahlen und Größen, wo sie " +
                    "helfen, und erkläre Fachbegriffe kurz, statt sie umständlich " +
                    "zu umschreiben. Wirke freundlich, respektvoll und nah am " +
                    "echten Leben.\n"
            )
        }
        when (profile.level) {
            "fortgeschritten" -> sb.append(
                "Vorwissen: fortgeschritten. Du darfst Fachbegriffe verwenden und erklärst sie kurz. " +
                    "Geh in die Tiefe: nenne konkrete Zahlen, Materialien, Maße und Wirkprinzipien, " +
                    "und erkläre auch Zusammenhänge, die erst auf den zweiten Blick offensichtlich sind.\n"
            )
            "experte" -> sb.append(
                "Vorwissen: Experte. Du darfst technische Details und Fachsprache verwenden. " +
                    "Liefere echtes Fachwissen auf Expertenniveau: exakte Zahlen und Maße, Materialien und " +
                    "Werkstoffe, physikalische/chemische/technische Grundlagen, Funktionsprinzipien bis ins " +
                    "Detail, typische Kennwerte, Normen, Historie und industrielle Zusammenhänge – wie ein " +
                    "gutes Fachbuch, das trotzdem flüssig vorlesbar bleibt. Keine Oberflächlichkeiten, " +
                    "keine Floskeln, keine ausweichenden Allgemeinplätze.\n"
            )
            else -> sb.append("Vorwissen: Laie. Erkläre alles Schritt für Schritt ohne Vorwissen.\n")
        }
        when (profile.tone) {
            "lustig" -> sb.append(
                "Ton: RICHTIG lustig – schreibe wie ein Comedy-Autor! Übertreib spaßeshalber, " +
                    "nutze überraschende Vergleiche („dieser Toaster brüllt wie ein U-Boot beim Frühsport“), " +
                    "freche Alltagsbeobachtungen, Augenzwinkern und Pointen mit Paukenschlag. " +
                    "Baue in JEDEM Abschnitt mindestens einen echten Gag ein, gern mit rhetorischen Fragen, " +
                    "Komik-Pausen und Wortspielen. Wichtig: Die Fakten bleiben trotzdem sauber korrekt, " +
                    "und bei Sicherheitshinweisen wird ernst gewarnt – dort KEIN Witz.\n"
            )
            "kurz" -> sb.append("Ton: knackig wie eine Schlagzeile – kurze Sätze, null Füllwörter, jedes Wort muss sich seinen Platz verdienen.\n")
            "ausfuehrlich" -> sb.append("Ton: ausführlich und gründlich – greifbare Beispiele, echte Zusammenhänge, auch Details, die sonst unter den Tisch fallen.\n")
            "verruckt" -> sb.append(
                "Ton: Frei erfundene, witzige Geschichte – mit rotem Faden statt " +
                    "wirrem Blödsinn! Erfinde eine humorvolle Geschichte um das " +
                    "Objekt als Hauptfigur, die einem klaren roten Faden folgt: ein " +
                    "einziges erkennbares Thema von Anfang bis Ende, zwei, drei " +
                    "skurrile, aber nachvollziehbare Wendungen, ein wiederkehrendes " +
                    "witziges Motiv und ein Finale mit Augenzwinkern, in dem sich " +
                    "alles auflöst. Übertreibungen sind erlaubt, aber bleibe bei " +
                    "der Geschichte – kein Absurditäten-Regen ohne Zusammenhang. " +
                    "Streue zwischendurch ein, zwei echte Fakten über das Objekt ein. " +
                    "Wichtig: Das Objekt heißt bei seinem echten Namen (Feld title) " +
                    "und das Feld category bleibt korrekt, und die Sicherheitshinweise " +
                    "bleiben vollständig ernst und sachlich korrekt – dort wird " +
                    "NIEMALS etwas erfunden.\n"
            )
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
        // Kein Längen-Zwang mehr: Die Analyse ist so ausführlich, wie es
        // Rolle, Zielgruppe und Vorwissen des KI-Profils verlangen – das
        // Video passt seine Länge danach der Sprechzeit an.
        return ProviderConfig(
            provider,
            model,
            baseUrl.trimEnd('/'),
            key,
            profilePrompt(loadProfile(context)),
            prefs(context).getString("ai_language", "de") ?: "de"
        )
    }

    fun isConfigured(context: Context): Boolean = loadConfig(context) != null

    private val PROMPT = "Du bist ein hilfreicher Erklärassistent für die App „Wer Wie Was“.\nAnalysiere das Foto (bei mehreren Fotos: verschiedene Ansichten desselben Objekts – nutze alle Bilder gemeinsam für eine möglichst genaue Erkennung) und beantworte: Was ist das, wie funktioniert es, wofür wird es verwendet?\nAntworte AUSSCHLIESSLICH mit einem JSON-Objekt (ohne Markdown, ohne Erklärtext) mit genau diesen Feldern:\n{\"title\": \"Kurzer Name des Objekts\", \"category\": \"Kategorie (z. B. Haushalt, Werkzeug, Elektronik, Natur, Lebensmittel, Sport, Kleidung, Verkehr, Medizin)\", \"summary\": \"3-5 Sätze: Was ist das Objekt und was macht es besonders?\", \"howItWorks\": \"4-8 Sätze: Wie funktioniert es? Erkläre das Funktionsprinzip Schritt für Schritt und mit echtem Fachwissen – nenne konkrete Zahlen, Materialien, physikalische oder technische Grundlagen, wo sie helfen.\", \"usage\": \"2-5 Sätze: Wofür wird es typischerweise verwendet – auch weniger offensichtliche Einsatzbereiche\", \"practical\": \"2-4 praktische Tipps für den Alltag, jeder als vollständiger Satz\", \"facts\": [\"3-5 interessante, konkrete Fakten (Zahlen, Geschichte, Herstellung, Besonderheiten)\"], \"safety\": \"Sicherheitshinweise (bei Lebensmitteln, Pilzen, Medikamenten, Chemikalien, Tieren, Werkzeugen: deutliche Warnung)\", \"uncertain\": true falls du dir nicht sicher bist, sonst false}\nDie genannten Umfänge sind Richtwerte nach unten: Lieber gründlicher und lehrreicher als dünn – die Qualität der Erklärung ist das Wichtigste. Folge dabei der Rollen-, Zielgruppen- und Vorwissen-Vorgabe aus dem Profil.\nWichtig: Alle Texte werden in der App zusätzlich laut vorgelesen (Sprachausgabe im Erklärvideo). Schreibe deshalb fließende, gesprochene Sätze: keine Aufzählungszeichen, keine Emojis, keine Abkürzungen („zum Beispiel“ statt „z.B.“, „etwa“ statt „ca.“), keine Klammern mit Beifügungen und keine Fachkürzel. Zahlen schreibe so, dass sie sich beim Vorlesen natürlich anhören."

    private fun promptFor(config: ProviderConfig): String {
        val lang = if (config.language == "de") {
            "Schreibe alles auf Deutsch.\n"
        } else {
            "Schreibe alle Inhalte auf " + languageName(config.language) +
                " – auch den Namen des Objekts (title) in dieser Sprache.\n"
        }
        // Abschluss-Erinnerung: Das lange Profil kann das Modell dazu verleiten,
        // frei zu antworten – deshalb noch einmal das JSON-Format einfordern.
        val jsonReminder =
            "Antworte ausschließlich mit dem JSON-Objekt – kein Erklärtext davor oder danach, kein Markdown.\n"
        return PROMPT + config.profilePrompt + lang + jsonReminder
    }

    /** Führt die Online-Analyse für ein einzelnes Foto mit dem gewählten Anbieter aus. */
    suspend fun analyze(imagePath: String, config: ProviderConfig): Explanation =
        analyze(listOf(imagePath), config)

    /**
     * Online-Analyse mit einem oder mehreren Fotos desselben Objekts (z. B. von
     * verschiedenen Seiten aufgenommen). Die KI kombiniert alle Bilder für die
     * Erkennung – das erste Foto gilt als Hauptansicht.
     */
    suspend fun analyze(imagePaths: List<String>, config: ProviderConfig): Explanation =
        withContext(Dispatchers.IO) {
            if (imagePaths.isEmpty()) throw IllegalStateException("Kein Foto für die Analyse vorhanden.")
            val dataUrls = imagePaths.map { path ->
                val bytes = File(path).readBytes()
                "data:image/jpeg;base64," +
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
            val content = when (config.provider) {
                "gemini" -> requestGemini(config, dataUrls)
                "anthropic" -> requestAnthropic(config, dataUrls)
                else -> requestChatCompletions(config, dataUrls)
            }
            parse(content)
        }

    /**
     * Rückfrage-Chat: Beantwortet eine Frage des Nutzers zur bereits erkannten
     * Entdeckung. Nutzt denselben Anbieter und API-Schlüssel wie die Foto-Analyse;
     * das Foto wird dabei NICHT erneut versendet – die bestehende Erklärung dient
     * als Kontext. `history` enthält bisherige Frage-Antwort-Paare, damit ein
     * echter Dialog mit Folgeverständnis entsteht.
     */
    suspend fun followUp(
        context: Context,
        d: Discovery,
        question: String,
        history: List<Pair<String, String>> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        val config = loadConfig(context)
            ?: throw IllegalStateException(
                "Kein API-Schlüssel hinterlegt – stelle zuerst einen Anbieter in den Einstellungen ein."
            )
        val e = d.explanation
        val sb = StringBuilder()
        sb.append("Du bist der Erklärassistent der App „Wer Wie Was“. Der Nutzer hat ein Foto gemacht; die App hat folgendes Objekt erkannt.\n")
        sb.append("Erkanntes Objekt: ").append(e.title)
        if (e.category.isNotBlank()) sb.append(" (Kategorie: ").append(e.category).append(")")
        sb.append("\n\nBereits bekannte Analyse:\n")
        sb.append("Kurzbeschreibung: ").append(e.summary).append("\n")
        if (e.howItWorks.isNotBlank()) sb.append("Funktionsweise: ").append(e.howItWorks).append("\n")
        if (e.usage.isNotBlank()) sb.append("Verwendung: ").append(e.usage).append("\n")
        if (e.practical.isNotBlank()) sb.append("Praktische Tipps: ").append(e.practical).append("\n")
        if (e.facts.isNotEmpty()) sb.append("Fakten: ").append(e.facts.joinToString("; ")).append("\n")
        if (e.safety.isNotBlank()) sb.append("Sicherheit: ").append(e.safety).append("\n")
        if (e.uncertain) sb.append("Hinweis: Die Erkennung des Objekts war unsicher.\n")
        sb.append("\nBeantworte jetzt die Rückfrage des Nutzers kurz und klar auf " +
            languageName(config.language) + " (maximal etwa 120 Wörter). ")
        sb.append("Beziehe dich auf das erkannte Objekt und den bisherigen Analyse-Kontext. ")
        sb.append("Wenn eine Frage nichts mit dem Objekt zu tun hat, weise freundlich darauf hin. ")
        sb.append("Sicherheitsrelevante Warnungen (z. B. bei Lebensmitteln, Pilzen, Chemikalien) bleiben ernst.\n")
        sb.append(config.profilePrompt)
        val system = sb.toString()
        when (config.provider) {
            "gemini" -> followUpGemini(config, system, question, history)
            "anthropic" -> followUpAnthropic(config, system, question, history)
            else -> followUpChatCompletions(config, system, question, history)
        }
    }

    /** Rückfrage über chat/completions (Mistral, OpenAI-kompatibel, OpenRouter, Grok). */
    private fun followUpChatCompletions(
        config: ProviderConfig,
        system: String,
        question: String,
        history: List<Pair<String, String>>
    ): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", system))
        for ((q, a) in history) {
            messages.put(JSONObject().put("role", "user").put("content", q))
            messages.put(JSONObject().put("role", "assistant").put("content", a))
        }
        messages.put(JSONObject().put("role", "user").put("content", question))
        val body = JSONObject()
            .put("model", config.model)
            .put("messages", messages)
        if (config.provider == "openai") {
            body.put("max_completion_tokens", 1600)
        } else {
            body.put("temperature", 0.3)
            body.put("max_tokens", 1600)
        }
        val text = postJsonAutoTokens(
            config.baseUrl + "/chat/completions", body,
            listOf("max_completion_tokens", "max_tokens"),
            mapOf("Authorization" to "Bearer " + config.apiKey)
        )
        val o = JSONObject(text)
        if (o.has("error")) {
            val err = o.optJSONObject("error")
            throw IllegalStateException("KI-API-Fehler: " + (err?.optString("message") ?: err.toString()))
        }
        return o.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
    }

    /** Rückfrage über Google Gemini (generateContent, rein textbasiert). */
    private fun followUpGemini(
        config: ProviderConfig,
        system: String,
        question: String,
        history: List<Pair<String, String>>
    ): String {
        val contents = JSONArray()
        for ((q, a) in history) {
            contents.put(
                JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", q)))
            )
            contents.put(
                JSONObject().put("role", "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", a)))
            )
        }
        contents.put(
            JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", question)))
        )
        val body = JSONObject()
            .put("contents", contents)
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
            )
            .put(
                "generationConfig",
                JSONObject().put("temperature", 0.3).put("maxOutputTokens", 1600)
            )
        val url = config.baseUrl + "/models/" + config.model + ":generateContent?key=" + config.apiKey
        val text = postJsonAutoTokens(url, body, listOf("maxOutputTokens"))
        val o = JSONObject(text)
        if (o.has("error")) {
            val err = o.optJSONObject("error")
            throw IllegalStateException("Gemini-Fehler: " + (err?.optString("message") ?: err.toString()))
        }
        val parts = o.getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            sb.append(parts.getJSONObject(i).optString("text"))
        }
        return sb.toString()
    }

    /** Rückfrage über die Anthropic Messages API (rein textbasiert). */
    private fun followUpAnthropic(
        config: ProviderConfig,
        system: String,
        question: String,
        history: List<Pair<String, String>>
    ): String {
        val messages = JSONArray()
        for ((q, a) in history) {
            messages.put(JSONObject().put("role", "user").put("content", q))
            messages.put(JSONObject().put("role", "assistant").put("content", a))
        }
        messages.put(JSONObject().put("role", "user").put("content", question))
        val body = JSONObject()
            .put("model", config.model)
            .put("max_tokens", 1600)
            .put("temperature", 0.3)
            .put("system", system)
            .put("messages", messages)
        val text = postJsonAutoTokens(
            config.baseUrl + "/messages", body,
            listOf("max_tokens"),
            mapOf(
                "x-api-key" to config.apiKey,
                "anthropic-version" to "2023-06-01"
            )
        )
        val o = JSONObject(text)
        if (o.has("error")) {
            val err = o.optJSONObject("error")
            throw IllegalStateException("Claude-Fehler: " + (err?.optString("message") ?: err.toString()))
        }
        val contentArr = o.getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until contentArr.length()) {
            val block = contentArr.getJSONObject(i)
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        return sb.toString()

    }

    /** Mistral und alle OpenAI-kompatiblen APIs (chat/completions, Bilder als Data-URLs). */
    private fun requestChatCompletions(config: ProviderConfig, dataUrls: List<String>): String {
        val textPart = JSONObject().put("type", "text").put("text", promptFor(config))
        val contentParts = JSONArray().put(textPart)
        dataUrls.forEach { url ->
            // Wichtig: "image_url" muss ein OBJEKT mit Feld "url" sein – ein nackter
            // String führt bei der API zu „HTTP 400 – Invalid type for image_url“.
            contentParts.put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", url))
            )
        }
        val message = JSONObject().put("role", "user").put("content", contentParts)
        val messages = JSONArray().put(message)
        val body = JSONObject()
            .put("model", config.model)
            .put("messages", messages)
        if (config.provider == "openai") {
            // Aktuelle GPT-Modelle: max_completion_tokens statt max_tokens,
            // niedrige Temperature wird nicht mehr unterstützt.
            body.put("max_completion_tokens", 4000)
        } else {
            body.put("temperature", 0.2)
            body.put("max_tokens", 4000)
        }
        val text = postJsonAutoTokens(
            config.baseUrl + "/chat/completions", body,
            listOf("max_completion_tokens", "max_tokens"),
            mapOf("Authorization" to "Bearer " + config.apiKey)
        )
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

    /** Google Gemini (generateContent, Bilder als inline_data). */
    private fun requestGemini(config: ProviderConfig, dataUrls: List<String>): String {
        val textPart = JSONObject().put("text", promptFor(config))
        val parts = JSONArray().put(textPart)
        dataUrls.forEach { url ->
            val base64 = url.substringAfter("base64,")
            val inlineData = JSONObject().put("mime_type", "image/jpeg").put("data", base64)
            parts.put(JSONObject().put("inline_data", inlineData))
        }
        val content = JSONObject().put("parts", parts)
        val contents = JSONArray().put(content)
        val generationConfig = JSONObject().put("temperature", 0.2)
        val body = JSONObject()
            .put("contents", contents)
            .put("generationConfig", generationConfig)
        val url = config.baseUrl + "/models/" + config.model + ":generateContent?key=" + config.apiKey
        val text = postJsonAutoTokens(url, body, listOf("maxOutputTokens"))
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

    /** Anthropic Claude (Messages API, Bilder als Base64-Quellen). */
    private fun requestAnthropic(config: ProviderConfig, dataUrls: List<String>): String {
        val textPart = JSONObject().put("type", "text").put("text", promptFor(config))
        val contentParts = JSONArray().put(textPart)
        dataUrls.forEach { url ->
            val base64 = url.substringAfter("base64,")
            val source = JSONObject()
                .put("type", "base64")
                .put("media_type", "image/jpeg")
                .put("data", base64)
            contentParts.put(JSONObject().put("type", "image").put("source", source))
        }
        val message = JSONObject().put("role", "user").put("content", contentParts)
        val body = JSONObject()
            .put("model", config.model)
            .put("max_tokens", 4000)
            .put("temperature", 0.2)
            .put("messages", JSONArray().put(message))
        val text = postJsonAutoTokens(
            config.baseUrl + "/messages", body,
            listOf("max_tokens"),
            mapOf(
                "x-api-key" to config.apiKey,
                "anthropic-version" to "2023-06-01"
            )
        )
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

    /** Gemeinsamer HTTP-Client mit harten Timeouts: HttpURLConnection hatte kein
     *  Write-Timeout – ein steckengebliebener Upload konnte die Analyse endlos
     *  blockieren („App hängt“). callTimeout begrenzt jetzt den kompletten Aufruf
     *  inkl. Verbindung, Upload und Antwort: Jede Anfrage endet garantiert mit
     *  Ergebnis oder klarer Fehlermeldung. */
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()

    /**
     * Führt einen JSON-POST aus und liefert den Antwort-Text. HTTP-Fehler,
     * Timeouts und Netz-Ausfälle werden als verständliche Meldung gemeldet –
     * die Analyse bleibt nie länger als das Timeout hängen.
     */
    private fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): String {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .apply { for ((k, v) in headers) header(k, v) }
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val code = response.code
                    if (code == 402) {
                        // OpenRouter-402: Guthaben deckt das angefragte max_tokens nicht
                        // („… can only afford N“) – gesondert markieren, damit
                        // postJsonAutoTokens das Limit senken und wiederholen kann.
                        throw PaymentRequiredException(
                            Regex("can only afford (\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull(),
                            "Guthaben des KI-Dienstes reicht für die angefragte Antwortlänge nicht aus (HTTP 402)."
                        )
                    }
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
            }
        } catch (e: InterruptedIOException) {
            // SocketTimeoutException & Co.: Verbindung oder Antwort dauerte zu lange
            throw IllegalStateException(
                "Zeitüberschreitung: Der KI-Dienst hat zu lange nicht geantwortet. " +
                    "Prüfe deine Internetverbindung und versuche es erneut."
            )
        } catch (e: UnknownHostException) {
            throw IllegalStateException(
                "Keine Internetverbindung (Server nicht erreichbar). " +
                    "Prüfe WLAN/Mobilfunk und versuche es erneut."
            )
        } catch (e: IOException) {
            throw IllegalStateException(
                "Verbindung zum KI-Dienst fehlgeschlagen: " +
                    (e.message ?: e.javaClass.simpleName)
            )
        }
    }

    /** HTTP 402 vom KI-Dienst: Das Guthaben deckt das angefragte max_tokens nicht;
     *  affordableTokens = leistbares Token-Limit aus der Fehlermeldung (falls parsbar). */
    private class PaymentRequiredException(val affordableTokens: Int?, message: String) :
        IllegalStateException(message)

    /**
     * OpenRouter und ähnliche Dienste prüfen VOR dem Aufruf, ob das angefragte
     * max_tokens vom Guthaben gedeckt ist, und lehnen sonst mit HTTP 402 ab
     * („You requested up to 4000 tokens, but can only afford 1754“). Diese
     * Funktion wiederholt die Anfrage dann automatisch mit gesenktem
     * Token-Limit: Die Analyse liefert eine kürzere Antwort, statt ganz zu
     * scheitern. Ist auch das kleinste leistbare Limit (400) erreicht, gibt es
     * eine klare Guthaben-Meldung.
     */
    private fun postJsonAutoTokens(
        url: String,
        body: JSONObject,
        tokenFields: List<String>,
        headers: Map<String, String> = emptyMap()
    ): String {
        try {
            return postJson(url, body.toString(), headers)
        } catch (e: PaymentRequiredException) {
            var budget = e.affordableTokens?.let { (it * 0.9).toInt() } ?: 1000
            while (budget >= 400) {
                for (f in tokenFields) body.put(f, budget)
                try {
                    return postJson(url, body.toString(), headers)
                } catch (e2: PaymentRequiredException) {
                    var next = e2.affordableTokens?.let { (it * 0.9).toInt() } ?: (budget / 2)
                    if (next >= budget) next = budget / 2
                    budget = next
                }
            }
            throw IllegalStateException(
                "Guthaben des KI-Dienstes ist aufgebraucht (HTTP 402). " +
                    "Fülle Guthaben auf, wechsle Modell/Anbieter oder nutze eine Gratis-Alternative."
            )
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
        // Weiterführende Links werden exakt aus dem erkannten Objekt (Titel) gebaut –
        // so suchen die Links genau nach dem Objekt auf dem Foto; benötigt einen
        // Netzwerkzugriff, parse() läuft daher nur auf dem IO-Dispatcher.
        val base = Explanation(
            title = title,
            category = o.optString("category", ""),
            summary = o.optString("summary", ""),
            howItWorks = o.optString("howItWorks", ""),
            usage = o.optString("usage", ""),
            practical = o.optString("practical", ""),
            facts = facts,
            safety = o.optString("safety", ""),
            links = emptyList(),
            uncertain = o.optBoolean("uncertain", false),
            source = "online"
        )
        return base.copy(links = KnowledgeBase.buildPublicLinks(base))
    }
}








