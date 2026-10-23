package de.derdl1983.weriewas.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
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
 *
 * Zugriffsschutz: Microsoft hat am 12.10.2026 auf das Sec-MS-GEC-Token Verfahren 2
 * umgestellt. Das alte v1-Verfahren (einfaches SHA-256) wird seitdem mit
 * 401 Unauthorized abgelehnt. Diese Implementierung erzeugt das v2-Token
 * (CS64-Prüfsumme + angepasstes SHA-256) und korrigiert automatisch eine
 * falsch gehende Geräte-Uhr anhand des Date-Headers der Server-Antwort.
 *
 * Der Token-Algorithmus wurde Zeile für gegen die Referenzimplementierung
 * (rany2/edge-tts, src/edge_tts/drm.py) validiert; Handshake-URL, Header und
 * Cookie entsprechen exakt dem echten Edge-Browser (communicate.py). Notfalls
 * wird die Server-Zeit zusätzlich über den Stimmlisten-Endpunkt ermittelt.
 *
 * Endpunkt-Wechsel Oktober 2026: Die Synthese läuft primär über den neuen
 * Host api.msedgeservices.com (Token als Ocp-Apim-Subscription-Key,
 * rany2/edge-tts PR #491); der alte speech.platform.bing.com-Host wird als
 * Fallback weiter versucht. Die Stimmliste antwortet noch auf dem alten Host.
 *
 * Stand 23.10.2026: Der alte Host führt den WebSocket-Handshake inzwischen gar
 * nicht mehr aus (HTTP 200 statt 101 – Endpunkt abgeschaltet) und dient nur
 * noch als letzter Fallback. Der neue Host verlangt die Sec-MS-GEC-Version des
 * echten Edge 155 („2-155.0.4283.45“) und rate-limitet Verbindungen hart
 * (HTTP 429: Burst von ~5 Handshakes, danach ~1 alle 3–4 s). Deshalb: aktuelle
 * Edge-155-Version im Token/User-Agent, ein 4-Sekunden-Verbindungstakt und
 * automatischer 429-Rückoff mit Retry-After.
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

    /**
     * Stimmen je Antwort-Sprache des KI-Profils: Die Online-Stimmen folgen
     * automatisch der im KI-Profil gewählten Antwort-Sprache (z. B. für den
     * Urlaub oder zum Sprachenlernen), ohne dass neue Stimmen gewählt werden
     * müssen. Alle Stimmen sind gratis.
     */
    val VOICES_BY_LANG: Map<String, List<EdgeVoice>> = mapOf(
        "de" to GERMAN_VOICES,
        "en" to listOf(
            EdgeVoice("en-US-AriaNeural", "Aria (weiblich, sehr natürlich)"),
            EdgeVoice("en-US-GuyNeural", "Guy (männlich, sehr natürlich)"),
            EdgeVoice("en-US-JennyNeural", "Jenny (weiblich)"),
            EdgeVoice("en-US-AndrewNeural", "Andrew (männlich)")
        ),
        "fr" to listOf(
            EdgeVoice("fr-FR-DeniseNeural", "Denise (weiblich, sehr natürlich)"),
            EdgeVoice("fr-FR-HenriNeural", "Henri (männlich, sehr natürlich)"),
            EdgeVoice("fr-FR-EloiseNeural", "Éloise (weiblich)")
        ),
        "es" to listOf(
            EdgeVoice("es-ES-ElviraNeural", "Elvira (weiblich, sehr natürlich)"),
            EdgeVoice("es-ES-AlvaroNeural", "Álvaro (männlich, sehr natürlich)")
        ),
        "tr" to listOf(
            EdgeVoice("tr-TR-EmelNeural", "Emel (weiblich, sehr natürlich)"),
            EdgeVoice("tr-TR-AhmetNeural", "Ahmet (männlich, sehr natürlich)")
        ),
        "it" to listOf(
            EdgeVoice("it-IT-ElsaNeural", "Elsa (weiblich, sehr natürlich)"),
            EdgeVoice("it-IT-DiegoNeural", "Diego (männlich, sehr natürlich)"),
            EdgeVoice("it-IT-IsabellaNeural", "Isabella (weiblich)")
        )
    )

    /** Alle Stimmen aller Sprachen (flach) – für die Anzeige des Stimmennamens. */
    val ALL_VOICES: List<EdgeVoice> by lazy { VOICES_BY_LANG.values.flatten() }

    /** Stimmenliste für eine Sprach-Kurzform ("de", "en", …); Fallback Deutsch. */
    fun voicesFor(lang: String): List<EdgeVoice> =
        VOICES_BY_LANG[lang.take(2)] ?: GERMAN_VOICES

    /**
     * Unterschiedliche Standard-Stimmen je Sprache:
     * slot 0 = Moderator, slot 1 = Erklärbär.
     */
    fun defaultVoiceFor(lang: String, slot: Int): String {
        val list = voicesFor(lang)
        return (list.getOrNull(slot.coerceIn(0, list.size - 1)) ?: list.first()).id
    }

    /**
     * Löst die gespeicherte Stimme für die Antwort-Sprache des KI-Profils auf:
     * Passt die gespeicherte Stimme zur gewählten Sprache, bleibt sie erhalten;
     * gehört sie zu einer anderen Sprache, greift automatisch eine passende
     * Standard-Stimme der neuen Sprache. Bleibt die gespeicherte Stimme leer,
     * bleibt Edge-TTS ausgen (wie vom Nutzer in den Einstellungen gewählt).
     */
    fun resolveVoice(stored: String, lang: String, slot: Int): String {
        if (stored.isBlank()) return ""
        val l = lang.take(2)
        return if (stored.startsWith(l + "-") || !VOICES_BY_LANG.containsKey(l)) stored
        else defaultVoiceFor(l, slot)
    }

    fun labelFor(id: String): String =
        ALL_VOICES.firstOrNull { it.id == id }?.label ?: id

    /**
     * Räumt einen Anzeigetext für die Sprachausgabe auf: entfernt
     * Aufzählungszeichen wie „•“, „-“ oder „*“ am Zeilenanfang, damit sie
     * nicht mit vorgelesen werden. Anzeigetext und Untertitel bleiben
     * davon unberührt – die Bereinigung gilt nur dem gesprochenen Text.
     */
    fun speakableText(text: String): String =
        text.replace(Regex("(?m)^[ \\t]*[•·\\-–*]+[ \\t]+"), "")
            .replace("•", "")
            .replace(Regex("[ \\t]{2,}"), " ")
            .trim()

    /**
     * Sprechpausen-Faktor je nach Zielgruppe des KI-Profils: Das
     * Senioren-Profil erhält deutlich mehr Atempause zwischen den Sätzen
     * (SSML-Breaks 1,5-fach), alle anderen Zielgruppen die Standard-Pausen.
     */
    fun pauseScaleFor(context: Context): Float =
        if (context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("ai_audience", "all") == "senior") 1.5f else 1f

    /**
     * Löscht abgelegte Sprachdateien aus dem App-Cache, die älter als
     * 24 Stunden sind. Wird beim App-Start aufgerufen.
     */
    fun pruneCache(context: Context) {
        try {
            val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            context.cacheDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.startsWith("www_") && file.lastModified() < cutoff) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            // Cache-Bereinigung ist optional
        }
    }

    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491A6F4"
    private const val WIN_EPOCH = 11644473600L
    // Edge 155 sendet „Sec-MS-GEC-Version=2-155.0.4283.45“; mit der alten
    // 143er-Version wurde der Handshake auf dem neuen Host gedrosselt/abgelehnt.
    private const val CHROMIUM_FULL_VERSION = "155.0.4283.45"
    // User-Agent des echten Edge 155 (erfasst per edge://net-export,
    // rany2/edge-tts Issue #490).
    private val EDGE_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36 Edg/155.0.0.0"

    // Verbindungstakt: Der neue Host erlaubt nur einen Burst von ~5
    // WebSocket-Handshakes und danach etwa einen alle 3–4 Sekunden (HTTP 429,
    // Token-Bucket). Serien-Synthesen (Video-Export mit 7–8 Szenen) werden
    // deshalb pro Verbindung auf 4 Sekunden Abstand getaktet.
    private val connectPaceLock = Any()
    private var lastConnectAt = 0L

    /** Wartet ggf., bis der nächste WebSocket-Handshake wieder erlaubt ist. */
    private fun paceConnection() {
        synchronized(connectPaceLock) {
            val waitMs = lastConnectAt + 4000L - System.currentTimeMillis()
            if (waitMs > 0) {
                try {
                    Thread.sleep(waitMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            lastConnectAt = System.currentTimeMillis()
        }
    }

    // Seit Oktober 2026 (Edge 155) lehnt der alte speech.platform.bing.com-Host
    // den Synthese-WebSocket in vielen Regionen mit 401/403 ab; Edge synthetisiert
    // jetzt über api.msedgeservices.com, der dasselbe Token als
    // Ocp-Apim-Subscription-Key akzeptiert (rany2/edge-tts PR #491). Der alte
    // Host bleibt als Fallback für Regionen vor der Umstellung.
    private const val WSS_URL_NEW =
        "wss://api.msedgeservices.com/tts/cognitiveservices/websocket/v1"
    private const val WSS_URL_LEGACY =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"

    // ---------- Konstanten des Sec-MS-GEC-v2-Verfahrens ----------
    // Wichtig: Alle Hex-Konstanten werden über toLong(16) geparst.
    // String.toInt(16) würde bei Werten ab 0x80000000 eine NumberFormatException
    // auslösen – das ließ die App beim ersten Öffnen der Stimmenauswahl abstürzen.
    // Startzustand der angepassten SHA-256-Variante.
    private val SHA256_INITIAL = "6A09E667 BB67AE85 3C6EF372 A54FF53A 510E527F 9B05688C 1F83D9AB 5BE0CD19"
        .split(" ").map { it.toLong(16).toInt() }.toIntArray()
    private val SHA256_STATE = "FD9FBEA2 91243CE7 BE499094 CD2E1E0A 6C006F49 04BD35AB FEB0A7E4 03EFFBD2"
        .split(" ").map { it.toLong(16).toInt() }.toIntArray()
    private val SHA256_SCHEDULE = "EB24BF9E 1558691F A0C09EE2 0CFA32BA A622EE8C D1DD9491 C0DD25A0 F9E70AB6 F0C31573 7058CE4E 7188E606 00E5AB33 D258B060 72766564"
        .split(" ").map { it.toLong(16).toInt() }.toIntArray()
    private val SHA256_ROUND_CONSTANTS = ("428A2F98 71374491 B5C0FBCF E9B5DBA5 3956C25B 59F111F1 923F82A4 AB1C5ED5" +
        " D807AA98 12835B01 243185BE 550C7DC3 72BE5D74 80DEB1FE 9BDC06A7 C19BF174" +
        " E49B69C1 EFBE4786 0FC19DC6 240CA1CC 2DE92C6F 4A7484AA 5CB0A9DC 76F988DA" +
        " 983E5152 A831C66D B00327C8 BF597FC7 C6E00BF3 D5A79147 06CA6351 14292967" +
        " 27B70A85 2E1B2138 4D2C6DFC 53380D13 650A7354 766A0ABB 81C2C92E 92722C85" +
        " A2BFE8A1 A81A664B C24B8B70 C76C51A3 D192E819 D6990624 F40E3585 106AA070" +
        " 19A4C116 1E376C08 2748774C 34B0BCB5 391C0CB3 4ED8AA4A 5B9CCA4F 682E6FF3" +
        " 748F82EE 78A5636F 84C87814 8CC70208 90BEFFFA A4506CEB BEF9A3F7 C67178F2")
        .split(" ").map { it.toLong(16).toInt() }.toIntArray()
    private val SHA256_ADJUSTMENT = 0xC9DC06A7.toInt()
    private val SIGMA0_ADJUSTMENT = 0x059C0B80.toInt()
    private val SIGMA1_ADJUSTMENT = 0x000B92C0.toInt()

    private val SHA256_BLOCK_CONSTANTS: IntArray = SHA256_ROUND_CONSTANTS.copyOf().also {
        it[14] = SHA256_ADJUSTMENT
    }

    private val CS64_COEFFICIENTS: List<Pair<Int, Int>> = ("E82E91B1 B32700D0 44DE24C6 88DFF51D 3BEB65BC 29514038 C89C5933 B328CBDF" +
        " 8D1EC211 D405B4D5 314EF5D5 26E55F3C E92491B1 607801C6 F0A5B9D5 82B2C1FB" +
        " 06064CF3 FA51A3DA DA79FA60 207CC464 BED20560 3C3C74F3 89F9C9F8 D2D14B61" +
        " 35FA38C6 BB04A2C1 CB49E3C6 C5626167")
        .split(" ").map { it.toLong(16).toInt() }.chunked(2).map { p -> p[0] to p[1] }
    private val CS64_FACTOR = 0x82893933.toInt()
    private val CS64_ADJUSTMENT0 = 0xF2F90B1A.toInt()
    private val CS64_ADJUSTMENT1 = 0x0B1A0000.toInt()

    // ---------- Hilfsfunktionen (32-Bit-Arithmetik, Überlauf entspricht Modulo 2^32) ----------
    private fun rol16(v: Int): Int = (v shl 16) or (v ushr 16)

    private fun sigma0(v: Int): Int =
        (v ushr 7) xor (v shl 25) xor (v ushr 18) xor (v shl 14) xor (v ushr 3)

    private fun sigma1(v: Int): Int =
        (v ushr 17) xor (v shl 15) xor (v ushr 19) xor (v shl 13) xor (v ushr 10)

    private fun rotr(v: Int, n: Int): Int = (v ushr n) or (v shl (32 - n))

    private fun leU32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or
            ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or
            ((b[off + 3].toInt() and 0xff) shl 24)

    private fun beU32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 24) or
            ((b[off + 1].toInt() and 0xff) shl 16) or
            ((b[off + 2].toInt() and 0xff) shl 8) or
            (b[off + 3].toInt() and 0xff)

    /** Erweitert die SHA-256-Wortliste (Standard-Sigma-Funktionen). */
    private fun expandSchedule(words: IntArray, firstWord: Int) {
        for (index in firstWord until 64) {
            val x = words[index - 2]
            val y = words[index - 15]
            val s1 = (x ushr 17) xor (x shl 15) xor (x ushr 19) xor (x shl 13) xor (x ushr 10)
            val s0 = (y ushr 7) xor (y shl 25) xor (y ushr 18) xor (y shl 14) xor (y ushr 3)
            words[index] = s1 + words[index - 7] + s0 + words[index - 16]
        }
    }

    /** Führt SHA-256-Runden aus (beginnt ggf. mitten im ersten Block). */
    private fun shaRounds(state: IntArray, words: IntArray, firstRound: Int, constants: IntArray): IntArray {
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        for (i in firstRound until 64) {
            val s1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
            // Auswahl-Funktion der Referenz: g ^ (e & (f ^ g))
            // (entspricht (e & f) ^ (~e & g); der Term ^ (e & g) darf nicht fehlen!)
            val ch = g xor (e and (f xor g))
            val t1 = h + s1 + ch + constants[i] + words[i]
            val s0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
            val maj = (a and b) or (c and (a or b))
            val t2 = s0 + maj
            h = g; g = f; f = e; e = d + t1
            d = c; c = b; b = a; a = t1 + t2
        }
        return intArrayOf(a, b, c, d, e, f, g, h)
    }

    /** Anfangszustand der angepassten SHA-256-Variante aus den ersten 7 Bytes. */
    private fun sha256Init(data: ByteArray): IntArray {
        val words = IntArray(64)
        words[14] = ((data[0].toInt() and 0xff) shl 16) or
            ((data[1].toInt() and 0xff) shl 8) or
            (data[2].toInt() and 0xff)
        words[15] = beU32(data, 3)
        for (index in 16..29) {
            var value = SHA256_SCHEDULE[index - 16]
            if (index - 2 >= 14) {
                value += if (index == 16) sigma1(words[14]) xor SIGMA1_ADJUSTMENT else sigma1(words[index - 2])
            }
            if (index - 7 >= 14) value += words[index - 7]
            if (index - 15 >= 14) {
                value += if (index == 29) sigma0(words[14]) xor SIGMA0_ADJUSTMENT else sigma0(words[index - 15])
            }
            words[index] = value
        }
        words[30] = sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14] +
            SHA256_ADJUSTMENT - SHA256_ROUND_CONSTANTS[14]
        expandSchedule(words, 31)
        val working = shaRounds(SHA256_STATE, words, 14, SHA256_BLOCK_CONSTANTS)
        val out = IntArray(8)
        for (i in 0 until 8) out[i] = SHA256_INITIAL[i] + working[i]
        return out
    }

    /** Angepasstes SHA-256 über die Token-Daten (Tickzahl + Trusted-Client-Token). */
    private fun sha256Digest(data: ByteArray): ByteArray {
        if (data.size < 7) throw IllegalArgumentException("Token-Daten zu kurz")
        val length = 57 + data.size
        val zeroCount = (((55 - length) % 64) + 64) % 64
        val padded = ByteArray(data.size - 7 + 1 + zeroCount + 8)
        var pos = 0
        for (i in 7 until data.size) { padded[pos] = data[i]; pos++ }
        padded[pos] = 0x80.toByte(); pos++
        for (i in 0 until zeroCount) { padded[pos] = 0; pos++ }
        val bits = (length * 8).toLong()
        for (i in 0 until 8) { padded[pos] = (bits ushr (56 - 8 * i)).toByte(); pos++ }
        var state = sha256Init(data)
        var offset = 0
        while (offset < padded.size) {
            val words = IntArray(64)
            for (i in 0 until 16) words[i] = beU32(padded, offset + 4 * i)
            expandSchedule(words, 16)
            val working = shaRounds(state, words, 0, SHA256_ROUND_CONSTANTS)
            for (i in 0 until 8) state[i] = state[i] + working[i]
            offset += 64
        }
        val out = ByteArray(32)
        for (i in 0 until 8) {
            val v = state[i]
            out[4 * i] = (v ushr 24).toByte()
            out[4 * i + 1] = (v ushr 16).toByte()
            out[4 * i + 2] = (v ushr 8).toByte()
            out[4 * i + 3] = v.toByte()
        }
        return out
    }

    /** Little-Endian-Wörter, auf gerade Anzahl aufgefüllt. */
    private fun wordsLe4Even(data: ByteArray): List<Int> {
        val pad = (4 - data.size % 4) % 4
        val padded = data + ByteArray(pad)
        val words = ArrayList<Int>()
        var i = 0
        while (i + 4 <= padded.size) { words.add(leU32(padded, i)); i += 4 }
        if (words.size % 2 == 1) words.add(0)
        return words
    }

    /** CS64-Prüfsumme: Rückgabe sind zwei 32-Bit-Wörter (Little-Endian zu hex). */
    private fun cs64Digest(data: ByteArray, digest: ByteArray): IntArray {
        val keyA = leU32(digest, 0) or 1
        val keyB = leU32(digest, 4) or 1
        val factors = intArrayOf(keyA * CS64_FACTOR, keyB * CS64_FACTOR)
        val terms = ArrayList<Int>()
        val offsets = ArrayList<Int>()
        CS64_COEFFICIENTS.forEachIndexed { index, (coef, off) ->
            terms.add(coef * factors[index % 2])
            offsets.add(off)
        }
        val prefixed = ByteArray(data.size + 1)
        prefixed[0] = 0
        System.arraycopy(data, 0, prefixed, 1, data.size)
        wordsLe4Even(prefixed).forEachIndexed { index, word ->
            var term = if (index % 2 == 0) word * keyA else word * keyB
            var offset = rol16(word) * (if (index % 2 == 0) 0xEF0569FB.toInt() else 0xC31713DB.toInt())
            if (index == 0) {
                term += CS64_ADJUSTMENT0 * factors[0]
                offset += CS64_ADJUSTMENT1
            }
            terms.add(term)
            offsets.add(offset)
        }
        var swapAcc = 0; var swapLast = 0; var state = 0; var acc = 0
        var i = 0
        while (i < terms.size) {
            val x = terms[i]; val y = terms[i + 1]
            var t = rol16(offsets[i] + x)
            var q = t * 0x76F50F34.toInt()
            swapAcc += rol16(q) * 0xC3EFEA97.toInt() + q
            t = rol16(offsets[i + 1] + y)
            q = t * 0x1881CE3C.toInt()
            swapLast = rol16(q) * 0x35BD1EC9.toInt() + q
            swapAcc += swapLast
            state = state * keyA + x
            state = rol16(state) * 0xCF98B111.toInt()
            state = rol16(state) * 0x87085B9F.toInt()
            state = rol16(state) * 0x12CEB96D.toInt()
            state = rol16(state) * 0x257E1D83.toInt()
            acc += state
            t = state * keyB + y
            t = rol16(t) * 0xA27416F5.toInt()
            t = rol16(t) * 0xD38396FF.toInt()
            // Reihenfolge der Referenz: rol16(rol16(t) * 0x7C932B89) * 0xBFA49F69
            state = rol16(rol16(t) * 0x7C932B89.toInt()) * 0xBFA49F69.toInt()
            acc += state
            i += 2
        }
        return intArrayOf(swapLast xor state, swapAcc xor acc)
    }

    /**
     * Erzeugt das Sec-MS-GEC-Token nach dem seit 12.10.2026 gültigen Verfahren 2
     * (Format: CS64-Prüfsumme + SHA-256, jeweils hexadezimal, durch Bindestrich getrennt).
     * Der Zeitstempel wird auf ein 5-Minuten-Fenster gerundet; skewSeconds korrigiert
     * eine falsch gehende Geräte-Uhr.
     */
    fun secMsGecV2(skewSeconds: Long = 0L): String {
        var ticks = System.currentTimeMillis() / 1000L + skewSeconds
        ticks += WIN_EPOCH
        ticks -= ticks % 300
        ticks *= 10000000L
        val data = (ticks.toString() + TRUSTED_CLIENT_TOKEN).toByteArray(Charsets.US_ASCII)
        val digest = sha256Digest(data)
        val cs = cs64Digest(data, digest)
        val sb = StringBuilder()
        for (w in cs) {
            var v = w
            for (i in 0 until 4) {
                val b = v and 0xff
                if (b < 16) sb.append('0')
                sb.append(Integer.toHexString(b).uppercase())
                v = v ushr 8
            }
        }
        sb.append('-')
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

    /** Zufällige MUID (32 hex-Zeichen, Großbuchstaben) wie die Edge-Referenz (DRM.generate_muid). */
    private fun muid(): String =
        UUID.randomUUID().toString().replace("-", "").uppercase()

    /** Fehler, bei dem ein Retry mit korrigierter Uhrzeit sinnvoll ist. */
    private class SkewException(message: String, val skewSeconds: Long) : IOException(message)

    /** Der Dienst hat die Verbindung gedrosselt (HTTP 429) – nach Wartezeit erneut versuchen. */
    private class RateLimitedException(message: String, val retryAfterMs: Long) : IOException(message)

    private class FailInfo(val code: Int?, val dateHeader: String?, val message: String?, val retryAfterHeader: String?)

    private fun parseServerDate(date: String?): Long? {
        if (date == null) return null
        return try {
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            fmt.parse(date)?.time?.div(1000L)
        } catch (e: Exception) {
            null
        }

    }

    /**
     * Holt die aktuelle Server-Zeit über den Stimmlisten-Endpunkt, falls die
     * abgewiesene Handshake-Antwort keinen Date-Header enthielt. Der Statuscode
     * ist dabei egal – jede Antwort trägt das Datum des Servers im Date-Header.
     */
    private fun fetchServerTimeSeconds(skewSeconds: Long): Long? {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        return try {
            val url = ("https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/voices/list"
                + "?trustedclienttoken=" + TRUSTED_CLIENT_TOKEN
                + "&Sec-MS-GEC=" + secMsGecV2(skewSeconds)
                + "&Sec-MS-GEC-Version=2-" + CHROMIUM_FULL_VERSION)
            val req = Request.Builder().url(url)
                .header("User-Agent", EDGE_USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "muid=" + muid() + ";")
                .build()
            client.newCall(req).execute().use { resp -> parseServerDate(resp.header("Date")) }
        } catch (e: Exception) {
            null
        } finally {
            client.dispatcher.executorService.shutdown()
        }
    }

    /**
     * Synthetisiert Text zu einer MP3-Datei (24 kHz, 48 kbit/s, mono).
     * Blockierend – im Hintergrund-Thread aufrufen. ratePercent: -50 bis +100.
     * ssml = true fügt nach Satz- und Gliederungszeichen natürliche Sprechpausen
     * ein (SSML-Breaks). Bei 401/403 wird die Geräte-Uhr anhand der Server-Zeit
     * korrigiert und mehrfach erneut versucht; zusätzlich läuft nach dem neuen Host
     * (api.msedgeservices.com) immer noch der alte Host als Fallback.
     */
    fun synthesizeToMp3(text: String, voice: String, ratePercent: Int, outFile: File, ssml: Boolean = false, pauseScale: Float = 1f): File {
        var lastSkew: SkewException? = null
        var lastHard: IOException? = null
        var hardNew: IOException? = null
        var rateLimited: RateLimitedException? = null
        // Erst der neue Host (api.msedgeservices.com), danach der alte:
        // Der alte Host lehnt den Handshake inzwischen komplett ab (HTTP 200
        // statt 101) und ist nur noch ein letzter Fallback. Beim neuen Host
        // wird zusätzlich HTTP 429 (Drosselung) mit Wartezeit + Neuversuch
        // behandelt, statt sofort aufzugeben.
        for (useLegacy in booleanArrayOf(false, true)) {
            var skew = 0L
            var attempt = 0
            while (attempt < 3) {
                try {
                    return synthesizeOnce(text, voice, ratePercent, outFile, skew, useLegacy, ssml, pauseScale)
                } catch (e: RateLimitedException) {
                    // Gedrosselt (HTTP 429): Retry-After (oder 4 s) warten und
                    // auf demselben Host erneut versuchen.
                    rateLimited = e
                    try {
                        Thread.sleep(e.retryAfterMs)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("Sprachsynthese unterbrochen.")
                    }
                    attempt++
                } catch (e: SkewException) {
                    skew = e.skewSeconds
                    lastSkew = e
                    attempt++
                } catch (e: IOException) {
                    // Ohne nutzbaren Skew (Netzwerk-Fehler) → anderen Host probieren
                    if (!useLegacy && hardNew == null) hardNew = e
                    lastHard = e
                    break
                }
            }
        }
        // Ursache bevorzugen, die wirklich weiterhilft: Drosselung oder der
        // Fehler des neuen (primären) Hosts statt der bekannte 200er-Totgang
        // des Legacy-Hosts.
        val cause = rateLimited?.message ?: hardNew?.message ?: lastHard?.message ?: lastSkew?.message ?: "unbekannter Fehler"
        throw IOException("Sprachsynthese fehlgeschlagen: " + cause)
    }

    private fun synthesizeOnce(text: String, voice: String, ratePercent: Int, outFile: File, skewSeconds: Long, useLegacyHost: Boolean, ssml: Boolean, pauseScale: Float = 1f): File {
        // Proaktiver Verbindungstakt gegen das HTTP-429-Rate-Limit des neuen
        // Hosts (Token-Bucket: ~5 im Burst, dann ~1 alle 3–4 Sekunden).
        paceConnection()
        // Neuer Host: Token als Ocp-Apim-Subscription-Key (Referenz: rany2/edge-tts
        // PR #491, constants.py/communicate.py). Alter Host: TrustedClientToken.
        val secMsGec = secMsGecV2(skewSeconds)
        val url = if (useLegacyHost) (
            WSS_URL_LEGACY
                + "?TrustedClientToken=" + TRUSTED_CLIENT_TOKEN
                + "&Sec-MS-GEC=" + secMsGec
                + "&Sec-MS-GEC-Version=2-" + CHROMIUM_FULL_VERSION
                + "&ConnectionId=" + requestId()
        ) else (
            WSS_URL_NEW
                + "?Ocp-Apim-Subscription-Key=" + TRUSTED_CLIENT_TOKEN
                + "&Sec-MS-GEC=" + secMsGec
                + "&Sec-MS-GEC-Version=2-" + CHROMIUM_FULL_VERSION
                + "&ConnectionId=" + requestId()
        )
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val audio = ByteArrayOutputStream()
        val done = CountDownLatch(1)
        val failure = AtomicReference<FailInfo?>(null)
        val ws = client.newWebSocket(
            // Header-Reihenfolge und -Inhalt wie der echte Edge-Browser
            // (Accept-Encoding fehlte zuvor – OkHttp sendet nur „gzip“).
            Request.Builder().url(url)
                .header("User-Agent", EDGE_USER_AGENT)
                .header("Accept-Encoding", "gzip, deflate, br, zstd")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("Cookie", "muid=" + muid() + ";")
                .build(),
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
                    var escaped = speakableText(text)
                        .replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                    // SSML-Pausen & Betonung: Nach Satz- und Gliederungszeichen
                    // kurze Sprechpausen einfügen – klingt deutlich natürlicher
                    // und lässt den Erklärtext ruhiger und verständlicher wirken.
                    if (ssml) {
                        // Sprechpausen je Zielgruppe skalieren (Senioren-Profil:
                        // 1,5-fache Atempausen, siehe pauseScaleFor) – klingt
                        // geduldiger und macht Sätze besser verständlich.
                        val sentenceMs = (320 * pauseScale.coerceIn(1f, 2f)).toInt()
                        val commaMs = (150 * pauseScale.coerceIn(1f, 2f)).toInt()
                        escaped = escaped
                            .replace(Regex("([.!?…])(?=\\s|$)"), "$1<break time='" + sentenceMs + "ms'/>")
                            .replace(Regex("([,;:])(?=\\s|$)"), "$1<break time='" + commaMs + "ms'/>")
                    }
                    val rate = (if (ratePercent >= 0) "+" + ratePercent else ratePercent.toString()) + "%"
                    // Sprach-Tag der Stimme folgen (z. B. "en-US-AriaNeural" → "en-US"),
                    // damit die Pausen-Verarbeitung zur Stimmsprache passt.
                    val voiceLang = voice.split("-").take(2).joinToString("-").ifBlank { "de-DE" }
                    val ssml = ("<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='" + voiceLang + "'>"
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
                    failure.set(FailInfo(response?.code, response?.header("Date"), t.message, response?.header("Retry-After")))
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
            if (err != null) {
                val code = err.code
                if (code == 401 || code == 403) {
                    // Server-Zeit aus der Antwort ableiten; liefert der Server keinen
                    // Date-Header, wird sie über den Stimmlisten-Endpunkt nachgefragt.
                    val server = parseServerDate(err.dateHeader) ?: fetchServerTimeSeconds(skewSeconds)
                    val hint = if (code == 401) {
                        "Zugriff verweigert (401): Token oder Uhrzeit ungültig. " +
                            "Stelle sicher, dass Datum, Uhrzeit und Zeitzone deines Geräts " +
                            "korrekt sind (am besten „Datum und Uhrzeit automatisch“ aktivieren)."
                    } else {
                        "Zugriff verweigert (403): Der Dienst wurde vom Anbieter gesperrt."
                    }
                    val msg = "Verbindung fehlgeschlagen (HTTP " + code + "): " + hint
                    if (server != null) {
                        throw SkewException(msg, server - System.currentTimeMillis() / 1000L)
                    }
                    throw IOException(msg)
                }
                if (code == 429) {
                    // Drosselung (Token-Bucket): Retry-After beachten, sonst 4 s
                    // warten – danach wird auf demselben Host erneut versucht.
                    val retryAfterSec = err.retryAfterHeader?.trim()?.toLongOrNull()?.coerceIn(1L, 30L)
                    val waitMs = (retryAfterSec ?: 4L) * 1000L
                    throw RateLimitedException(
                        "Verbindung gedrosselt (HTTP 429): Der Dienst erlaubt nur wenige Anfragen in Folge – es wird kurz gewartet und erneut versucht.",
                        waitMs
                    )
                }
                val detail = if (code == 200) {
                    // OkHttp meldet hier: „Expected HTTP 101 response but was '200 OK'“.
                    // Der Server führt den Handshake nicht mehr aus (abgeschalteter
                    // Endpunkt, z. B. der alte Host) oder ein Netzwerk-/Hotspot-Filter
                    // antwortet an seiner Stelle.
                    "Der Server hat den WebSocket-Handshake abgelehnt (200 statt 101) – Endpunkt abgeschaltet oder Netzwerk-Filter aktiv"
                } else {
                    err.message ?: "unbekannter Fehler"
                }
                throw IOException(
                    "Verbindung fehlgeschlagen" +
                        (if (code != null) " (HTTP " + code + ")" else "") +
                        ": " + detail
                )
            }
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





