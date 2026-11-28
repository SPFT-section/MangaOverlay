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

    @JvmStatic private external fun nGuessLoad(data: ByteArray, known: ByteArray?): Int
    @JvmStatic private external fun nGuessFix(text: String, protect: String): String?
    @JvmStatic private external fun nGuessPlausible(text: String, protect: String): Float

    @Volatile var guessReady = false; private set

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

    // ---- แก้คำที่ OCR อ่านผิด: ล้มเหลวเมื่อไรก็ปิดเฉพาะส่วนนี้ (ข้อความเดิมผ่านไปเฉย ๆ) ไม่กระทบฟังก์ชันพิกเซล ----
    private inline fun <T> guardG(f: () -> T?): T? = if (!ok || !guessReady) null else try { f() } catch (e: Throwable) { guessReady = false; null }

    /** data = "word log10count" ต่อบรรทัด, known = รายการคำเพิ่ม (คำละบรรทัด) หรือ null */
    fun guessLoad(data: ByteArray, known: ByteArray? = null): Boolean {
        if (!ok) return false
        val n = try { nGuessLoad(data, known) } catch (e: Throwable) { 0 }
        guessReady = n > 0
        return guessReady
    }

    /** null = ไม่เปลี่ยน */
    fun guessFix(text: String, protect: String): String? = guardG { nGuessFix(text, protect) }

    /** 0..1 สัดส่วนคำที่เป็นคำอังกฤษรู้จัก, -1 = ไม่ทราบ/ข้อมูลน้อย */
    fun guessPlausible(text: String, protect: String): Float = guardG { nGuessPlausible(text, protect) } ?: -1f
}
