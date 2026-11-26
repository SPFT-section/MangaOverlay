package com.sunmind.mangaoverlay

import android.app.*
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.*
import android.view.WindowManager.LayoutParams as WLP
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.TextViewCompat
import kotlinx.coroutines.*
import org.opencv.android.OpenCVLoader
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var layer: FrameLayout
    private lateinit var container: FrameLayout
    private lateinit var btn: TextView
    private var btnLp: WLP? = null
    private var proj: MediaProjection? = null
    private var projCb: MediaProjection.Callback? = null
    private var stopping = false
    private var capture: Capture? = null
    private lateinit var tracker: Tracker
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, _ -> busy = false })
    private var W = 0; private var H = 0
    // transform ของ container (screen = sc*local + t)
    private var sc = 1f; private var tx = 0f; private var ty = 0f
    private var scOcr = 1f; private var accDy = 0f
    private var active = false; private var busy = false; private var lost = false
    private var settleJob: Job? = null
    @Volatile private var mask: List<Rect> = emptyList()

    override fun onBind(i: Intent?): IBinder? = null

    companion object { const val ACTION_STOP = "com.sunmind.mangaoverlay.STOP" }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        // startForegroundService() บังคับให้เรียก startForeground() ภายใน 5 วิ ทุกครั้ง ไม่งั้นแอปล้ม (รวมกรณีกด Start ซ้ำ)
        startFg()
        if (i == null || i.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (capture != null) return START_NOT_STICKY
        try {
            if (!OpenCVLoader.initLocal()) toast("โหลด OpenCV ไม่สำเร็จ การติดตามการเลื่อนหน้าอาจไม่ทำงาน")
            Prefs.init(applicationContext)
            Translator.init(applicationContext)
            Translator.onStatus = { m -> main.post { toast(m) } }
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics(); @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            W = dm.widthPixels; H = dm.heightPixels
            tracker = Tracker(W / Q, H / Q)
            val data = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra("data", Intent::class.java)
                       else @Suppress("DEPRECATION") i.getParcelableExtra("data")
            if (data == null) { toast("ไม่ได้รับสิทธิ์จับภาพหน้าจอ"); stopSelf(); return START_NOT_STICKY }
            val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(i.getIntExtra("code", 0), data)
            proj = mp
            val cb = object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }
            projCb = cb
            mp.registerCallback(cb, main)
            capture = Capture(mp, W, H, dm.densityDpi).also { c ->
                c.onGray = { g -> tracker.step(g, mask)?.let { s -> main.post { applyStep(s) } } }
            }
            buildLayer(); buildButton()
            scope.launch { Translator.warmUp() }
        } catch (e: Exception) {
            toast("เริ่มไม่ได้: ${e.javaClass.simpleName}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** หมุนจอ = ขนาดเฟรมไม่ตรงกับที่ตั้งไว้ ตำแหน่งคำแปลจะเพี้ยน จึงหยุดอย่างชัดเจนให้เริ่มใหม่ */
    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        if (capture == null || stopping) return
        val dm = DisplayMetrics(); @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
        if (dm.widthPixels != W || dm.heightPixels != H) {
            toast("หมุนจอแล้ว กรุณากดเริ่มใหม่อีกครั้ง"); stopSelf()
        }
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("m", "Manga Overlay", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopAct = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "หยุด", stopPi).build()
        val n = Notification.Builder(this, "m").setContentTitle("Manga Overlay").setContentText("กำลังทำงาน")
            .setSmallIcon(android.R.drawable.ic_menu_edit).addAction(stopAct).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    // ---------- windows ----------
    private fun buildLayer() {
        container = FrameLayout(this).apply { pivotX = 0f; pivotY = 0f; clipChildren = false; clipToPadding = false }
        layer = FrameLayout(this).apply { clipChildren = false }
        layer.addView(container, FrameLayout.LayoutParams(-1, -1))
        val lp = WLP(-1, -1, WLP.TYPE_APPLICATION_OVERLAY,
            WLP.FLAG_NOT_TOUCHABLE or WLP.FLAG_NOT_FOCUSABLE or WLP.FLAG_LAYOUT_IN_SCREEN or WLP.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WLP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(layer, lp)
    }

    private fun buildButton() {
        val size = (52 * resources.displayMetrics.density).toInt()
        btn = TextView(this).apply {
            text = "文"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC555555.toInt()) }
        }
        val lp = WLP(size, size, WLP.TYPE_APPLICATION_OVERLAY, WLP.FLAG_NOT_FOCUSABLE or WLP.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = W - size - 16; y = H / 3 }
        btnLp = lp
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0; var down = 0L; var moved = false
        btn.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = lp.x; sy = lp.y; down = e.eventTime; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - dx) > 12 || abs(e.rawY - dy) > 12) moved = true
                    if (moved) { lp.x = sx + (e.rawX - dx).toInt(); lp.y = sy + (e.rawY - dy).toInt(); wm.updateViewLayout(btn, lp); updateMask() }
                }
                MotionEvent.ACTION_UP -> if (!moved) { if (e.eventTime - down > 700) { toast("ปิด Manga Overlay"); stopSelf() } else toggle() }
            }
            true
        }
        wm.addView(btn, lp)
        updateMask()
    }

    private fun toggle() {
        active = !active
        (btn.background as GradientDrawable).setColor(if (active) 0xCC1B9E4B.toInt() else 0xCC555555.toInt())
        capture?.tracking = active
        if (active) scope.launch { while (busy) delay(50); full() } else { container.removeAllViews(); updateMask() }
    }

    // ---------- tracking ----------
    private fun applyTransform() {
        container.scaleX = sc; container.scaleY = sc; container.translationX = tx; container.translationY = ty
        updateMask()
    }

    private fun applyStep(s: Step) {
        if (!active) return
        if (container.childCount == 0) {
            // หน้าแรกไม่พบข้อความ: พอเลื่อนไปหน้าที่มีข้อความแล้วหยุดนิ่ง ต้องสแกนใหม่เอง (เดิมค้างไม่ทำอะไรจนกว่าจะกดปุ่มใหม่)
            if (!s.ok || abs(s.tx) > 2f || abs(s.ty) > 2f) {
                settleJob?.cancel()
                settleJob = scope.launch { delay(350); if (active && !busy && container.childCount == 0) full() }
            }
            return
        }
        if (!s.ok) { lost = true; container.visibility = View.INVISIBLE }
        else if (!lost) {
            tx = s.s * tx + s.tx; ty = s.s * ty + s.ty; sc *= s.s; accDy += s.ty
            applyTransform()
            if (!busy && abs(accDy) > H * 0.25f && abs(sc / scOcr - 1f) <= 0.03f) scope.launch { strip() }
        }
        settleJob?.cancel()
        settleJob = scope.launch { delay(150); scope.launch { onSettle() } }
    }

    private fun updateMask() {
        val l = ArrayList<Rect>()
        for (i in 0 until container.childCount) {
            val p = container.getChildAt(i).layoutParams as FrameLayout.LayoutParams
            l.add(Rect((p.leftMargin * sc + tx).toInt() - 8, (p.topMargin * sc + ty).toInt() - 8,
                ((p.leftMargin + p.width) * sc + tx).toInt() + 8, ((p.topMargin + p.height) * sc + ty).toInt() + 8))
        }
        btnLp?.let { l.add(Rect(it.x - 8, it.y - 8, it.x + it.width + 8, it.y + it.height + 8)) }
        mask = l
    }

    // ---------- OCR + translate ----------
    private suspend fun full() {
        if (busy) return
        busy = true; ui(true)
        try {
            val hide = container.visibility == View.VISIBLE && container.childCount > 0
            container.removeAllViews()
            sc = 1f; tx = 0f; ty = 0f; scOcr = 1f; accDy = 0f
            applyTransform(); tracker.reset()
            val bmp = capture!!.snapshot(if (hide) listOf(container) else emptyList(), hide)
            lost = false; container.visibility = View.VISIBLE
            val found = Ocr.run(bmp, scope, (H * 0.04f).toInt())
            bmp.recycle()
            if (found.items.isEmpty()) toast("ไม่พบข้อความในหน้านี้")
            addItems(found.items, found.tr, 1f, 0f, 0f)
        } finally { busy = false; ui(false) }
    }

    private suspend fun onSettle() {
        if (!active) return
        if (busy) { settleJob = scope.launch { delay(250); onSettle() }; return }
        if (lost || abs(sc / scOcr - 1f) > 0.03f) { full(); return }
        if (abs(accDy) >= H * 0.06f) strip() else prune()
    }

    /** OCR เฉพาะแถบใหม่ที่เลื่อนเข้ามา (ไม่ซ่อนโอเวอร์เลย์ เพราะแถบใหม่ยังไม่มีข้อความทับ) */
    private suspend fun strip() {
        if (busy || !active) return
        busy = true; ui(true)
        try {
            val moved = accDy
            val margin = (H * 0.1f).toInt()
            val y0: Int; val y1: Int
            if (moved < 0) { y0 = max(0, H + moved.toInt() - margin); y1 = H } else { y0 = 0; y1 = min(H, moved.toInt() + margin) }
            val bmp = capture!!.snapshot(emptyList(), false)
            val s0 = sc; val x0 = tx; val yy0 = ty
            val crop = Bitmap.createBitmap(bmp, 0, y0, W, y1 - y0)
            val found = Ocr.run(crop, scope)
            crop.recycle(); bmp.recycle()
            found.items.forEach { it.r.offset(0, y0) }
            accDy -= moved
            addItems(found.items.filter { it.r.top >= H * 0.04f }, found.tr, s0, x0, yy0)
            prune()
        } finally { busy = false; ui(false) }
    }

    /** items อยู่ในพิกัดหน้าจอ ณ เวลา snapshot (s0,x0,y0) -> แปลงเป็น local; วางกรอบทันที แล้วเติมข้อความไทยเมื่อแปลเสร็จ */
    private suspend fun addItems(items: List<Item>, tr: Deferred<Map<String, String?>>, s0: Float, x0: Float, y0: Float) {
        if (!active || items.isEmpty()) return
        val views = ArrayList<Pair<TextView, Item>>()
        for (item in items) {
            val r = item.r
            val pd = if (item.bubble) 0 else 6
            val l = (r.left - pd - x0) / s0; val t = (r.top - pd - y0) / s0
            val w = (r.width() + 2 * pd) / s0; val h = (r.height() + 2 * pd) / s0
            val nr = RectF(l, t, l + w, t + h)
            var skip = false
            for (j in container.childCount - 1 downTo 0) {
                val p = container.getChildAt(j).layoutParams as FrameLayout.LayoutParams
                val er = RectF(p.leftMargin.toFloat(), p.topMargin.toFloat(), (p.leftMargin + p.width).toFloat(), (p.topMargin + p.height).toFloat())
                val ix = RectF(er)
                if (ix.intersect(nr)) {
                    val small = minOf(er.width() * er.height(), nr.width() * nr.height())
                    if (ix.width() * ix.height() > 0.3f * small) {
                        if (nr.width() * nr.height() > 1.3f * er.width() * er.height()) container.removeViewAt(j) else skip = true
                    }
                }
            }
            if (skip) continue
            val tv = makeTv(item.bg)
            container.addView(tv, FrameLayout.LayoutParams(w.toInt(), h.toInt()).apply { leftMargin = l.toInt(); topMargin = t.toInt() })
            views.add(Pair(tv, item))
        }
        updateMask()
        // คำแปลเริ่มทำงานตั้งแต่ตอนหากรอบ ส่วนใหญ่จึงเสร็จแล้ว/ใกล้เสร็จตอนมาถึงตรงนี้
        val th = try { tr.await() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyMap<String, String?>() }
        for ((tv, item) in views) {
            val t = th[item.text]
            if (t.isNullOrBlank()) container.removeView(tv) else tv.text = t // แปลไม่ได้ -> เอากรอบออก ให้เห็นต้นฉบับ
        }
        updateMask()
    }

    private fun makeTv(bg: Int) = TextView(this).apply {
        gravity = Gravity.CENTER; setPadding(4, 2, 4, 2)
        val lum = 0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)
        setTextColor(if (lum > 140) Color.BLACK else Color.WHITE)
        background = GradientDrawable().apply { setColor(Color.rgb(Color.red(bg), Color.green(bg), Color.blue(bg))); cornerRadius = 10f }
        breakStrategy = Layout.BREAK_STRATEGY_BALANCED       // ตัดบรรทัดให้สมดุลเหมือนในกรอบคำพูด
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        if (Prefs.bold) typeface = Typeface.DEFAULT_BOLD
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 7, 28, 1, TypedValue.COMPLEX_UNIT_SP)
    }

    private fun prune() {
        for (i in container.childCount - 1 downTo 0) {
            val p = container.getChildAt(i).layoutParams as FrameLayout.LayoutParams
            val top = p.topMargin * sc + ty; val bot = top + p.height * sc
            if (bot < -H || top > 2 * H) container.removeViewAt(i)
        }
        updateMask()
    }

    private fun ui(b: Boolean) { if (::btn.isInitialized) btn.text = if (b) "…" else "文" }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        stopping = true
        scope.cancel()
        Translator.close()
        capture?.release(); capture = null
        projCb?.let { cb -> runCatching { proj?.unregisterCallback(cb) } }
        runCatching { proj?.stop() }; proj = null
        runCatching { wm.removeView(layer) }; runCatching { wm.removeView(btn) }
        super.onDestroy()
    }
}
