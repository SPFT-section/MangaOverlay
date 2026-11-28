package com.sunmind.mangaoverlay

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.view.View
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val Q = 3 // tracking downscale factor (ละเอียดขึ้นจาก 4 -> ตำแหน่งแม่นขึ้น)

/** ภาพเต็มความละเอียด + ท่าของหน้า ณ เฟรมนั้นพอดี */
class Snap(val bmp: Bitmap, val pose: Pose)

class Capture(proj: MediaProjection, val w: Int, val h: Int, dpi: Int, private val keeper: PoseKeeper) {
    private val ht = HandlerThread("cap").apply { start() }
    private val handler = Handler(ht.looper)
    private val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
    private var held: Image? = null
    private val vd: VirtualDisplay
    @Volatile var frames = 0
    @Volatile var tracking = false
    /** เรียกบน thread ของ Capture ทุกเฟรมที่เปิดการติดตาม: (ภาพเทาย่อ, เวลาเฟรมเป็น ns) */
    @Volatile var onGray: ((ByteArray, Long) -> Unit)? = null

    init {
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            held?.close(); held = img; frames++
            if (tracking) runCatching { onGray?.invoke(gray(img), img.timestamp) }
        }, handler)
        vd = proj.createVirtualDisplay("manga", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler)
    }

    // ใช้ซ้ำทุกเฟรม: ผู้รับ (Tracker.step) คัดลอกเข้า Mat ทันทีบน thread เดียวกัน จึงไม่ซ้อนกัน
    private var grayBuf: ByteArray? = null

    /** ย่อภาพเป็นเทาโดยเฉลี่ย 2x2 ต่อจุด (เดิมสุ่มจุดเดียว ทำให้เกิด aliasing บนตัวหนังสือ/ลายสกรีน) */
    private fun gray(img: Image): ByteArray {
        val p = img.planes[0]; val b = p.buffer; val rs = p.rowStride; val ps = p.pixelStride
        val sw = w / Q; val sh = h / Q
        val buf = grayBuf?.takeIf { it.size == sw * sh } ?: ByteArray(sw * sh).also { grayBuf = it }
        if (Native.gray(b, b.limit(), rs, ps, w, h, Q, buf)) return buf
        return grayKt(b, rs, ps, sw, sh)
    }

    /** ทางสำรอง (Kotlin เดิม) เมื่อโหลดเนทีฟไม่ได้ */
    private fun grayKt(b: java.nio.ByteBuffer, rs: Int, ps: Int, sw: Int, sh: Int): ByteArray {
        val out = ByteArray(sw * sh)
        val last = b.limit() - 1
        for (y in 0 until sh) {
            val r0 = y * Q * rs
            val r1 = r0 + (if (Q > 1) rs else 0)
            for (x in 0 until sw) {
                val i0 = r0 + x * Q * ps; val i1 = r1 + x * Q * ps
                var sum = 0
                for (k in 0..3) {
                    val i = (if (k < 2) i0 else i1) + (k and 1) * ps
                    if (i + 2 > last) continue
                    sum += ((b.get(i).toInt() and 255) * 77 + (b.get(i + 1).toInt() and 255) * 150 + (b.get(i + 2).toInt() and 255) * 29) shr 8
                }
                out[y * sw + x] = (sum shr 2).toByte()
            }
        }
        return out
    }

    private fun toBitmap(img: Image): Bitmap {
        val p = img.planes[0]; val bw = p.rowStride / p.pixelStride
        p.buffer.rewind()
        val bmp = Bitmap.createBitmap(bw, img.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(p.buffer)
        if (bw == img.width) return bmp
        val cropped = Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
        bmp.recycle()
        return cropped
    }

    /**
     * ซ่อนโอเวอร์เลย์ของเราเอง รอเฟรมที่สะอาด แล้วคืนภาพเต็ม + ท่าของหน้า ณ เฟรมนั้น
     * resetPose=true: ตั้งต้นท่าเป็นศูนย์ที่เฟรมนี้พอดี (ใช้ตอนสแกนทั้งหน้า) การเลื่อนหลังจากนี้จึงนับต่อจากภาพที่ OCR
     */
    suspend fun snapshot(hide: List<View>, wait: Boolean, resetPose: Boolean = false): Snap {
        val f0 = frames
        hide.forEach { it.visibility = View.INVISIBLE }
        try {
            var t = 0
            while (held == null && t < 2000) { delay(16); t += 16 }
            if (held == null) throw IllegalStateException("no frame")
            if (wait) { t = 0; while (frames == f0 && t < 250) { delay(16); t += 16 }; delay(32) }
            return suspendCancellableCoroutine { c ->
                handler.post {
                    try {
                        val img = held ?: throw IllegalStateException("no frame")
                        // ทั้งสองอย่างรันบน handler เดียวกับ listener ของเฟรม จึงสอดคล้องกับ held พอดี
                        val pose = if (resetPose) keeper.reset() else keeper.now()
                        c.resume(Snap(toBitmap(img), pose))
                    } catch (e: Throwable) { c.resumeWithException(e) }
                }
            }
        } finally {
            // คืนการมองเห็นเสมอ แม้เกิด error/ถูกยกเลิก ไม่งั้นโอเวอร์เลย์หายถาวร
            hide.forEach { it.visibility = View.VISIBLE }
        }
    }

    fun release() {
        vd.release(); reader.close(); held?.close(); ht.quitSafely()
    }
}
