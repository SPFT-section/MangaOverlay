package com.sunmind.mangaoverlay

import android.content.Context

/** แก้คำที่ OCR อ่านผิดก่อนส่งไปแปล (ตัวเดาคำเป็น C++ ใน libmocalc) โหลดไม่สำเร็จ = คืนข้อความเดิม */
object Guess {
    @Volatile private var started = false
    @Volatile private var pKey: String? = null
    @Volatile private var pVal = ""

    /** โหลดพจนานุกรมความถี่ (assets/freq_en.txt) เบื้องหลัง เรียกซ้ำได้ */
    @Synchronized fun init(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        Thread({
            try {
                val freq = app.assets.open("freq_en.txt").use { it.readBytes() }
                // คำเพิ่มจากพจนานุกรม EN-TH ที่มีในแอปอยู่แล้ว (ใช้เทียบว่า "เป็นคำจริง" ไม่ใช้เป็นตัวเลือกแก้คำ)
                val known = try { knownWords(app) } catch (e: Throwable) { null }
                Native.guessLoad(freq, known)
            } catch (e: Throwable) { /* ใช้ข้อความเดิม */ }
        }, "guess-load").apply { isDaemon = true; start() }
    }

    /** คำเดี่ยวตัวอักษรล้วน 4-24 ตัว จาก dict_en_th.tsv (คอลัมน์แรก) คั่นด้วย \n */
    private fun knownWords(ctx: Context): ByteArray {
        val out = java.io.ByteArrayOutputStream(600_000)
        ctx.assets.open("dict_en_th.tsv").bufferedReader().useLines { ls ->
            for (l in ls) {
                val i = l.indexOf('\t'); if (i !in 4..24) continue
                var ok = true
                for (k in 0 until i) { val c = l[k]; if (!(c in 'a'..'z' || c in 'A'..'Z')) { ok = false; break } }
                if (ok) { out.write(l.substring(0, i).lowercase().toByteArray()); out.write('\n'.code) }
            }
        }
        return out.toByteArray()
    }

    // คำในศัพท์เฉพาะของผู้ใช้ (ชื่อตัวละคร ฯลฯ) ห้ามถูกแก้
    private fun protect(): String {
        val g = Prefs.glossary
        if (g != pKey) {
            pVal = g.lines().mapNotNull { l -> l.substringBefore('=', "").trim().takeIf { it.isNotEmpty() } }.joinToString("\n")
            pKey = g
        }
        return pVal
    }

    fun fix(s: String): String = if (s.isEmpty()) s else (Native.guessFix(s, protect()) ?: s)

    /** -1f = ไม่ทราบ */
    fun plausibility(s: String): Float = Native.guessPlausible(s, protect())
}
