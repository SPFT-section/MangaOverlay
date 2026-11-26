package com.sunmind.mangaoverlay

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*

class MainActivity : Activity() {
    private val reqCap = 1
    private lateinit var out: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = EditText(this).apply { hint = "พิมพ์คำหรือประโยคภาษาอังกฤษ" }
        out = TextView(this).apply { textSize = 16f; setPadding(0, pad, 0, 0) }
        val lookup = Button(this).apply { text = "ค้นพจนานุกรม"; setOnClickListener { lookup(box.text.toString().trim()) } }
        val start = Button(this).apply { text = "เริ่ม / Start"; setOnClickListener { start() } }
        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad)
                addView(start); addView(box); addView(lookup); addView(out)
            })
        })
    }

    private fun lookup(q: String) {
        if (q.isEmpty()) return
        out.text = "กำลังค้นหา..."
        Thread {
            Dict.load(this)
            val single = !q.contains(' ')
            val r = if (single) (Dict.lookup(q)?.split(';')?.filter { it.isNotBlank() }?.joinToString("\n• ", "• ") ?: "ไม่พบคำนี้")
                    else Dict.translate(q).ifBlank { "แปลไม่ได้" }
            runOnUiThread { out.text = r }
        }.start()
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
