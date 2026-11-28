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

    // คำที่ห้ามแตะ/ใช้เติมตัวอักษรที่ถูกบัง = ศัพท์เฉพาะของผู้ใช้ (glossary) + คำที่เคยอ่านชัดเจนแล้วในเซสชันนี้ (ชื่อเมือง/ตัวละครที่โผล่ซ้ำ)
    private val session = LinkedHashSet<String>()
    @Volatile private var sVer = 0
    @Volatile private var pSv = -1

    /** จำคำยาว >=6 ตัวอักษรจากข้อความที่ OCR มั่นใจสูง ไว้เติมตอนที่ภายหลังคำเดียวกันถูกบังบางส่วน (เก็บล่าสุด 400 คำ) */
    fun learn(text: String) {
        synchronized(session) {
            for (m in LEARN_RE.findAll(text)) {
                if (session.add(m.value.lowercase())) {
                    sVer++
                    if (session.size > 400) session.remove(session.first())
                }
            }
        }
    }

    private fun protect(): String {
        val g = Prefs.glossary
        if (g != pKey || sVer != pSv) {
            val user = g.lines().mapNotNull { l -> l.substringBefore('=', "").trim().takeIf { it.isNotEmpty() } }
            val learned = synchronized(session) { pSv = sVer; session.toList() }
            pVal = (user + learned).joinToString("\n")
            pKey = g
        }
        return pVal
    }

    fun fix(s: String): String = if (s.isEmpty()) s else (Native.guessFix(s, protect()) ?: s)

    /** -1f = ไม่ทราบ */
    fun plausibility(s: String): Float = Native.guessPlausible(s, protect())

    /** บรรทัดที่ OCR มั่นใจต่ำ "กู้" ได้ไหม: ต้องมีตัวอักษรละติน >=3 และสะกดถูกตามพจนานุกรม >=75% (ใช้เนทีฟไม่ได้/ไม่แน่ใจ = ไม่กู้ เหมือนพฤติกรรมเดิม) */
    fun rescuable(line: String): Boolean =
        line.count { it in 'a'..'z' || it in 'A'..'Z' } >= 3 && Native.guessSpell(line, protect()) >= RESCUE_SPELL

    const val RESCUE_SPELL = 0.75f
    private val LEARN_RE = Regex("[A-Za-z]{6,24}")
}
