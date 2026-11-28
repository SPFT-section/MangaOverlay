package com.sunmind.mangaoverlay

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * ตัวแยกตัวอักษรออกจากพื้นของเราเอง (Kotlin ล้วน ไม่พึ่ง Android/ไลบรารี) — ฟอนต์แคบ/ตัวสีบนพื้นไล่สี/เรืองแสง ที่ OCR อ่านเพี้ยน
 * คืนภาพขาว-ดำ "ตัวอักษรดำบนพื้นขาว" ขยายแล้ว หลายแบบให้ OCR ลองอ่านแล้วเลือกผลที่ดีที่สุด
 */
object Bin {
    class Out(val px: IntArray, val w: Int, val h: Int, val name: String)

    fun lum(px: IntArray) = IntArray(px.size) { val c = px[it]; (77 * ((c shr 16) and 255) + 150 * ((c shr 8) and 255) + 29 * (c and 255)) shr 8 }

    fun otsu(v: IntArray): Int {
        val hist = IntArray(256); for (x in v) hist[x.coerceIn(0, 255)]++
        val n = v.size.toDouble(); var sum = 0.0; for (i in 0..255) sum += i * hist[i]
        var wB = 0.0; var sB = 0.0; var best = 0.0; var th = 127
        for (t in 0..255) {
            wB += hist[t]; if (wB == 0.0) continue
            val wF = n - wB; if (wF == 0.0) break
            sB += t * hist[t]
            val d = sB / wB - (sum - sB) / wF
            val b = wB * wF * d * d
            if (b > best) { best = b; th = t }
        }
        return th
    }

    /** ขยายด้วย bilinear (ช่องเดียว) */
    fun scale(src: IntArray, w: Int, h: Int, s: Int): IntArray {
        if (s == 1) return src
        val nw = w * s; val nh = h * s; val out = IntArray(nw * nh)
        for (y in 0 until nh) {
            val fy = ((y + 0.5f) / s - 0.5f).coerceIn(0f, h - 1f); val y0 = fy.toInt(); val y1 = min(h - 1, y0 + 1); val ay = fy - y0
            for (x in 0 until nw) {
                val fx = ((x + 0.5f) / s - 0.5f).coerceIn(0f, w - 1f); val x0 = fx.toInt(); val x1 = min(w - 1, x0 + 1); val ax = fx - x0
                val top = src[y0 * w + x0] * (1 - ax) + src[y0 * w + x1] * ax
                val bot = src[y1 * w + x0] * (1 - ax) + src[y1 * w + x1] * ax
                out[y * nw + x] = (top * (1 - ay) + bot * ay + 0.5f).toInt()
            }
        }
        return out
    }

    /** สีพื้นหลักของภาพ = ถังสี (5 บิต/ช่อง) ที่มีพิกเซลมากสุด เฉลี่ยสีในถังนั้น */
    fun dominant(px: IntArray): Int {
        val cnt = IntArray(32768); val sr = LongArray(32768); val sg = LongArray(32768); val sb = LongArray(32768)
        for (c in px) {
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            val k = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            cnt[k]++; sr[k] += r.toLong(); sg[k] += g.toLong(); sb[k] += b.toLong()
        }
        var bk = 0; for (k in 1 until 32768) if (cnt[k] > cnt[bk]) bk = k
        val n = max(1, cnt[bk])
        return ((sr[bk] / n).toInt() shl 16) or ((sg[bk] / n).toInt() shl 8) or (sb[bk] / n).toInt()
    }

