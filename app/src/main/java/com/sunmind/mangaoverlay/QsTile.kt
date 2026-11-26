package com.sunmind.mangaoverlay

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** ไทล์ใน Quick Settings: แตะเพื่อเริ่ม/หยุด Manga Overlay จากแถบด้านบน */
class QsTile : TileService() {
    override fun onStartListening() { refresh() }

    override fun onClick() {
        if (OverlayService.running) {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
            return
        }
        val i = Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_START).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
    }

    private fun refresh() {
        val t = qsTile ?: return
        val on = OverlayService.running
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Manga Overlay"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (on) "กำลังทำงาน" else "แตะเพื่อเริ่ม"
        t.updateTile()
    }
}
