package com.sunmind.mangaoverlay

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class Det(val r: Rect, val conf: Float, val cls: Int)

/**
 * ตัวหากรอบคำพูดด้วย YOLOv8n (ONNX) : เอาต์พุตมาตรฐาน [1, 4+nc, N] (cx,cy,w,h + คะแนนแต่ละคลาส)
 * รองรับ [1, N, 4+nc] และแบบ end-to-end [1, N, 6] (x1,y1,x2,y2,conf,cls) ด้วย ; ขนาดอินพุตอ่านจากโมเดล (ไม่ระบุ = 640)
 * หน้าที่: บอกว่าข้อความไหนอยู่กรอบเดียวกัน + ขอบเขตกรอบ (แทนการเดาด้วย flood-fill) ; ไม่อ่านตัวอักษรเอง
 */
object BubbleDet {
    private const val CONF = 0.35f
    private const val IOU = 0.5f

    @Volatile private var sess: OrtSession? = null
    private var env: OrtEnvironment? = null
    private var inW = 640
    private var inH = 640
    @Volatile var lastErr = ""

    fun installed(ctx: Context) = ModelStore.installed(ctx, ModelStore.DET)
    fun ready() = sess != null

    @Synchronized fun load(ctx: Context): Boolean {
        if (sess != null) return true
        val f = ModelStore.file(ctx, ModelStore.DET); if (f.length() < ModelStore.DET.minBytes) return false
        return try {
            val e = OrtEnvironment.getEnvironment()
            val s = e.createSession(f.absolutePath, OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) })
            val sh = (s.inputInfo.values.first().info as TensorInfo).shape
            if (sh.size != 4) { s.close(); lastErr = "อินพุตไม่ใช่ [1,3,H,W]"; return false }
            inH = if (sh[2] > 0) sh[2].toInt() else 640
            inW = if (sh[3] > 0) sh[3].toInt() else 640
            env = e; sess = s; true
        } catch (t: Throwable) { lastErr = t.javaClass.simpleName + ": " + (t.message ?: ""); false }
    }

    @Synchronized fun close() { runCatching { sess?.close() }; sess = null }

    /** คืนกรอบในพิกัดของ bmp ; เรียกบน thread พื้นหลัง (บล็อกจนเสร็จ) */
    fun detect(bmp: Bitmap): List<Det> {
        val s = sess ?: return emptyList(); val e = env ?: return emptyList()
        try {
            val w = bmp.width; val h = bmp.height
            val r = min(inW.toFloat() / w, inH.toFloat() / h)
            val nw = max(1, (w * r).roundToInt()); val nh = max(1, (h * r).roundToInt())
            val padX = (inW - nw) / 2; val padY = (inH - nh) / 2
            val px = IntArray(nw * nh)
            val sc = Bitmap.createScaledBitmap(bmp, nw, nh, true)
            sc.getPixels(px, 0, nw, 0, 0, nw, nh)
            if (sc !== bmp) sc.recycle()
            val plane = inW * inH
            val arr = FloatArray(3 * plane) { 114f / 255f }   // ขอบเติมสีเทาเหมือนตอนฝึก (letterbox)
            for (y in 0 until nh) for (x in 0 until nw) {
                val c = px[y * nw + x]; val i = (y + padY) * inW + x + padX
                arr[i] = ((c shr 16) and 255) / 255f
                arr[plane + i] = ((c shr 8) and 255) / 255f
                arr[2 * plane + i] = (c and 255) / 255f
            }
            val cand = ArrayList<Det>()
            OnnxTensor.createTensor(e, FloatBuffer.wrap(arr), longArrayOf(1, 3, inH.toLong(), inW.toLong())).use { t ->
                s.run(mapOf(s.inputNames.first() to t)).use { res ->
                    val ot = res[0] as OnnxTensor
                    val shp = ot.info.shape
                    val fb = ot.floatBuffer; val d = FloatArray(fb.remaining()); fb.get(d)
                    if (shp.size == 3) parse(d, shp[1].toInt(), shp[2].toInt(), r, padX, padY, w, h, cand)
                }
            }
            return nms(cand)
        } catch (t: Throwable) { lastErr = t.javaClass.simpleName + ": " + (t.message ?: ""); return emptyList() }
    }

    private fun parse(d: FloatArray, a: Int, b: Int, r: Float, padX: Int, padY: Int, w: Int, h: Int, out: MutableList<Det>) {
        fun add(x1: Float, y1: Float, x2: Float, y2: Float, conf: Float, cls: Int) {
            if (conf < CONF) return
            val l = ((x1 - padX) / r).toInt().coerceIn(0, w - 1); val t = ((y1 - padY) / r).toInt().coerceIn(0, h - 1)
            val rr = ((x2 - padX) / r).toInt().coerceIn(l + 1, w); val bb = ((y2 - padY) / r).toInt().coerceIn(t + 1, h)
            if (rr - l >= 24 && bb - t >= 24) out.add(Det(Rect(l, t, rr, bb), conf, cls))
        }
        if (a > b && b == 6) {                    // end-to-end: [N, 6]
            for (i in 0 until a) { val o = i * 6; add(d[o], d[o + 1], d[o + 2], d[o + 3], d[o + 4], d[o + 5].toInt()) }
            return
        }
        val first = a < b                         // [C, N] (มาตรฐาน) หรือ [N, C]
        val c = if (first) a else b; val n = if (first) b else a
        if (c < 5) return
        fun v(ch: Int, i: Int) = if (first) d[ch * n + i] else d[i * c + ch]
        for (i in 0 until n) {
            var best = 0f; var bc = 0
            for (k in 4 until c) { val sc = v(k, i); if (sc > best) { best = sc; bc = k - 4 } }
            if (best < CONF) continue
            val cx = v(0, i); val cy = v(1, i); val bw = v(2, i); val bh = v(3, i)
            add(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2, best, bc)
        }
    }

    private fun iou(a: Rect, b: Rect): Float {
        val l = max(a.left, b.left); val t = max(a.top, b.top); val r = min(a.right, b.right); val bt = min(a.bottom, b.bottom)
        val i = max(0, r - l).toLong() * max(0, bt - t)
        val u = a.width().toLong() * a.height() + b.width().toLong() * b.height() - i
        return if (u <= 0) 0f else i.toFloat() / u
    }

    private fun nms(c: List<Det>): List<Det> {
        val keep = ArrayList<Det>()
        for (d in c.sortedByDescending { it.conf }) {
            if (keep.none { iou(it.r, d.r) > IOU }) keep.add(d)
            if (keep.size >= 40) break
        }
        return keep
    }
}
