package com.sunmind.mangaoverlay

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * ที่เก็บโมเดล ONNX ทั้งสามตัว (ทุกตัวรันออฟไลน์ในเครื่องผ่าน ONNX Runtime ตัวเดียวกัน)
 *   DET = YOLOv8n-bubble   หากรอบคำพูด (bbox)
 *   REC = PP-OCR rec       อ่านตัวอักษรทีละบรรทัด (v4/v5 mobile ใช้ได้ทั้งคู่ ขอแค่มี metadata "character")
 *   INP = MI-GAN           ลบตัวอักษรเดิมในกรอบ แล้วเติมพื้นหลังให้ต่อเนื่อง
 * ได้ไฟล์มาสองทาง: ใส่ URL ให้โหลด หรือเลือกไฟล์ .onnx จากเครื่อง (ไม่ต้องพึ่งเน็ต)
 */
object ModelStore {
    class Spec(val id: String, val fileName: String, val minBytes: Long, val defaultUrl: String, val sha: String?)

    // ลิงก์ค่าเริ่มต้นเป็นของที่ผู้ใช้เลือก (ตรวจชื่อไฟล์จากหน้า repo แล้ว) ; ใส่ URL อื่นทับได้ในหน้าตั้งค่า
    // DET  : kitsumed/yolov8m_seg-speech-bubble (YOLOv8m-seg, 1 คลาส "speech bubble", GPL-3.0) ไฟล์ dynamic 109 MB
    // REC  : en_PP-OCRv5_mobile_rec (PaddlePaddle, Apache-2.0) สำเนาของ bakshinghampalace ; ไฟล์ export ของ Paddle ไม่มี metadata ตัวอักษร จึงต้องมี RDICT
    // RDICT: inference.yml ของโมเดลเดียวกัน (มีรายการ character_dict) ; ไฟล์ .txt ทีละบรรทัดก็ใช้ได้
    // INP  : MI-GAN pipeline v2 (Picsart, MIT) อินพุต uint8 ขนาดอิสระ ; มาสก์ 0 = ลบ
    private const val HF = "https://huggingface.co/"
    val REC = Spec("rec", "rec.onnx", 1_000_000, HF + "bakshinghampalace/en-ppocrv5-mobile-rec-onnx/resolve/main/onnx/model.onnx", null)
    val RDICT = Spec("recdict", "rec_dict.txt", 100, HF + "bakshinghampalace/en-ppocrv5-mobile-rec-onnx/resolve/main/onnx/inference.yml", null)
    val DET = Spec("det", "bubble.onnx", 500_000, HF + "kitsumed/yolov8m_seg-speech-bubble/resolve/main/model_dynamic.onnx",
        "36c26bdefe150226acd9669772e9ff5a011fa0dd4622469b49d3d5e359f3251c")
    val INP = Spec("inp", "migan.onnx", 1_000_000, HF + "square-zero-labs/migan/resolve/main/migan_pipeline_v2.onnx", null)

    fun file(ctx: Context, s: Spec) = File(File(ctx.filesDir, "models").apply { mkdirs() }, s.fileName)
    fun installed(ctx: Context, s: Spec) = file(ctx, s).length() >= s.minBytes

    private fun release(s: Spec) {
        when (s.id) { "rec", "recdict" -> PpRec.close(); "det" -> BubbleDet.close(); "inp" -> Inpaint.close() }
    }

    /** คืน null = สำเร็จ ไม่งั้นคืนข้อความผิดพลาด ; เรียกบน thread พื้นหลัง */
    fun download(ctx: Context, s: Spec, url0: String, onProgress: (Int) -> Unit): String? {
        val url = url0.ifBlank { s.defaultUrl }
        if (url.isBlank()) return "ยังไม่ได้ใส่ URL ของโมเดลนี้ (ใส่ลิงก์ .onnx หรือใช้ปุ่มเลือกไฟล์)"
        val dst = file(ctx, s); val tmp = File(dst.parentFile, s.fileName + ".tmp")
        var c: HttpURLConnection? = null
        try {
            c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 20000; c.instanceFollowRedirects = true
            if (c.responseCode != 200) return "HTTP ${c.responseCode}"
            val total = c.contentLengthLong; var got = 0L; var last = -1
            val md = MessageDigest.getInstance("SHA-256")
            c.inputStream.use { ins -> tmp.outputStream().use { os ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    os.write(buf, 0, n); md.update(buf, 0, n); got += n
                    if (total > 0) { val p = (got * 100 / total).toInt(); if (p != last) { last = p; onProgress(p) } }
                }
            } }
            if (got < s.minBytes) { tmp.delete(); return "ไฟล์เล็กผิดปกติ ($got ไบต์)" }
            if (s.sha != null && url == s.defaultUrl && md.digest().joinToString("") { "%02x".format(it) } != s.sha) {
                tmp.delete(); return "SHA-256 ไม่ตรง (ไฟล์เสียหรือถูกเปลี่ยน)"
            }
            release(s); dst.delete()
            return if (tmp.renameTo(dst)) null else "บันทึกไฟล์ไม่ได้"
        } catch (e: Exception) { tmp.delete(); return e.javaClass.simpleName + ": " + (e.message ?: "") }
        finally { c?.disconnect() }
    }

    /** คัดลอกไฟล์ .onnx ที่ผู้ใช้เลือก (Storage Access Framework) เข้าที่เก็บของแอป */
    fun importFrom(ctx: Context, s: Spec, uri: Uri): String? {
        val dst = file(ctx, s); val tmp = File(dst.parentFile, s.fileName + ".tmp")
        try {
            val ins = ctx.contentResolver.openInputStream(uri) ?: return "เปิดไฟล์ไม่ได้"
            ins.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            if (tmp.length() < s.minBytes) { val n = tmp.length(); tmp.delete(); return "ไฟล์เล็กผิดปกติ ($n ไบต์)" }
            release(s); dst.delete()
            return if (tmp.renameTo(dst)) null else "บันทึกไฟล์ไม่ได้"
        } catch (e: Exception) { tmp.delete(); return e.javaClass.simpleName + ": " + (e.message ?: "") }
    }

    fun load(ctx: Context, s: Spec): Boolean = when (s.id) {
        "rec", "recdict" -> PpRec.load(ctx); "det" -> BubbleDet.load(ctx); else -> Inpaint.load(ctx)
    }
    fun lastErr(s: Spec): String = when (s.id) { "rec", "recdict" -> PpRec.lastErr; "det" -> BubbleDet.lastErr; else -> Inpaint.lastErr }
}
