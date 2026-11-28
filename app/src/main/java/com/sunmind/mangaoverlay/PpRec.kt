package com.sunmind.mangaoverlay

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * ตัวอ่านตัวอักษรจากโมเดล PP-OCR recognition (ONNX, v4 หรือ v5 mobile) ที่โหลด/นำเข้าครั้งเดียวแล้วรันในเครื่อง (ออฟไลน์)
 * อ่านทีละบรรทัด (รับกรอบบรรทัดจาก ML Kit) ; จำกัดคำตอบเป็น ASCII เพราะมังงะที่อ่านเป็นภาษาอังกฤษ (โมเดลรู้จักจีนด้วย แต่จะทำให้เดามั่ว)
 * ทดสอบแล้วกับภาพจริง: AAAAAGH!! (0.96), "THE DOCK DISTRICT OF / SLIMEP.. IS LIBERATED" ที่ ML Kit อ่านเป็นขยะ
 */
object PpRec {
    private const val H = 48
    private const val MINW = 320

    @Volatile private var sess: OrtSession? = null
    private var env: OrtEnvironment? = null
    private var chars: List<String> = emptyList()
    private var allowed = IntArray(0)
    @Volatile var lastErr = ""

    fun file(ctx: Context) = ModelStore.file(ctx, ModelStore.REC)
    fun installed(ctx: Context) = ModelStore.installed(ctx, ModelStore.REC)
    fun ready() = sess != null

    @Synchronized fun load(ctx: Context): Boolean {
        if (sess != null) return true
        val f = file(ctx); if (f.length() < 1_000_000) return false
        return try {
            val e = OrtEnvironment.getEnvironment()
            val s = e.createSession(f.absolutePath, OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) })
            val ch0 = s.metadata.customMetadata["character"]?.split('\n')
            if (ch0.isNullOrEmpty()) { s.close(); lastErr = "โมเดลไม่มีตัวบอกชุดตัวอักษร (character)"; return false }
            // v5 มีชุดตัวอักษรใหญ่กว่า v4 มาก และบางไฟล์ลงท้ายด้วยบรรทัดว่าง : ใช้ขนาดเอาต์พุตของโมเดลตัดสินว่าต้องทิ้งบรรทัดท้ายไหม
            var ch: List<String> = ch0
            val od = ((s.outputInfo.values.first().info as? TensorInfo)?.shape?.lastOrNull() ?: -1L).toInt()
            if (od > 0 && ch.last().isEmpty() && od != ch.size + 2 && od != ch.size + 1) ch = ch.dropLast(1)
            chars = ch
            // index 0 = blank, 1..n = chars, n+1 = เว้นวรรค ; เก็บเฉพาะ ASCII ที่พิมพ์ได้
            val al = ArrayList<Int>()
            for (i in ch.indices) if (ch[i].length == 1 && ch[i][0].code in 33..126) al.add(i + 1)
            al.add(ch.size + 1)
            allowed = al.toIntArray()
            env = e; sess = s; true
        } catch (e: Throwable) { lastErr = e.javaClass.simpleName + ": " + (e.message ?: ""); false }
    }

    @Synchronized fun close() { runCatching { sess?.close() }; sess = null }

    /** อ่านข้อความบรรทัดเดียวในกรอบ r ของภาพ bmp : คืน (ข้อความ, ความมั่นใจ 0..1) */
    fun recognize(bmp: Bitmap, r: Rect): Pair<String, Float>? {
        val s = sess ?: return null; val e = env ?: return null
        val x0 = max(0, r.left - 3); val y0 = max(0, r.top - 3)
        val x1 = min(bmp.width, r.right + 3); val y1 = min(bmp.height, r.bottom + 3)
        val cw = x1 - x0; val ch = y1 - y0
        if (cw < 6 || ch < 6) return null
        val w = ceil(H * cw.toFloat() / ch).toInt().coerceIn(8, 2400)
        val wd = max(w, MINW)
        val px = IntArray(w * H)
        try {
            val crop = Bitmap.createBitmap(bmp, x0, y0, cw, ch)
            val sc = Bitmap.createScaledBitmap(crop, w, H, true)
            sc.getPixels(px, 0, w, 0, 0, w, H)
            if (sc !== crop) sc.recycle(); if (crop !== bmp) crop.recycle()
        } catch (ex: Exception) { return null }
        val plane = H * wd; val arr = FloatArray(3 * plane)   // ส่วนที่เหลือเป็น 0 = ขอบเติมเหมือนตอนฝึก
        for (y in 0 until H) for (x in 0 until w) {
            val c = px[y * w + x]; val i = y * wd + x
            arr[i] = ((c and 255) / 255f - 0.5f) / 0.5f                    // B (โมเดลรับ BGR)
            arr[plane + i] = (((c shr 8) and 255) / 255f - 0.5f) / 0.5f    // G
            arr[2 * plane + i] = (((c shr 16) and 255) / 255f - 0.5f) / 0.5f // R
        }
        return try {
            OnnxTensor.createTensor(e, FloatBuffer.wrap(arr), longArrayOf(1, 3, H.toLong(), wd.toLong())).use { t ->
                s.run(mapOf(s.inputNames.first() to t)).use { res ->
                    @Suppress("UNCHECKED_CAST") val o = (res[0].value as Array<Array<FloatArray>>)[0]
                    decode(o)
                }
            }
        } catch (ex: Throwable) { lastErr = ex.javaClass.simpleName; null }
    }

    /** CTC แบบ greedy เฉพาะ ASCII+เว้นวรรค */
    private fun decode(o: Array<FloatArray>): Pair<String, Float>? {
        // เอาต์พุต = blank + ตัวอักษร (+ เว้นวรรคถ้าโมเดลมี) ; ไม่ตรงทั้งสองแบบ = ชุดตัวอักษรกับโมเดลไม่ใช่คู่กัน
        if (o.isEmpty() || (o[0].size != chars.size + 2 && o[0].size != chars.size + 1)) { lastErr = "รูปแบบเอาต์พุตของโมเดลไม่ตรงกับชุดตัวอักษร"; return null }
        val sb = StringBuilder(); var prev = -1; var sum = 0f; var n = 0
        for (row in o) {
            var bi = 0; var bp = row[0]
            for (i in allowed) if (i < row.size && row[i] > bp) { bp = row[i]; bi = i }
            if (bi != 0 && bi != prev) { sb.append(if (bi == chars.size + 1) " " else chars[bi - 1]); sum += bp; n++ }
            prev = bi
        }
        val t = sb.toString().trim()
        return if (t.isEmpty()) null else Pair(t, sum / max(n, 1))
    }
}
