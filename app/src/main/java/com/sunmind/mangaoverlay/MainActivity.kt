package com.sunmind.mangaoverlay

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button

class MainActivity : Activity() {
    private val reqCap = 1
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(Button(this).apply { text = "เริ่ม / Start"; setOnClickListener { start() } })
    }
    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return
        }
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
        startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), reqCap)
    }
    @Deprecated("ok")
    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        if (r == reqCap && c == RESULT_OK && d != null) {
            startForegroundService(Intent(this, OverlayService::class.java).putExtra("code", c).putExtra("data", d))
            finish()
        }
    }
}
