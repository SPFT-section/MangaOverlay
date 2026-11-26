package com.sunmind.mangaoverlay

import android.animation.ValueAnimator
import android.app.*
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.service.quicksettings.TileService
import android.text.Layout
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.*
import android.view.WindowManager.LayoutParams as WLP
import android.widget.FrameLayout
import android.widget.LinearLayout
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
    private var px = 0f; private var py = 0f          // ชดเชยความหน่วงของการจับภาพ (คาดการณ์ตามความเร็ว) ใช้เฉพาะตอนแสดงผล
    private var epoch = 0
    private val keeper = PoseKeeper()
    private var statTotal = 0; private var statDone = 0; private var statAi = false
    private var active = false; private var busy = false; private var lost = false
    private val restRun = Runnable { px = 0f; py = 0f; if (::container.isInitialized) applyTransform() }
    private var settleJob: Job? = null
    private var busyUi = false; private var peeking = false; private var rescanQ = false
    private var menu: LinearLayout? = null; private var menuLp: WLP? = null
    private var lastTap = 0L; private var tapRun: Runnable? = null; private var longFired = false
    private val dimRun = Runnable { if (::btn.isInitialized) btn.animate().alpha(0.55f).setDuration(300).start() }
    private val longRun = Runnable { longFired = true; haptic(HapticFeedbackConstants.LONG_PRESS); showMenu() }
    @Volatile private var mask: List<Rect> = emptyList()

    override fun onBind(i: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.sunmind.mangaoverlay.STOP"
        const val ACTION_TOGGLE = "com.sunmind.mangaoverlay.TOGGLE"
        const val ACTION_RESCAN = "com.sunmind.mangaoverlay.RESCAN"
        @Volatile var running = false
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val fromUi = i?.hasExtra("data") == true
        // คำสั่งจากแจ้งเตือน/ไทล์ตอนไม่มีเซสชัน: ไม่มีอะไรให้ทำ และห้าม startForeground (ไม่มีสิทธิ์จับภาพ)
        if (!fromUi && capture == null) { stopSelf(); return START_NOT_STICKY }
        // startForegroundService() บังคับให้เรียก startForeground() ภายใน 5 วิ ทุกครั้ง ไม่งั้นแอปล้ม (รวมกรณีกด Start ซ้ำ)
        startFg()
        when (i?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_TOGGLE -> { toggle(); return START_NOT_STICKY }
            ACTION_RESCAN -> { rescan(); return START_NOT_STICKY }
        }
        if (i == null) { stopSelf(); return START_NOT_STICKY }
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
            capture = Capture(mp, W, H, dm.densityDpi, keeper).also { c ->
                // รันบน thread ของ Capture: ตามการเลื่อน -> สะสมท่า (ครบทุกเฟรม) -> ส่งให้ UI วาด
                c.onGray = { g, ts -> tracker.step(g, mask)?.let { s -> val d = keeper.update(s, ts); main.post { applyStep(d) } } }
            }
            buildLayer(); buildButton()
            running = true; notifyTile()
            toast("แตะ=เปิด/ปิดแปล • แตะ 2 ครั้ง=สแกนใหม่ • กดค้าง=เมนู")
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
        val n = buildNotif()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    private fun buildNotif(): Notification {
        val fl = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        fun svc(code: Int, action: String) =
            PendingIntent.getService(this, code, Intent(this, OverlayService::class.java).setAction(action), fl)
        fun act(label: String, pi: PendingIntent) =
            Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_tile), label, pi).build()
        val open = PendingIntent.getActivity(this, 9,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), fl)
        return Notification.Builder(this, "m").setContentTitle("Manga Overlay")
            .setContentText(if (active) "กำลังแปลหน้านี้" else "พร้อมใช้งาน — แตะปุ่มลอยเพื่อเริ่มแปล")
            .setSmallIcon(R.drawable.ic_tile).setContentIntent(open)
            .addAction(act(if (active) "ปิดแปล" else "เปิดแปล", svc(1, ACTION_TOGGLE)))
            .addAction(act("สแกนใหม่", svc(2, ACTION_RESCAN)))
            .addAction(act("หยุด", svc(0, ACTION_STOP)))
            .setOngoing(true).build()
    }

    private fun updateNotif() {
        runCatching { getSystemService(NotificationManager::class.java).notify(1, buildNotif()) }
    }

    private fun notifyTile() {
        runCatching { TileService.requestListeningState(applicationContext, ComponentName(applicationContext, QsTile::class.java)) }
    }

    private fun haptic(t: Int = HapticFeedbackConstants.CONTEXT_CLICK) {
        if (Prefs.haptic && ::btn.isInitialized) btn.performHapticFeedback(t)
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
        applyAlpha()
    }

    private fun buildButton() {
        val d = resources.displayMetrics.density
        val size = (52 * d).toInt(); val margin = (8 * d).toInt()
        btn = TextView(this).apply {
            text = "文"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xEE374151.toInt()); setStroke((2 * d).toInt(), 0x66FFFFFF) }
        }
        val lp = WLP(size, size, WLP.TYPE_APPLICATION_OVERLAY, WLP.FLAG_NOT_FOCUSABLE or WLP.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = W - size - margin; y = H / 3 }
        btnLp = lp
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0; var moved = false; var ignoreUp = false
        btn.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    dx = e.rawX; dy = e.rawY; sx = lp.x; sy = lp.y; moved = false; longFired = false
                    main.removeCallbacks(dimRun); btn.animate().cancel(); btn.alpha = 1f
                    ignoreUp = menu != null; if (ignoreUp) dismissMenu()
                    main.postDelayed(longRun, 450)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && (abs(e.rawX - dx) > 12 || abs(e.rawY - dy) > 12)) { moved = true; main.removeCallbacks(longRun) }
                    if (moved) {
                        lp.x = (sx + (e.rawX - dx)).toInt().coerceIn(0, max(0, W - size))
                        lp.y = (sy + (e.rawY - dy)).toInt().coerceIn(0, max(0, H - size))
                        wm.updateViewLayout(btn, lp); updateMask()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longRun)
                    if (moved) snapToEdge() else if (!longFired && !ignoreUp) onTap(e.eventTime)
                    main.postDelayed(dimRun, 2500)
                }
                MotionEvent.ACTION_CANCEL -> { main.removeCallbacks(longRun); main.postDelayed(dimRun, 2500) }
            }
            true
        }
        wm.addView(btn, lp)
        updateMask()
        main.postDelayed(dimRun, 4000)
    }

    /** ดูดปุ่มติดขอบจอซ้าย/ขวา */
    private fun snapToEdge() {
        val lp = btnLp ?: return
        val m = (8 * resources.displayMetrics.density).toInt()
        val target = if (lp.x + lp.width / 2 < W / 2) m else W - lp.width - m
        ValueAnimator.ofInt(lp.x, target).apply {
            duration = 180
            addUpdateListener { a ->
                lp.x = a.animatedValue as Int
                runCatching { wm.updateViewLayout(btn, lp) }
                updateMask()
            }
            start()
        }
    }

    /** แตะครั้งเดียว = เปิด/ปิด (รอ 300ms ดูว่าเป็นแตะสองครั้งหรือไม่) แตะสองครั้ง = สแกนใหม่ */
    private fun onTap(t: Long) {
        val pending = tapRun
        if (pending != null && t - lastTap < 300) {
            main.removeCallbacks(pending); tapRun = null; rescan(); return
        }
        lastTap = t
        val r = Runnable { tapRun = null; toggle() }
        tapRun = r; main.postDelayed(r, 300)
    }

    private fun refreshBtn() {
        if (!::btn.isInitialized) return
        val c = when { busyUi -> 0xEEF59E0B.toInt(); active -> 0xEE16A34A.toInt(); else -> 0xEE374151.toInt() }
        (btn.background as GradientDrawable).setColor(c)
        btn.text = if (busyUi) "…" else "文"
    }

    private fun applyAlpha() {
        if (::container.isInitialized) container.alpha = if (peeking) 0f else Prefs.opacity / 100f
    }

    private fun toggle() {
        if (!::btn.isInitialized) return
        active = !active
        if (!active) peeking = false
        applyAlpha(); refreshBtn(); haptic(); updateNotif()
        capture?.tracking = active
        if (active) scope.launch { while (busy) delay(50); full() } else { container.removeAllViews(); px = 0f; py = 0f; updateMask() }
    }

    /** สแกนหน้าปัจจุบันใหม่ทันที (ถ้ายังไม่เปิดแปล จะเปิดให้) */
    private fun rescan() {
        if (!::btn.isInitialized) return
        if (!active) { toggle(); return }
        if (rescanQ) return
        rescanQ = true; haptic()
        scope.launch { try { while (busy) delay(50); full() } finally { rescanQ = false } }
    }

    private fun togglePeek() {
        if (!active) { toast("เปิดการแปลก่อน"); return }
        peeking = !peeking; applyAlpha()
    }

    private fun openApp() {
        runCatching {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    // ---------- เมนูด่วน (กดค้างปุ่มลอย) ----------
    private fun showMenu() {
        if (menu != null || !::btn.isInitialized) return
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun item(label: String, color: Int, onClick: () -> Unit) = TextView(this@OverlayService).apply {
            text = label; textSize = 15f; setTextColor(color); gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, ColorDrawable(Color.WHITE))
            setOnClickListener { dismissMenu(); onClick() }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xF2171A21.toInt()); cornerRadius = dp(16).toFloat(); setStroke(dp(1), 0x33FFFFFF) }
            setPadding(dp(6), dp(6), dp(6), dp(6))
            addView(item("↻  สแกนใหม่", Color.WHITE) { rescan() })
            addView(item(if (peeking) "◐  แสดงคำแปล" else "◐  ดูต้นฉบับ", Color.WHITE) { togglePeek() })
            addView(item("✓  สถานะการแปล", Color.WHITE) { report() })
            addView(item("⚙  ตั้งค่า", Color.WHITE) { openApp() })
            addView(item("✕  ปิด Manga Overlay", 0xFFFCA5A5.toInt()) { toast("ปิด Manga Overlay"); stopSelf() })
            setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) dismissMenu(); false }
        }
        val w = dp(210)
        val bl = btnLp ?: return
        val lp = WLP(w, WLP.WRAP_CONTENT, WLP.TYPE_APPLICATION_OVERLAY,
            WLP.FLAG_NOT_FOCUSABLE or WLP.FLAG_NOT_TOUCH_MODAL or WLP.FLAG_WATCH_OUTSIDE_TOUCH or WLP.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        val onRight = bl.x + bl.width / 2 > W / 2
        lp.x = if (onRight) bl.x - w - dp(8) else bl.x + bl.width + dp(8)
        val estH = dp(5 * 42 + 12)
        lp.y = bl.y.coerceIn(dp(24), max(dp(24), H - estH - dp(24)))
        try { wm.addView(box, lp) } catch (e: Exception) { return }
        menu = box; menuLp = lp
        box.post { updateMask() }
    }

    private fun dismissMenu() {
        val m = menu ?: return
        menu = null; menuLp = null
        runCatching { wm.removeView(m) }
        updateMask()
    }

    // ---------- tracking ----------
    private fun applyTransform() {
        container.scaleX = sc; container.scaleY = sc; container.translationX = tx + px; container.translationY = ty + py
        updateMask()
    }

    private fun applyStep(d: Disp) {
        if (!active || d.pose.epoch != epoch) return   // ผลที่ตกค้างจากก่อนสแกนใหม่ ทิ้งไป
        val p = d.pose
        sc = p.s; tx = p.tx; ty = p.ty
        // ชดเชยความหน่วง ~2-3 เฟรมระหว่างจับภาพ->คำนวณ->วาด ด้วยการคาดการณ์ตามความเร็วล่าสุด (จำกัดไม่ให้เกินจริง)
        val lead = 30f
        px = (d.vx * lead).coerceIn(-45f, 45f); py = (d.vy * lead).coerceIn(-45f, 45f)
        if (!d.ok || d.still || abs(px) < 0.5f && abs(py) < 0.5f) { px = 0f; py = 0f }
        main.removeCallbacks(restRun); main.postDelayed(restRun, 90)   // หยุดเลื่อน = คืนตำแหน่งจริง ไม่ค้างค่าคาดการณ์
        if (container.childCount == 0) {
            // หน้าแรกไม่พบข้อความ: พอเลื่อนไปหน้าที่มีข้อความแล้วหยุดนิ่ง ต้องสแกนใหม่เอง
            if (d.moved) {
                settleJob?.cancel()
                settleJob = scope.launch { delay(350); if (active && !busy && container.childCount == 0) full() }
            }
            return
        }
        // ตามไม่ได้ชั่วคราวไม่ซ่อนกรอบ (เดิมซ่อนทันทีแล้วสแกนใหม่ทั้งหน้า = กระพริบ) จะซ่อนก็ต่อเมื่อหลงตำแหน่งจริง
        if (d.lost) { lost = true; container.visibility = View.INVISIBLE }
        else if (container.visibility != View.VISIBLE && !lost) container.visibility = View.VISIBLE
        applyTransform()
        if (!lost && !busy && abs(p.acc) > H * 0.25f && abs(sc - 1f) <= 0.03f) scope.launch { strip() }
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
        val m = menu; val ml = menuLp
        if (m != null && ml != null) l.add(Rect(ml.x - 8, ml.y - 8, ml.x + max(m.width, ml.width) + 8, ml.y + max(m.height, 1) + 8))
        mask = l
    }

    // ---------- OCR + translate ----------
    private suspend fun full() {
        if (busy) return
        busy = true; ui(true)
        try {
            val hide = container.visibility == View.VISIBLE && container.childCount > 0
            container.removeAllViews()
            tracker.reset()
            // ตั้งต้นท่าที่เฟรมของภาพที่จะ OCR พอดี: ถ้าผู้ใช้เลื่อนหน้าระหว่างรอ OCR/แปล กรอบจะเลื่อนตามไปถูกที่ ไม่เพี้ยน
            val snap = capture!!.snapshot(if (hide) listOf(container) else emptyList(), hide, resetPose = true)
            epoch = snap.pose.epoch; sc = 1f; tx = 0f; ty = 0f; px = 0f; py = 0f
            lost = false; container.visibility = View.VISIBLE
            applyTransform()
            val bmp = snap.bmp
            val found = Ocr.run(bmp, scope, (H * 0.04f).toInt())
            bmp.recycle()
            if (found.items.isEmpty()) toast("ไม่พบข้อความในหน้านี้")
            addItems(found, 1f, 0f, 0f, true)
        } finally { busy = false; ui(false) }
    }

    private suspend fun onSettle() {
        if (!active) return
        if (busy) { settleJob = scope.launch { delay(250); onSettle() }; return }
        if (lost || abs(sc - 1f) > 0.03f) { full(); return }
        if (abs(keeper.now().acc) >= H * 0.06f) strip() else prune()
    }

    /** OCR เฉพาะแถบใหม่ที่เลื่อนเข้ามา (ไม่ซ่อนโอเวอร์เลย์ เพราะแถบใหม่ยังไม่มีข้อความทับ) */
    private suspend fun strip() {
        if (busy || !active) return
        busy = true; ui(true)
        try {
            val snap = capture!!.snapshot(emptyList(), false)
            val ps = snap.pose
            if (ps.epoch != epoch) { snap.bmp.recycle(); return }
            val moved = ps.acc                       // ระยะเลื่อนสะสม ณ เฟรมของภาพนี้พอดี
            val margin = (H * 0.1f).toInt()
            val y0: Int; val y1: Int
            if (moved < 0) { y0 = max(0, H + moved.toInt() - margin); y1 = H } else { y0 = 0; y1 = min(H, moved.toInt() + margin) }
            if (y1 - y0 < 48) { snap.bmp.recycle(); return }
            val crop = Bitmap.createBitmap(snap.bmp, 0, y0, W, y1 - y0)
            val found = Ocr.run(crop, scope)
            crop.recycle(); snap.bmp.recycle()
            found.items.forEach { it.r.offset(0, y0) }
            keeper.subAcc(moved)
            addItems(Found(found.items.filter { it.r.top >= H * 0.04f }, found.tr, found.better), ps.s, ps.tx, ps.ty, false)
            prune()
        } finally { busy = false; ui(false) }
    }

    /** items อยู่ในพิกัดหน้าจอ ณ เวลา snapshot (s0,x0,y0) -> แปลงเป็น local; วางกรอบทันที แล้วเติมข้อความไทยเมื่อแปลเสร็จ */
    private suspend fun addItems(f: Found, s0: Float, x0: Float, y0: Float, whole: Boolean) {
        val items = f.items
        if (whole) { statTotal = 0; statDone = 0; statAi = false }
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
            tv.visibility = View.INVISIBLE   // ยังไม่มีคำแปล: ไม่ให้กล่องเปล่าบังต้นฉบับ
            container.addView(tv, FrameLayout.LayoutParams(w.toInt(), h.toInt()).apply { leftMargin = l.toInt(); topMargin = t.toInt() })
            views.add(Pair(tv, item))
        }
        updateMask()
        statTotal += views.size
        // คำแปลเริ่มทำงานตั้งแต่ตอนหากรอบ ส่วนใหญ่จึงเสร็จแล้ว/ใกล้เสร็จตอนมาถึงตรงนี้
        val th = try { f.tr.await() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyMap<String, String?>() }
        val missing = ArrayList<Pair<TextView, Item>>()
        for ((tv, item) in views) {
            val t = th[item.text]
            if (t.isNullOrBlank()) missing.add(Pair(tv, item)) else { tv.text = t; tv.visibility = View.VISIBLE; statDone++ }
        }
        updateMask()
        // งานที่ไม่ควรค้าง busy: ลองกรอบที่แปลไม่ได้ซ้ำ + รอคำแปลจาก AI (ช้ากว่าแต่ดีกว่า) แล้วแทนที่
        scope.launch {
            if (missing.isNotEmpty()) {
                delay(1200)
                val again = Translator.translate(missing.map { it.second.text }.distinct(), strict = false, force = true)
                val map = missing.map { it.second.text }.distinct().zip(again).toMap()
                for ((tv, item) in missing) {
                    val t = map[item.text]
                    if (!t.isNullOrBlank() && tv.parent === container) { tv.text = t; tv.visibility = View.VISIBLE; statDone++ }
                }
            }
            val bm = f.better?.let { try { it.await() } catch (e: CancellationException) { throw e } catch (e: Exception) { null } }
            if (bm != null && bm.isNotEmpty()) {
                statAi = true
                for ((tv, item) in views) {
                    val t = bm[item.text] ?: continue
                    if (tv.parent !== container) continue
                    if (tv.visibility != View.VISIBLE) statDone++
                    tv.text = t; tv.visibility = View.VISIBLE
                }
            }
            // ยังไม่มีคำแปลจริง ๆ -> เอากรอบออกให้เห็นต้นฉบับ
            for ((tv, _) in views) if (tv.parent === container && tv.visibility != View.VISIBLE) container.removeView(tv)
            updateMask()
        }
    }

    private fun report() {
        val pct = if (statTotal == 0) 0 else statDone * 100 / statTotal
        toast("แปลแล้ว $statDone/$statTotal กรอบ ($pct%) • ตัวแปล: ${if (statAi) "AI + " else ""}${Translator.engine().ifEmpty { "-" }}")
    }

    private fun makeTv(bg: Int) = TextView(this).apply {
        gravity = Gravity.CENTER; setPadding(4, 2, 4, 2)
        val lum = 0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)
        setTextColor(if (lum > 140) Color.BLACK else Color.WHITE)
        background = GradientDrawable().apply { setColor(Color.rgb(Color.red(bg), Color.green(bg), Color.blue(bg))); cornerRadius = 10f }
        breakStrategy = Layout.BREAK_STRATEGY_BALANCED       // ตัดบรรทัดให้สมดุลเหมือนในกรอบคำพูด
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        if (Prefs.bold) typeface = Typeface.DEFAULT_BOLD
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 7, Prefs.maxSp, 1, TypedValue.COMPLEX_UNIT_SP)
    }

    private fun prune() {
        for (i in container.childCount - 1 downTo 0) {
            val p = container.getChildAt(i).layoutParams as FrameLayout.LayoutParams
            val top = p.topMargin * sc + ty; val bot = top + p.height * sc
            if (bot < -H || top > 2 * H) container.removeViewAt(i)
        }
        updateMask()
    }

    private fun ui(b: Boolean) { busyUi = b; refreshBtn() }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        stopping = true
        running = false; notifyTile()
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        runCatching { dismissMenu() }
        Translator.close()
        capture?.release(); capture = null
        projCb?.let { cb -> runCatching { proj?.unregisterCallback(cb) } }
        runCatching { proj?.stop() }; proj = null
        runCatching { wm.removeView(layer) }; runCatching { wm.removeView(btn) }
        super.onDestroy()
    }
}
