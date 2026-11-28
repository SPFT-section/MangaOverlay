package com.sunmind.mangaoverlay

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.ByteBuffer

/**
 * ลบตัวอักษรเดิมด้วย MI-GAN (ONNX แบบ "pipeline": image uint8 [1,3,H,W] + mask uint8 [1,1,H,W] -> ภาพ uint8)
 * - ขั้วของมาสก์ (255 = รู หรือ 0 = รู) ต่างกันตามไฟล์ที่ export จึงทดสอบเองตอนโหลดด้วยภาพสังเคราะห์ (ช่องดำบนพื้นขาว)
 * - ภาพถูกยืดเป็นขนาดอินพุตของโมเดล (ค่าเริ่มต้น 512x512) แล้วย่อกลับ ; เอาเฉพาะพิกเซลในมาสก์ไปแทนที่ภาพเดิม
 * - รันพร้อมกันได้สูงสุด 2 งาน (กันเมมโมรี่/CPU เต็มเมื่อมีหลายกรอบ)
 */
object Inpaint {
    @Volatile private var sess: OrtSession? = null
    private var env: OrtEnvironment? = null
    private var imgName = ""
    private var maskName = ""
    private var inW = 512
    private var inH = 512
    private var chw = true          // ผลลัพธ์เป็น [1,3,H,W] (true) หรือ [1,H,W,3]
    @Volatile private var holeVal = 255
    private val gate = Semaphore(2)
    @Volatile var lastErr = ""

    fun installed(ctx: Context) = ModelStore.installed(ctx, ModelStore.INP)
    fun ready() = sess != null

    @Synchronized fun load(ctx: Context): Boolean {
        if (sess != null) return true
        val f = ModelStore.file(ctx, ModelStore.INP); if (f.length() < ModelStore.INP.minBytes) return false
        var s: OrtSession? = null
        return try {
            val e = OrtEnvironment.getEnvironment()
            s = e.createSession(f.absolutePath, OrtSession.SessionOptions().apply { setIntraOpNumThreads(3) })
            val names = s.inputNames.toList()
            if (names.size != 2) { lastErr = "ต้องมีอินพุต 2 ตัว (image, mask) แต่พบ ${names.size}"; s.close(); return false }
            maskName = names.firstOrNull { it.contains("mask", true) } ?: names[1]
            imgName = names.first { it != maskName }
            val ii = s.inputInfo[imgName]!!.info as TensorInfo
            val mi = s.inputInfo[maskName]!!.info as TensorInfo
            if (ii.type != OnnxJavaType.UINT8 || mi.type != OnnxJavaType.UINT8) {
                lastErr = "รองรับเฉพาะ MI-GAN pipeline ที่อินพุตเป็น uint8 (พบ ${ii.type}/${mi.type})"; s.close(); return false
            }
            val sh = ii.shape
            if (sh.size == 4) {
                inH = if (sh[2] > 0) sh[2].toInt() else 512
                inW = if (sh[3] > 0) sh[3].toInt() else 512
            }
            env = e; sess = s
            if (!calibrate()) { sess = null; s.close(); return false }
            true
        } catch (t: Throwable) { runCatching { s?.close() }; sess = null; lastErr = t.javaClass.simpleName + ": " + (t.message ?: ""); false }
    }

    @Synchronized fun close() { runCatching { sess?.close() }; sess = null }

    /** ภาพทดสอบ: พื้นขาว + ช่องดำกลางภาพ ; ขั้วที่ถูก = ช่องดำถูกเติมให้เป็นสีขาว */
    private fun calibrate(): Boolean {
        val px = IntArray(inW * inH) { -1 }
        val hole = BooleanArray(inW * inH)
        val cx = inW / 2; val cy = inH / 2; val q = minOf(inW, inH) / 8
        for (y in cy - q until cy + q) for (x in cx - q until cx + q) px[y * inW + x] = 0xFF000000.toInt()
        for (y in cy - q - 6 until cy + q + 6) for (x in cx - q - 6 until cx + q + 6) hole[y * inW + x] = true
        for (v in intArrayOf(255, 0)) {
            holeVal = v
            val o = runRaw(px, hole) ?: return false
            var sum = 0L; var n = 0
            for (y in cy - q / 2 until cy + q / 2) for (x in cx - q / 2 until cx + q / 2) { sum += (o[y * inW + x] shr 8) and 255; n++ }
            if (sum / n > 128) return true
        }
        lastErr = "ทดสอบขั้วมาสก์ไม่ผ่าน (โมเดลอาจไม่ใช่ MI-GAN pipeline)"; return false
    }