    /** Sauvola threshold (ปรับตามพื้นที่) ด้วย integral image: คืน threshold ต่อพิกเซล */
    private fun sauvola(v: IntArray, w: Int, h: Int, win: Int, k: Double): IntArray {
        val iw = w + 1
        val s1 = LongArray(iw * (h + 1)); val s2 = LongArray(iw * (h + 1))
        for (y in 0 until h) {
            var r1 = 0L; var r2 = 0L
            for (x in 0 until w) {
                val p = v[y * w + x].toLong(); r1 += p; r2 += p * p
                s1[(y + 1) * iw + x + 1] = s1[y * iw + x + 1] + r1
                s2[(y + 1) * iw + x + 1] = s2[y * iw + x + 1] + r2
            }
        }
        val half = win / 2; val th = IntArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - half); val y1 = min(h, y + half + 1)
            for (x in 0 until w) {
                val x0 = max(0, x - half); val x1 = min(w, x + half + 1)
                val n = ((x1 - x0) * (y1 - y0)).toDouble()
                val a = (s1[y1 * iw + x1] - s1[y0 * iw + x1] - s1[y1 * iw + x0] + s1[y0 * iw + x0]) / n
                val b = (s2[y1 * iw + x1] - s2[y0 * iw + x1] - s2[y1 * iw + x0] + s2[y0 * iw + x0]) / n
                val sd = sqrt(max(0.0, b - a * a))
                th[y * w + x] = (a * (1 + k * (sd / 128.0 - 1))).toInt()
            }
        }
        return th
    }

    private fun render(ink: BooleanArray, w: Int, h: Int, name: String, pad: Int): Out {
        val nw = w + 2 * pad; val nh = h + 2 * pad
        val out = IntArray(nw * nh) { -1 }   // ขาว (ARGB 0xFFFFFFFF)
        for (y in 0 until h) for (x in 0 until w) if (ink[y * w + x]) out[(y + pad) * nw + x + pad] = 0xFF000000.toInt()
        return Out(out, nw, nh, name)
    }

    /** ตัวอักษรต้องเป็นส่วนน้อยของภาพ (เกิน 60% = ขั้วผิด หรือพื้นกลายเป็นตัวอักษร) */
    private fun sane(ink: BooleanArray): Boolean { val c = ink.count { it }; return c > ink.size * 0.01 && c < ink.size * 0.6 }

    /**
     * px = ARGB ของบล็อกข้อความ (ตัดมาแล้ว) ; คืนรายการภาพแบบต่าง ๆ เรียงตามโอกาสที่ดี
     * - dist: ระยะสีจากสีพื้นหลัก (ตัวอักษรสีอะไรก็ได้บนพื้นไล่สี/เรืองแสง) ← แก้ป้ายตัวขาวบนพื้นฟ้า
     * - otsu: ตัดขาวดำด้วยความสว่างทั้งภาพ ; sauv: ตัดแบบปรับตามพื้นที่ (พื้นไล่สี/เงาตก)
     */
    fun variants(px: IntArray, w: Int, h: Int, upscale: Int = 3): List<Out> {
        val s = upscale.coerceAtLeast(1)
        val l = lum(px)
        val bg = dominant(px)
        val br = (bg shr 16) and 255; val bgG = (bg shr 8) and 255; val bb = bg and 255
        val dist = IntArray(px.size) {
            val c = px[it]; val dr = ((c shr 16) and 255) - br; val dg = ((c shr 8) and 255) - bgG; val db = (c and 255) - bb
            min(255, (sqrt((dr * dr + dg * dg + db * db).toDouble()) * 255.0 / 441.0 * 1.8).toInt())
        }
        val L = scale(l, w, h, s); val D = scale(dist, w, h, s); val nw = w * s; val nh = h * s
        val pad = 12 * s / 2 + 6
        val res = ArrayList<Out>()
        fun add(name: String, ink: BooleanArray) { if (sane(ink)) res.add(render(ink, nw, nh, name, pad)) }

        val td = otsu(D); add("dist", BooleanArray(D.size) { D[it] > td })
        val tl = otsu(L)
        add("otsuDark", BooleanArray(L.size) { L[it] < tl })
        add("otsuLight", BooleanArray(L.size) { L[it] > tl })
        val win = (min(nw, nh) / 2).coerceIn(21, 121) or 1
        val th = sauvola(L, nw, nh, win, 0.25)
        add("sauvDark", BooleanArray(L.size) { L[it] < th[it] - 8 })
        // ขั้วกลับของ sauvola: กลับค่าความสว่างก่อนคำนวณ
        val Li = IntArray(L.size) { 255 - L[it] }
        val thi = sauvola(Li, nw, nh, win, 0.25)
        add("sauvLight", BooleanArray(L.size) { Li[it] < thi[it] - 8 })
        return res
    }
}
