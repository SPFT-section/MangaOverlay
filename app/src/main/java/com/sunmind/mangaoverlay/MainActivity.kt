package com.sunmind.mangaoverlay

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.RippleDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import kotlinx.coroutines.runBlocking

class MainActivity : Activity() {
    companion object {
        const val ACTION_START = "com.sunmind.mangaoverlay.START"
        const val ACTION_LOOKUP = "com.sunmind.mangaoverlay.LOOKUP"
        private val BG = 0xFF0F1115.toInt()
        private val CARD = 0xFF181B22.toInt()
        private val LINE = 0xFF2A2F3A.toInt()
        private val TXT = 0xFFE5E7EB.toInt()
        private val SUB = 0xFF9CA3AF.toInt()
        private val ACCENT = 0xFF22C55E.toInt()
        private val ACCENT_D = 0xFF16A34A.toInt()
        private val AMBER = 0xFFF59E0B.toInt()
        private val RED = 0xFFDC2626.toInt()
    }

    private val reqCap = 1
    private val reqNotif = 2
    private val main = Handler(Looper.getMainLooper())
    private lateinit var scroll: ScrollView
    private lateinit var dictCard: View
    private lateinit var out: TextView
    private lateinit var box: EditText
    private lateinit var gl: EditText
    private lateinit var tvPerm: TextView
    private lateinit var tvRun: TextView
    private lateinit var startBtn: TextView
    private lateinit var llmKeyBox: EditText
    private lateinit var llmModelBox: EditText
    private lateinit var llmBaseBox: EditText
    private lateinit var llmOut: TextView
    private lateinit var provChips: List<TextView>
    private val provNames = listOf("ปิด", "Gemini", "OpenAI-compat", "Claude")

    // ---------- ตัวช่วยสร้าง UI ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun shape(c: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(c); cornerRadius = dp(r).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun ripple(c: Int, r: Int) = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape(c, r), null)

    private fun tv(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    private fun card(build: LinearLayout.() -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(16))
        background = shape(CARD, 18, LINE)
        build()
    }

    private fun section(t: String) = tv(t, 13f, ACCENT, true).apply { setPadding(0, 0, 0, dp(6)) }

    private fun button(t: String, c: Int, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 16f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setPadding(dp(16), dp(14), dp(16), dp(14)); background = ripple(c, 14); isClickable = true
        setOnClickListener { onClick() }
    }

