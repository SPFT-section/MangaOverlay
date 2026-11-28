package com.sunmind.mangaoverlay

import kotlin.math.abs
import kotlin.math.min

/**
 * ตัวแก้คำที่ OCR อ่านผิดของเราเอง (Kotlin ล้วน): ระยะแก้ไขถ่วงน้ำหนักตามตัวอักษรที่ OCR สับสนบ่อย (l/I/1, 0/O, rn/m ...)
 * แก้เฉพาะคำที่ไม่อยู่ในพจนานุกรม และเฉพาะเมื่อมีคำตอบที่ใกล้ที่สุดเพียงคำเดียว (ไม่เดา) ชื่อเฉพาะที่ใกล้คำจริงมากจึงไม่ถูกเปลี่ยนมั่ว
 */
object Spell {
    private val confuse: Set<String> = listOf("li", "l1", "i1", "0o", "5s", "8b", "6g", "2z", "uv", "ce", "ec", "nh", "hb", "ao", "eo", "ij")
        .flatMap { listOf(it, it.reversed()) }.toHashSet()
    private val wordRe = Regex("[A-Za-z0-9]*[A-Za-z][A-Za-z0-9]*")

    /** ระยะแก้ไข a->b (ตัวพิมพ์เล็กทั้งคู่) คืน > max เมื่อเกินงบ */
    fun cost(a: String, b: String, max: Float): Float {
        val n = a.length; val m = b.length
        if (abs(n - m) > max) return max + 1
        var p2 = FloatArray(m + 1); var p1 = FloatArray(m + 1) { it.toFloat() }; var cur = FloatArray(m + 1)
        for (i in 1..n) {
            cur[0] = i.toFloat(); var rowMin = cur[0]
            for (j in 1..m) {
                val ca = a[i - 1]; val cb = b[j - 1]
                val sub = if (ca == cb) 0f else if ("$ca$cb" in confuse) 0.4f else 1f
                var v = min(p1[j - 1] + sub, min(p1[j] + 1f, cur[j - 1] + 1f))
                // rn <-> m
                if (i >= 2 && ca == 'n' && a[i - 2] == 'r' && cb == 'm' && j >= 1) v = min(v, p2[j - 1] + 0.5f)
                if (j >= 2 && cb == 'n' && b[j - 2] == 'r' && ca == 'm') v = min(v, p1[j - 2] + 0.5f)
                cur[j] = v; if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return max + 1
            val t = p2; p2 = p1; p1 = cur; cur = t
        }
        return p1[m]
    }

    /** คำใกล้สุดที่ไม่กำกวม (ต้องมีคำเดียวที่ต้นทุนต่ำสุด หรือมีคำเดียวที่อยู่ใน prefer) ไม่เจอ = null */
    fun best(word: String, vocab: List<String>, prefer: Set<String>, max: Float = 1.0f): String? {
        val w = word.lowercase(); var bc = max + 1; var bw: String? = null; var tie = false; val ties = ArrayList<String>()
        for (c in vocab) {
            if (abs(c.length - w.length) > 1) continue
            val d = cost(w, c, min(max, bc))
            if (d > max || d > bc) continue
            if (d < bc) { bc = d; bw = c; tie = false; ties.clear(); ties.add(c) } else { tie = true; ties.add(c) }
        }
        if (bw == null) return null
        if (!tie) return bw
        val pf = ties.filter { it in prefer }
        return if (pf.size == 1) pf[0] else null
    }

    private fun recase(orig: String, w: String) = when {
        orig.all { it.isUpperCase() } -> w.uppercase()
        orig[0].isUpperCase() -> w.replaceFirstChar { it.uppercaseChar() }
        else -> w
    }

    fun fix(text: String, known: (String) -> Boolean, vocab: List<String>, prefer: Set<String>): String =
        wordRe.replace(text) { m ->
            val w = m.value
            val start = m.range.first == 0
            if (w.length < 4 || known(w) || w.all { it.isDigit() } || (!start && w[0].isUpperCase() && w.any { it.isLowerCase() })) w
            else best(w, vocab, prefer)?.let { recase(w, it) } ?: w
        }
}
