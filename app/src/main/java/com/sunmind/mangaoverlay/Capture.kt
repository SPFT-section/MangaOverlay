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
private const val GAP_MOVE = 14_000_000L   // ns: ช่วงห่างขั้นต่ำระหว่างเฟรมที่ประมวลผลตอนกำลังเลื่อน
private const val GAP_IDLE = 30_000_000L   // ns: ตอนหน้าจอนิ่ง

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
    /** หน้าจอนิ่งอยู่ (ตั้งจากผลการตามล่าสุด): ลดอัตราการตามเหลือ ~30 fps เพื่อลดภาระ CPU/แบตตอนผู้ใช้กำลังอ่าน */
    @Volatile var idle = false
    private var lastProc = 0L
    private val sw = w / Q
    private val sh = h / Q
    private val grayBuf = ByteArray(sw * sh)   // ใช้ซ้ำทุกเฟรม: Tracker.step() คัดลอกเข้า Mat ทันทีบน thread เดียวกัน
    private var row0 = ByteArray(0)
    private var row1 = ByteArray(0)
    /** เรียกบน thread ของ Capture ทุกเฟรมที่เปิดการติดตาม: (ภาพเทาย่อ, เวลาเฟรมเป็น ns) */
    @Volatile var onGray: ((ByteArray, Long) -> Unit)? = null

    init {
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            held?.close(); held = img; frames++
            if (tracking) {
                // จอรีเฟรช 90/120Hz ไม่จำเป็นต้องตามทุกเฟรม: จำกัด ~65 fps ตอนเลื่อน และ ~30 fps ตอนนิ่ง
                val ts = img.timestamp
                if (ts - lastProc >= (if (idle) GAP_IDLE else GAP_MOVE)) {
                    lastProc = ts
                    runCatching { onGray?.invoke(gray(img), ts) }
                }
            }
        }, handler)
        vd = proj.createVirtualDisplay("manga", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler)
    }

    /**
     * ย่อภาพเป็นเทาโดยเฉลี่ย 2x2 ต่อจุด (เดิมสุ่มจุดเดียว ทำให้เกิด aliasing บนตัวหนังสือ/ลายสกรีน)
     * คัดลอกทีละแถวด้วย bulk get แล้วคำนวณบน ByteArray (เดิมเรียก ByteBuffer.get ~3.5 ล้านครั้งต่อเฟรม) ผลลัพธ์ค่าเดียวกันทุกจุด
     */
    private fun gray(img: Image): ByteArray {
        val p = img.planes[0]; val rs = p.rowStride; val ps = p.pixelStride
        val b = p.buffer.duplicate(); val buf = b as java.nio.Buffer
        val lim = b.limit()
        val span = sw * Q * ps
        if (row0.size < span) { row0 = ByteArray(span); row1 = ByteArray(span) }
        val out = grayBuf
        val step = Q * ps
        for (y in 0 until sh) {
            val o0 = y * Q * rs
            val o1 = o0 + (if (Q > 1) rs else 0)
            if (o1 + span > lim) break   // ไม่ควรเกิด (บัฟเฟอร์ยาว rs*h) กันไว้ไม่ให้ล้ม
            buf.position(o0); b.get(row0, 0, span)
            val r1 = if (Q > 1) { buf.position(o1); b.get(row1, 0, span); row1 } else row0
            var xi = 0; var oi = y * sw
            for (x in 0 until sw) {
                val c = xi + ps
                val s0 = ((row0[xi].toInt() and 255) * 77 + (row0[xi + 1].toInt() and 255) * 150 + (row0[xi + 2].toInt() and 255) * 29) shr 8
                val s1 = ((row0[c].toInt() and 255) * 77 + (row0[c + 1].toInt() and 255) * 150 + (row0[c + 2].toInt() and 255) * 29) shr 8
                val s2 = ((r1[xi].toInt() and 255) * 77 + (r1[xi + 1].toInt() and 255) * 150 + (r1[xi + 2].toInt() and 255) * 29) shr 8
                val s3 = ((r1[c].toInt() and 255) * 77 + (r1[c + 1].toInt() and 255) * 150 + (r1[c + 2].toInt() and 255) * 29) shr 8
                out[oi++] = ((s0 + s1 + s2 + s3) shr 2).toByte()
                xi += step
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
