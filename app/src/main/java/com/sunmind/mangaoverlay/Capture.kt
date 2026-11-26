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

const val Q = 4 // tracking downscale factor

class Capture(proj: MediaProjection, val w: Int, val h: Int, dpi: Int) {
    private val ht = HandlerThread("cap").apply { start() }
    private val handler = Handler(ht.looper)
    private val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
    private var held: Image? = null
    private val vd: VirtualDisplay
    @Volatile var frames = 0
    @Volatile var tracking = false
    @Volatile var onGray: ((ByteArray) -> Unit)? = null

    init {
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            held?.close(); held = img; frames++
            if (tracking) runCatching { onGray?.invoke(gray(img)) }
        }, handler)
        vd = proj.createVirtualDisplay("manga", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler)
    }

    private fun gray(img: Image): ByteArray {
        val p = img.planes[0]; val b = p.buffer; val rs = p.rowStride; val ps = p.pixelStride
        val sw = w / Q; val sh = h / Q; val out = ByteArray(sw * sh)
        for (y in 0 until sh) {
            val row = y * Q * rs
            for (x in 0 until sw) {
                val i = row + x * Q * ps
                out[y * sw + x] = (((b.get(i).toInt() and 255) * 77 + (b.get(i + 1).toInt() and 255) * 150 + (b.get(i + 2).toInt() and 255) * 29) shr 8).toByte()
            }
        }
        return out
    }

    private fun toBitmap(img: Image): Bitmap {
        val p = img.planes[0]; val bw = p.rowStride / p.pixelStride
        p.buffer.rewind()
        val bmp = Bitmap.createBitmap(bw, img.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(p.buffer)
        return if (bw == img.width) bmp else Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
    }

    /** Hides our own overlay views, waits for a clean frame, returns full-res bitmap. */
    suspend fun snapshot(hide: List<View>, wait: Boolean): Bitmap {
        val f0 = frames
        hide.forEach { it.visibility = View.INVISIBLE }
        var t = 0
        while (held == null && t < 2000) { delay(16); t += 16 }
        if (wait) { t = 0; while (frames == f0 && t < 250) { delay(16); t += 16 }; delay(32) }
        val bmp = suspendCancellableCoroutine<Bitmap> { c ->
            handler.post { try { c.resume(toBitmap(held!!)) } catch (e: Throwable) { c.resumeWithException(e) } }
        }
        hide.forEach { it.visibility = View.VISIBLE }
        return bmp
    }

    fun release() {
        vd.release(); reader.close(); held?.close(); ht.quitSafely()
    }
}
