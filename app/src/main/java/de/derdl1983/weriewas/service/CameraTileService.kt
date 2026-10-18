package de.derdl1983.weriewas.service

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import de.derdl1983.weriewas.MainActivity

/**
 * Schnelleinstellungs-Kachel „Wer Wie Was“:
 * ein Tipp öffnet die App direkt im Kamera-Modus.
 */
class CameraTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { tile ->
            tile.state = Tile.STATE_ACTIVE
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_CAMERA
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            pending.send()
        } catch (_: PendingIntent.CanceledException) {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(pending)
        }
    }
}
