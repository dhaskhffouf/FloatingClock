package com.minhthai.floatclock

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Ô bật/tắt nhanh đồng hồ trong thanh cài đặt nhanh. */
class ClockTileService : TileService() {

    override fun onStartListening() {
        refresh()
    }

    override fun onClick() {
        val prefs = Prefs(this)
        if (!prefs.enabled) {
            if (!Settings.canDrawOverlays(this)) {
                openApp()
                return
            }
            prefs.enabled = true
            ClockService.start(this)
        } else {
            prefs.enabled = false
            ClockService.stop(this)
        }
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        tile.state = if (Prefs(this).enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Đồng hồ nổi"
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