    /** px: ARGB ขนาด inW x inH ; hole: true = จุดที่ให้โมเดลวาดใหม่ ; คืน ARGB ขนาดเท่ากัน */
    private fun runRaw(px: IntArray, hole: BooleanArray): IntArray? {
        val s = sess ?: return null; val e = env ?: return null
        val n = inW * inH
        val img = ByteBuffer.allocateDirect(3 * n); val m = ByteBuffer.allocateDirect(n)
        for (i in 0 until n) {
            val c = px[i]
            img.put(i, ((c shr 16) and 255).toByte()); img.put(n + i, ((c shr 8) and 255).toByte()); img.put(2 * n + i, (c and 255).toByte())
            m.put(i, (if (hole[i]) holeVal else 255 - holeVal).toByte())
        }
        val t1 = OnnxTensor.createTensor(e, img, longArrayOf(1, 3, inH.toLong(), inW.toLong()), OnnxJavaType.UINT8)
        val t2 = OnnxTensor.createTensor(e, m, longArrayOf(1, 1, inH.toLong(), inW.toLong()), OnnxJavaType.UINT8)
        try {
            s.run(mapOf(imgName to t1, maskName to t2)).use { res ->
                val ot = res[0] as OnnxTensor
                val shp = ot.info.shape
                chw = !(shp.size == 4 && shp[3] == 3L)
                val bb = ot.byteBuffer; val b = ByteArray(bb.remaining()); bb.get(b)
                if (b.size < 3 * n) { lastErr = "ขนาดเอาต์พุตไม่ตรง"; return null }
                val out = IntArray(n)
                for (i in 0 until n) {
                    val r: Int; val g: Int; val bl: Int
                    if (chw) { r = b[i].toInt() and 255; g = b[n + i].toInt() and 255; bl = b[2 * n + i].toInt() and 255 }
                    else { r = b[3 * i].toInt() and 255; g = b[3 * i + 1].toInt() and 255; bl = b[3 * i + 2].toInt() and 255 }
                    out[i] = (255 shl 24) or (r shl 16) or (g shl 8) or bl
                }
                return out
            }
        } catch (t: Throwable) { lastErr = t.javaClass.simpleName + ": " + (t.message ?: ""); return null }
        finally { t1.close(); t2.close() }
    }

    /**
     * ลบบริเวณ holes (พิกัดในภาพ px ขนาด cw x ch) แล้วคืนภาพขนาดเดิม ; null = ทำไม่ได้ (ผู้เรียกใช้สีพื้นแทน)
     * คืนเฉพาะพิกเซลในมาสก์ที่ถูกแทนที่ ส่วนที่เหลือคงเป็นภาพเดิมเป๊ะ
     */
    suspend fun erase(px: IntArray, cw: Int, ch: Int, holes: List<Rect>): IntArray? {
        if (sess == null || holes.isEmpty()) return null
        return gate.withPermit {
            try {
                val src = Bitmap.createBitmap(px, cw, ch, Bitmap.Config.ARGB_8888)
                val sc = Bitmap.createScaledBitmap(src, inW, inH, true)
                val spx = IntArray(inW * inH); sc.getPixels(spx, 0, inW, 0, 0, inW, inH)
                if (sc !== src) sc.recycle(); src.recycle()
                val mk = BooleanArray(inW * inH)
                val fx = inW.toFloat() / cw; val fy = inH.toFloat() / ch
                for (h in holes) {
                    val x0 = (h.left * fx).toInt().coerceIn(0, inW); val x1 = (h.right * fx).toInt().coerceIn(0, inW)
                    val y0 = (h.top * fy).toInt().coerceIn(0, inH); val y1 = (h.bottom * fy).toInt().coerceIn(0, inH)
                    for (y in y0 until y1) for (x in x0 until x1) mk[y * inW + x] = true
                }
                val o = runRaw(spx, mk) ?: return@withPermit null
                val ob = Bitmap.createBitmap(o, inW, inH, Bitmap.Config.ARGB_8888)
                val back = Bitmap.createScaledBitmap(ob, cw, ch, true)
                val bp = IntArray(cw * ch); back.getPixels(bp, 0, cw, 0, 0, cw, ch)
                if (back !== ob) back.recycle(); ob.recycle()
                val res = px.copyOf()
                for (h in holes) {
                    val x0 = h.left.coerceIn(0, cw); val x1 = h.right.coerceIn(0, cw)
                    val y0 = h.top.coerceIn(0, ch); val y1 = h.bottom.coerceIn(0, ch)
                    for (y in y0 until y1) for (x in x0 until x1) res[y * cw + x] = bp[y * cw + x]
                }
                res
            } catch (t: Throwable) { lastErr = t.javaClass.simpleName + ": " + (t.message ?: ""); null }
        }
    }
}
