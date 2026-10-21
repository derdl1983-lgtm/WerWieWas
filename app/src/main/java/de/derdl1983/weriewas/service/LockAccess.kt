package de.derdl1983.weriewas.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import de.derdl1983.weriewas.MainActivity
import de.derdl1983.weriewas.R

/**
 * Schnellzugriff auf dem Sperrbildschirm:
 * Zeigt eine dauerhafte Benachrichtigung („Foto analysieren“), die auch auf dem
 * Sperrbildschirm sichtbar ist. Ein Tipp darauf öffnet die App direkt im
 * Kamera-Modus – ohne vorheriges Entsperren (siehe MainActivity#allowOverLockScreen).
 */
object LockAccess {

    /** Schlüssel in den Einstellungen (SharedPreferences „settings“). */
    const val PREF_KEY = "lock_notification"

    private const val CHANNEL_ID = "lock_access"
    private const val NOTIFICATION_ID = 42

    /** Prüft, ob Benachrichtigungen angezeigt werden dürfen (ab Android 13 nötig). */
    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Zeigt die Sperrbild-Benachrichtigung an – oder entfernt sie.
     * Wird beim App-Start und beim Umschalten in den Einstellungen aufgerufen.
     */
    fun sync(context: Context, enabled: Boolean) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!enabled || !canNotify(context)) {
            nm.cancel(NOTIFICATION_ID)
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Schnellzugriff Sperrbildschirm",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Zeigt die Foto-Benachrichtigung der App auf dem Sperrbildschirm."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)

        val intent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_CAMERA
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pending = PendingIntent.getActivity(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Wer Wie Was – Foto analysieren")
            .setContentText("Tippen und direkt fotografieren – auch vom Sperrbildschirm.")
            .setContentIntent(pending)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }
}
