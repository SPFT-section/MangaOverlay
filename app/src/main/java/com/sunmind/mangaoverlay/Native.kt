package com.sunmind.mangaoverlay

import java.nio.ByteBuffer

/**
 * สะพานไป libmocalc.so (C++17, src/main/cpp). โหลดไม่ได้หรือเกิดข้อผิดพลาด -> ปิดตัวเองถาวร
 * แล้วผู้เรียกจะคืน null/false ให้ใช้โค้ด Kotlin เดิมแทน (แอปไม่พังเพราะเนทีฟ)
 */
object Native {
    @Volatile var ok = false; private set
    init { ok = try { System.loadLibrary("mocalc"); true } catch (e: Throwable) { false } }

    @JvmStatic private external fun nGray(buf: ByteBuffer, limit: Int, rowStride: Int, pixelStride: Int, w: Int, h: Int, q: Int, out: ByteArray): Boolean
    @JvmStatic private external fun nDivided(px: IntArray, w: Int, h: Int, x0: Int, x1: Int, y0: Int, y1: Int): Int
    @JvmStatic private external fun nFit(px: IntArray, w: Int, h: Int, l: Int, t: Int, r: Int, b: Int, out5: IntArray): Int
    @JvmStatic private external fun nBgOf(px: IntArray, w: Int, h: Int, l: Int, t: Int, r: Int, b: Int): Int

    private inline fun <T> guard(f: () -> T?): T? = if (!ok) null else try { f() } catch (e: Throwable) { ok = false; null }

    /** true = เขียนผลลงใน out แล้ว */
    fun gray(buf: ByteBuffer, limit: Int, rowStride: Int, pixelStride: Int, w: Int, h: Int, q: Int, out: ByteArray): Boolean =
        guard { nGray(buf, limit, rowStride, pixelStride, w, h, q, out) } == true

    /** null = ใช้เนทีฟไม่ได้ */
    fun divided(px: IntArray, w: Int, h: Int, x0: Int, x1: Int, y0: Int, y1: Int): Boolean? =
        guard { nDivided(px, w, h, x0, x1, y0, y1) }?.let { if (it < 0) null else it == 1 }

    /** out5 = {l,t,r,b,argb}; คืน true=พบ false=ไม่พบ null=ใช้เนทีฟไม่ได้ */
    fun fit(px: IntArray, w: Int, h: Int, l: Int, t: Int, r: Int, b: Int, out5: IntArray): Boolean? =
        guard { nFit(px, w, h, l, t, r, b, out5) }?.let { if (it < 0) null else it == 1 }

    /** null = ใช้เนทีฟไม่ได้ */
    fun bgOf(px: IntArray, w: Int, h: Int, l: Int, t: Int, r: Int, b: Int): Int? =
        guard { nBgOf(px, w, h, l, t, r, b) }?.takeIf { it != 0 }
}
