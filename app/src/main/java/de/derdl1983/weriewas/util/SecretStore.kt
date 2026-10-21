package de.derdl1983.weriewas.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Verschlüsselte Ablage für Geheimnisse der App (z. B. die API-Schlüssel der
 * KI-Anbieter).
 *
 * Die Werte liegen in EncryptedSharedPreferences: Schlüssel- und Wertdaten werden
 * mit AES (Android-Keystore-Masterschlüssel) verschlüsselt und sind damit nicht
 * mehr im Klartext in der XML-Datei der Einstellungen lesbar.
 *
 * Robustheit: Ist der verschlüsselte Speicher defekt (z. B. nach Keystore-Problemen
 * nach einem Geräte-Wechsel), wird er einmalig neu angelegt. Schlägt auch das fehl,
 * fällt die App auf einen unverschlüsselten Speicher zurück, statt ganz zu
 * versagen – die Analyse bleibt so nutzbar, der Fall wird protokolliert.
 */
object SecretStore {

    private const val TAG = "WerWieWasSecrets"
    private const val FILE = "secret_settings"

    @Volatile
    private var cached: SharedPreferences? = null

    private fun encryptedPrefs(context: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val prefs = create(context.applicationContext)
            cached = prefs
            return prefs
        }
    }

    private fun create(context: Context): SharedPreferences {
        try {
            return newEncrypted(context)
        } catch (e: Exception) {
            Log.e(TAG, "Verschlüsselter Speicher defekt – wird neu angelegt", e)
        }
        // Einmaliger Versuch: defekte Datei löschen und frisch anlegen
        runCatching { context.deleteSharedPreferences(FILE) }
        return try {
            newEncrypted(context)
        } catch (e: Exception) {
            Log.e(TAG, "Verschlüsselung nicht verfügbar – Fallback auf Klartext-Speicher", e)
            context.getSharedPreferences(FILE + "_plain", Context.MODE_PRIVATE)
        }
    }

    private fun newEncrypted(context: Context): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            FILE,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Liest ein gespeichertes Geheimnis ("" wenn nicht vorhanden). */
    fun get(context: Context, key: String): String =
        runCatching { encryptedPrefs(context).getString(key, "") ?: "" }.getOrDefault("")

    /**
     * Speichert ein Geheimnis verschlüsselt; ein leerer Wert entfernt den Eintrag.
     */
    fun put(context: Context, key: String, value: String) {
        val v = value.trim()
        runCatching {
            val prefs = encryptedPrefs(context)
            if (v.isBlank()) {
                prefs.edit().remove(key).apply()
            } else {
                prefs.edit().putString(key, v).apply()
            }
        }.onFailure { Log.e(TAG, "Speichern von $key fehlgeschlagen", it) }
    }

    /** Entfernt ein gespeichertes Geheimnis. */
    fun remove(context: Context, key: String) {
        runCatching { encryptedPrefs(context).edit().remove(key).apply() }
            .onFailure { Log.e(TAG, "Entfernen von $key fehlgeschlagen", it) }
    }

    /**
     * Einmalige Migration: API-Schlüssel, die in früheren App-Versionen im
     * Klartext in den Einstellungen („settings“) lagen, werden in den
     * verschlüsselten Speicher verschoben und dort gelöscht.
     * Wird beim App-Start aufgerufen und läuft ohne Wirkung ins Leere,
     * sobald keine Klartext-Schlüssel mehr existieren.
     */
    fun migratePlainApiKeys(context: Context) {
        val plain = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val entries = plain.all
        val keys = entries.keys.filter { it.endsWith("_api_key") }
        for (k in keys) {
            val value = entries[k] as? String ?: ""
            if (value.isNotBlank()) {
                runCatching {
                    val prefs = encryptedPrefs(context)
                    if ((prefs.getString(k, "") ?: "").isBlank()) {
                        prefs.edit().putString(k, value).apply()
                    }
                }
            }
            // Klartext-Kopie in jedem Fall entfernen
            plain.edit().remove(k).apply()
        }
        if (keys.isNotEmpty()) {
            Log.i(TAG, "${keys.size} API-Schlüssel verschlüsselt migriert.")
        }
    }
}