    private fun field(e: EditText) = e.apply {
        setTextColor(TXT); setHintTextColor(SUB); textSize = 15f
        background = shape(0xFF10131A.toInt(), 12, LINE); setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun line() = View(this).apply { setBackgroundColor(LINE) }

    private fun toggleRow(title: String, desc: String, on: Boolean, set: (Boolean) -> Unit): View {
        val sw = Switch(this).apply {
            isChecked = on
            val st = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(st, intArrayOf(Color.WHITE, 0xFFD1D5DB.toInt()))
            trackTintList = ColorStateList(st, intArrayOf(ACCENT, 0xFF4B5563.toInt()))
            setOnCheckedChangeListener { _, v -> set(v) }
        }
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv(title, 15f, TXT)); addView(tv(desc, 12f, SUB))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(t, LinearLayout.LayoutParams(0, -2, 1f)); addView(sw)
        }
    }

    private fun seekRow(title: String, min: Int, max: Int, cur: Int, fmt: (Int) -> String, set: (Int) -> Unit): View {
        val label = tv("$title: ${fmt(cur)}", 14f, TXT)
        val sb = SeekBar(this).apply {
            this.max = max - min; progress = cur - min
            progressTintList = ColorStateList.valueOf(ACCENT); thumbTintList = ColorStateList.valueOf(ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    label.text = "$title: ${fmt(p + min)}"
                    if (u) set(p + min)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(4))
            addView(label); addView(sb)
        }
    }

    // ---------- วงจรชีวิต ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Prefs.init(this)
        window.statusBarColor = BG; window.navigationBarColor = BG

        tvPerm = tv("", 14f, TXT); tvRun = tv("", 14f, TXT)
        startBtn = button("เริ่มแปล", ACCENT_D) { if (OverlayService.running) stopService() else start() }

        gl = field(EditText(this).apply {
            hint = "ชื่อ/ศัพท์เฉพาะ บรรทัดละคำ รูปแบบ อังกฤษ=ไทย\nเช่น\nLuffy=ลูฟี่\nStraw Hat Pirates=โจรสลัดหมวกฟาง"
            minLines = 4; gravity = Gravity.TOP; setText(Prefs.glossary)
        })
        box = field(EditText(this).apply {
            hint = "พิมพ์คำหรือประโยคภาษาอังกฤษ"
            inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_SEARCH; setSingleLine()
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEARCH) { lookup(text.toString().trim()); true } else false
            }
        })
        out = tv("", 16f, TXT).apply { setPadding(0, dp(12), 0, 0); setTextIsSelectable(true) }
        llmKeyBox = field(EditText(this).apply {
            hint = "API key"; setSingleLine(); setText(Prefs.llmKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        })
        llmModelBox = field(EditText(this).apply { setSingleLine(); setText(Prefs.llmModel) })
        llmBaseBox = field(EditText(this).apply {
            hint = "Base URL เช่น https://api.groq.com/openai/v1"; setSingleLine(); setText(Prefs.llmBase)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        })
        llmOut = tv("", 13f, TXT).apply { setPadding(0, dp(8), 0, 0); setTextIsSelectable(true) }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv("Manga Overlay", 28f, TXT, true))
            addView(tv("แปลมังงะ อังกฤษ → ไทย แบบลอยทับหน้าจอ", 13f, SUB))
        }

        val status = card {
            addView(section("สถานะ"))
            addView(tvPerm); addView(tvRun, lp(4))
            addView(startBtn, lp(14))
        }

        val display = card {
            addView(section("การแสดงผล"))
            addView(toggleRow("รวมบรรทัดในกรอบคำพูด", "รวมข้อความในกรอบเดียวกันก่อนแปล ได้ประโยคที่สมบูรณ์ขึ้น", Prefs.merge) { Prefs.merge = it })
            addView(line(), LinearLayout.LayoutParams(-1, 1))
            addView(toggleRow("ขยายกล่องให้พอดีกรอบ", "กล่องคำแปลคลุมกรอบคำพูดทั้งกรอบ", Prefs.fit) { Prefs.fit = it })
            addView(line(), LinearLayout.LayoutParams(-1, 1))
            addView(toggleRow("สำนวนมังงะ", "วลีสั้น/คำอุทาน/เสียงประกอบแปลแบบมังงะ และตัดคำสุภาพที่เครื่องแปลใส่เกิน", Prefs.mangaStyle) { Prefs.mangaStyle = it })
            addView(line(), LinearLayout.LayoutParams(-1, 1))
            addView(toggleRow("ตัวอักษรหนา", "อ่านง่ายขึ้นบนกรอบสีอ่อน", Prefs.bold) { Prefs.bold = it })
            addView(line(), LinearLayout.LayoutParams(-1, 1))
            addView(toggleRow("สั่นเมื่อแตะปุ่มลอย", "ให้ฟีดแบ็กตอนแตะ แตะสองครั้ง และกดค้าง", Prefs.haptic) { Prefs.haptic = it })
            addView(seekRow("ความทึบของกล่องคำแปล", 40, 100, Prefs.opacity, { "$it%" }) { Prefs.opacity = it })
            addView(seekRow("ขนาดตัวอักษรสูงสุด", 16, 40, Prefs.maxSp, { "$it sp" }) { Prefs.maxSp = it })
        }

        val glossary = card {
            addView(section("ศัพท์เฉพาะ / ชื่อตัวละคร"))
            addView(gl)
        }

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        provChips = provNames.mapIndexed { i, n ->
            TextView(this).apply {
                text = n; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; isClickable = true
                setPadding(dp(6), dp(10), dp(6), dp(10))
                setOnClickListener { Prefs.llmProvider = i; refreshProv() }
            }.also { chips.addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(6) }) }
        }
        val ai = card {
            addView(section("แปลด้วย AI (ไม่บังคับ — แม่นและได้สำนวนมังงะที่สุด)"))
            addView(tv("ใส่ API key ของคุณเอง เช่น Gemini ฟรีจาก aistudio.google.com/apikey ถ้าไม่ใส่ แอปใช้ Google / Bing / ออฟไลน์ตามปกติ AI จะแปลทั้งหน้าพร้อมบริบท แล้วแทนที่คำแปลเร็วเมื่อเสร็จ", 12f, SUB).apply { setLineSpacing(0f, 1.15f) })
            addView(chips, lp(10))
            addView(llmKeyBox, lp(10))
            addView(llmModelBox, lp(8))
            addView(llmBaseBox, lp(8))
            addView(button("ทดสอบการเชื่อมต่อ", 0xFF374151.toInt()) { testLlm() }, lp(10))
            addView(llmOut)
        }

        dictCard = card {
            addView(section("พจนานุกรม / แปลด่วน"))
            addView(box)
            addView(button("แปล", ACCENT_D) { lookup(box.text.toString().trim()) }, lp(10))
            addView(out)
        }

        val tips = card {
            addView(section("ทางลัด"))
            addView(tv(
                "• แตะปุ่มลอย = เปิด/ปิดการแปล\n" +
                "• แตะสองครั้ง = สแกนหน้านี้ใหม่ทันที\n" +
                "• กดค้าง = เมนูด่วน (สแกนใหม่ / ดูต้นฉบับ / ตั้งค่า / ปิด)\n" +
                "• ลากปุ่ม = ย้ายตำแหน่ง (ดูดติดขอบจออัตโนมัติ)\n" +
                "• แถบแจ้งเตือน = ปุ่มเปิด-ปิดแปล / สแกนใหม่ / หยุด\n" +
                "• กดไอคอนแอปค้าง = ทางลัด \"เริ่มแปล\" และ \"พจนานุกรม\"\n" +
                "• เพิ่มไทล์ \"Manga Overlay\" ใน Quick Settings เพื่อเริ่ม/หยุดจากแถบด้านบน",
                13f, SUB).apply { setLineSpacing(0f, 1.2f) })
            addView(button("ปักทางลัด \"เริ่มแปล\" บนหน้าจอหลัก", 0xFF374151.toInt()) { pin() }, lp(12))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(28))
            addView(header)
            addView(status, lp(20)); addView(display, lp(12)); addView(ai, lp(12)); addView(glossary, lp(12))
            addView(dictCard, lp(12)); addView(tips, lp(12))
        }
        scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true; addView(root) }
        setContentView(scroll)
        refreshProv()
        if (b == null) handle(intent)
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); handle(i) }

    /** ทางลัดจากไอคอนแอป / ไทล์ / ปักหน้าจอหลัก */
    private fun handle(i: Intent?) {
        when (i?.action) {
            ACTION_START -> { setIntent(Intent(this, MainActivity::class.java)); main.post { start() } }
            ACTION_LOOKUP -> {
                setIntent(Intent(this, MainActivity::class.java))
                scroll.post {
                    scroll.scrollTo(0, dictCard.top); box.requestFocus()
                    getSystemService(InputMethodManager::class.java)?.showSoftInput(box, 0)
                }
            }
        }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val ok = Settings.canDrawOverlays(this)
        tvPerm.text = if (ok) "● สิทธิ์แสดงทับแอปอื่น: พร้อม" else "● สิทธิ์แสดงทับแอปอื่น: ยังไม่อนุญาต (กดเริ่มเพื่อตั้งค่า)"
        tvPerm.setTextColor(if (ok) ACCENT else AMBER)
        val run = OverlayService.running
        tvRun.text = if (run) "● กำลังทำงาน" else "● หยุดอยู่"
        tvRun.setTextColor(if (run) ACCENT else SUB)
        startBtn.text = if (run) "หยุดการทำงาน" else "เริ่มแปล"
        startBtn.background = ripple(if (run) RED else ACCENT_D, 14)
    }

    private fun save() {
        Prefs.glossary = gl.text.toString()
        Prefs.llmKey = llmKeyBox.text.toString(); Prefs.llmModel = llmModelBox.text.toString(); Prefs.llmBase = llmBaseBox.text.toString()
    }

    private fun refreshProv() {
        val p = Prefs.llmProvider
        provChips.forEachIndexed { i, t -> t.background = shape(if (i == p) ACCENT_D else 0xFF10131A.toInt(), 10, LINE) }
        llmKeyBox.visibility = if (p == 0) View.GONE else View.VISIBLE
        llmModelBox.visibility = if (p == 0) View.GONE else View.VISIBLE
        llmBaseBox.visibility = if (p == 2) View.VISIBLE else View.GONE
        llmModelBox.hint = "ชื่อโมเดล (เว้นว่าง = ${Llm.defaultModel(p)})"
    }

    private fun testLlm() {
        save(); llmOut.text = "กำลังทดสอบ..."
        Thread {
            val r = try { Llm.test() } catch (e: Exception) { "ไม่สำเร็จ: ${e.javaClass.simpleName}" }
            runOnUiThread { if (!isDestroyed) llmOut.text = r }
        }.start()
    }

    override fun onPause() { save(); super.onPause() }

    // ---------- พจนานุกรม ----------
    private fun lookup(q: String) {
        if (q.isEmpty()) return
        out.text = "กำลังค้นหา..."
        val app = applicationContext
        save()
        Thread {
            val r = try {
                Dict.load(app)
                if (!q.contains(' ')) {
                    Dict.lookup(q)?.split(';')?.filter { it.isNotBlank() }?.joinToString("\n• ", "• ") ?: "ไม่พบคำนี้"
                } else {
                    Translator.init(app)
                    // Google -> ML Kit ออฟไลน์ -> พจนานุกรม (ตามลำดับเดียวกับโอเวอร์เลย์)
                    runBlocking { Translator.translate(listOf(q), strict = false) }.firstOrNull() ?: "แปลไม่ได้"
                }
            } catch (e: Exception) { "เกิดข้อผิดพลาด: ${e.javaClass.simpleName}" }
            runOnUiThread { if (!isDestroyed) out.text = r }
        }.start()
    }

    // ---------- เริ่ม/หยุด ----------
    private fun pin() {
        val sm = getSystemService(ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) {
            Toast.makeText(this, "ตัวเปิดแอปนี้ไม่รองรับการปักทางลัด ลองกดไอคอนแอปค้างแทน", Toast.LENGTH_LONG).show(); return
        }
        val si = ShortcutInfo.Builder(this, "start_pin")
            .setShortLabel("เริ่มแปล").setLongLabel("เริ่ม Manga Overlay")
            .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(Intent(this, MainActivity::class.java).setAction(ACTION_START)).build()
        sm.requestPinShortcut(si, null)
    }

    private fun stopService() {
        startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
        main.postDelayed({ if (!isDestroyed) refresh() }, 400)
    }

    private fun start() {
        save()
        if (OverlayService.running) { Toast.makeText(this, "Manga Overlay ทำงานอยู่แล้ว", Toast.LENGTH_SHORT).show(); refresh(); return }
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
