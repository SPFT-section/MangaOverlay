package com.sunmind.mangaoverlay

import android.app.Activity
import android.content.pm.PackageManager
import kotlinx.coroutines.runBlocking
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    private val reqCap = 1
    private val reqNotif = 2
    private lateinit var out: TextView
    private lateinit var cbMerge: CheckBox
    private lateinit var cbFit: CheckBox
    private lateinit var cbBold: CheckBox
    private lateinit var gl: EditText

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Prefs.init(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = EditText(this).apply { hint = "พิมพ์คำหรือประโยคภาษาอังกฤษ" }
        out = TextView(this).apply { textSize = 16f; setPadding(0, pad, 0, 0) }
        cbMerge = CheckBox(this).apply { text = "รวมบรรทัดในกรอบคำพูดเดียวกันก่อนแปล"; isChecked = Prefs.merge }
        cbFit = CheckBox(this).apply { text = "ขยายกล่องคำแปลให้พอดีกรอบคำพูด"; isChecked = Prefs.fit }
        cbBold = CheckBox(this).apply { text = "ตัวอักษรหนา"; isChecked = Prefs.bold }
        gl = EditText(this).apply {
            hint = "ชื่อ/ศัพท์เฉพาะ บรรทัดละคำ รูปแบบ อังกฤษ=ไทย\nเช่น\nLuffy=ลูฟี่\nStraw Hat Pirates=โจรสลัดหมวกฟาง"
            minLines = 4; gravity = Gravity.TOP; setText(Prefs.glossary)
        }
        val lookup = Button(this).apply { text = "ค้นพจนานุกรม"; setOnClickListener { lookup(box.text.toString().trim()) } }
        val start = Button(this).apply { text = "เริ่ม / Start"; setOnClickListener { start() } }
        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad)
                addView(start); addView(cbMerge); addView(cbFit); addView(cbBold); addView(gl)
                addView(box); addView(lookup); addView(out)
            })
        })
    }

    private fun save() {
        Prefs.merge = cbMerge.isChecked; Prefs.fit = cbFit.isChecked; Prefs.bold = cbBold.isChecked
        Prefs.glossary = gl.text.toString()
    }

    override fun onPause() { save(); super.onPause() }

    private fun lookup(q: String) {
        if (q.isEmpty()) return
        out.text = "กำลังค้นหา..."
        val app = applicationContext
        Thread {
            val r = try {
                Dict.load(app)
                if (!q.contains(' ')) {
                    Dict.lookup(q)?.split(';')?.filter { it.isNotBlank() }?.joinToString("\n• ", "• ") ?: "ไม่พบคำนี้"
                } else {
                    Translator.init(app)
                    // Google -> ML Kit ออฟไลน์ -> พจนานุกรม (ตามลำดับเดียวกับโอเวอร์เลย์)
                    runBlocking { Translator.translate(listOf(q)) }.firstOrNull() ?: "แปลไม่ได้"
                }
            } catch (e: Exception) { "เกิดข้อผิดพลาด: ${e.javaClass.simpleName}" }
            runOnUiThread { if (!isDestroyed) out.text = r }
        }.start()
    }

    private fun start() {
        save()
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "กรุณาอนุญาต \"แสดงทับแอปอื่น\" แล้วกดเริ่มอีกครั้ง", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return
        }
        // ขอสิทธิ์แจ้งเตือนก่อน แล้วค่อยขอจับภาพหน้าจอ (ถ้าขอพร้อมกัน dialog จะทับกัน)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), reqNotif)
        } else launchCapture()
    }

    private fun launchCapture() =
        startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), reqCap)

    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(r, p, g)
        if (r == reqNotif) launchCapture() // แม้ไม่อนุญาตแจ้งเตือน บริการก็ยังทำงานได้
    }

    @Deprecated("ok")
    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        super.onActivityResult(r, c, d)
        if (r != reqCap) return
        if (c == RESULT_OK && d != null) {
            startForegroundService(Intent(this, OverlayService::class.java).putExtra("code", c).putExtra("data", d))
            finish()
        } else Toast.makeText(this, "ยังไม่ได้อนุญาตการจับภาพหน้าจอ", Toast.LENGTH_SHORT).show()
    }
}
