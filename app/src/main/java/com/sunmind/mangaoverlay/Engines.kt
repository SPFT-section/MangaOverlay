package com.sunmind.mangaoverlay

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.LruCache
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.sync.withLock
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import android.content.Context
import java.io.File
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** patch = ภาพกรอบคำพูดที่ MI-GAN ลบตัวอักษรเดิมแล้ว (ขนาดเท่ากล่องที่วาง) ; null = ใช้สีพื้นทึบ bg แทน */
class Item(val r: Rect, val text: String, val bg: Int, val bubble: Boolean = false, val patch: Bitmap? = null)

/** items = กรอบที่พร้อมวาง, tr = คำแปล (เริ่มแปลไปพร้อมกับการหากรอบ จึงมักเสร็จก่อน/พร้อมกัน) */
class Found(val items: List<Item>, val tr: Deferred<Map<String, String?>>, val better: Deferred<Map<String, String>>? = null,
            /** y ขอบบนของบล็อกที่ขอบล่างภาพตัดอยู่ (-1 = ไม่มี) ; cutT = y ขอบล่างของบล็อกที่ขอบบนภาพตัดอยู่ — พิกัดของภาพที่ OCR */
            val cutB: Int = -1, val cutT: Int = -1)

/** พิกเซลทั้งภาพอ่านครั้งเดียว (เดิมเรียก getPixel ทีละจุดหลายหมื่นครั้ง) */
private class Img(val px: IntArray, val w: Int, val h: Int) {
    fun p(x: Int, y: Int) = px[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
    fun lum(x: Int, y: Int): Int { val c = p(x, y); return (77 * Color.red(c) + 150 * Color.green(c) + 29 * Color.blue(c)) shr 8 }
}

object Ocr {
    private val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private const val ST = 2      // ลดความละเอียดตอนหากรอบคำพูด
    private const val TOL = 28    // ความต่างความสว่างที่ยังนับเป็นพื้นกรอบเดียวกัน
    private val junkRe = Regex("(?i)(https?:|www\\.|\\w\\.(com|net|org)\\b|@\\w)")
    private val noLetterRe = Regex("^[\\d\\s\\p{Punct}]+$")
    private val wordRe = Regex("[A-Za-z]+(?:'[A-Za-z]+)*")
    private val stop: Set<String> = ("a an the i me my mine we us our you your he him his she her it its they them their this that these those " +
        "am is are was were be been being do does did have has had will would shall should can could may might must " +
        "and or but if so as of at by for in on to up out off over into from with about than then there here when where who whom whose which what why how " +
        "not no yes oh ah uh um hey hmm well just very too also only even still already again ever never always all any some each every both more most much many " +
        "such own same other another few little lot lots now let get got go going gonna wanna gotta kinda dunno gotcha lemme gimme ya yo huh heh haha ahh ohh ugh argh " +
        "okay ok yeah yep nope please thanks thank sorry dont doesnt didnt isnt arent wasnt werent wont cant couldnt shouldnt wouldnt").split(' ').toHashSet()

    private class Blk(val r: Rect, val text: String, val lh: Int, val conf: Float = 1f, val lines: List<Rect> = emptyList())
    private class Fit(val r: Rect, val bg: Int)

    suspend fun run(bmp: Bitmap, scope: CoroutineScope, minTop: Int = 0): Found {
        val none = Found(emptyList(), CompletableDeferred<Map<String, String?>>(emptyMap()))
        // แบ่งหน้าที่: ML Kit หาบรรทัดข้อความ  ||  YOLOv8n-bubble หากรอบคำพูด  (เริ่มพร้อมกัน)
        val detJob = scope.async(Dispatchers.Default) { if (Prefs.useDet && BubbleDet.ready()) BubbleDet.detect(bmp) else emptyList() }
        val t = try { rec.process(InputImage.fromBitmap(bmp, 0)).await() }
                catch (e: CancellationException) { throw e }   // ห้ามกลืน cancellation
                catch (e: Exception) { detJob.cancelAndJoin(); return none }
        return withContext(Dispatchers.Default) {
            try {
                val w = bmp.width; val h = bmp.height
                val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
                val img = Img(px, w, h)
                val cut = intArrayOf(Int.MAX_VALUE, -1)
                val bubbles = detJob.await().map { it.r }
                val bl = refine(bmp, blocks(img, t, minTop, cut, bubbles).take(40))
                val texts = bl.map { normalize(it.text) }
                // เริ่มแปลทันที ขนานไปกับการหากรอบคำพูด: เครื่องแปลเร็ว (ได้ก่อน) + AI (ถ้าตั้งไว้ ได้ทีหลังแต่ดีกว่า)
                val d = texts.distinct()
                val tr = scope.async(Dispatchers.IO) { d.zip(Translator.translate(d)).toMap() }
                val better = if (Llm.enabled() && d.isNotEmpty()) scope.async(Dispatchers.IO) { Translator.refine(texts) } else null
                // แปลกำลังวิ่งอยู่ ; ตรงนี้ลบข้อความเดิมด้วย MI-GAN ขนานกันไปทีละกรอบ (จำกัดต่อหน้า กันช้า)
                val inpLeft = java.util.concurrent.atomic.AtomicInteger(12)
                val items = bl.mapIndexed { i, b -> async { toItem(img, b, texts[i], bubbles, inpLeft) } }.awaitAll()
                Found(items, tr, better, if (cut[0] == Int.MAX_VALUE) -1 else cut[0], cut[1])
            } catch (e: CancellationException) { throw e } catch (e: Exception) { none }
        }
    }

    private fun blocks(img: Img, t: Text, minTop: Int, cut: IntArray, bubbles: List<Rect>): List<Blk> {
        val raw = ArrayList<Blk>()
        for (b in t.textBlocks) {
            val bb = b.boundingBox ?: continue
            if (bb.top < minTop) continue
            // ข้อความที่ถูกขอบภาพตัด = ได้ครึ่งเดียว แปลแล้วผิด รอให้เลื่อนเข้ามาเต็มกรอบแล้วค่อยแปล
            val cb = bb.bottom >= img.h - 2; val ct = bb.top <= 1
            if (cb || ct || bb.left <= 1 || bb.right >= img.w - 1) {
                // จดไว้ให้รอบ OCR ถัดไป (strip) ย้อนขึ้นมาครอบบล็อกนี้ทั้งก้อน ไม่งั้นจะโดนตัดซ้ำที่ขอบแถบแล้วหายไปเลย
                if (bb.left > 1 && bb.right < img.w - 1) {
                    if (cb) cut[0] = min(cut[0], bb.top)
                    if (ct) cut[1] = max(cut[1], bb.bottom)
                }
                continue
            }
            if (b.lines.isNotEmpty() && b.lines.map { it.confidence }.average() < 0.35) continue
            val s = clean(b.text)
            if (junkRe.containsMatchIn(s)) continue // ลายน้ำ/URL
            val hs = b.lines.mapNotNull { it.boundingBox?.height() }.sorted()
            raw.add(Blk(Rect(bb), s, if (hs.isEmpty()) bb.height() else hs[hs.size / 2], if (b.lines.isEmpty()) 1f else b.lines.map { it.confidence }.average().toFloat(),
                b.lines.mapNotNull { it.boundingBox }.map { Rect(it) }))
        }
        val merged = if (Prefs.merge) merge(img, raw, bubbles) else raw
        return merged.sortedWith(compareBy({ it.r.top }, { it.r.left })).filter { b ->
            val core = b.text.filter { it.isLetter() }   // "I?" "A!" "I..." ต้องไม่ถูกทิ้ง (เดิมเทียบทั้งข้อความกับ "I"/"A" เป๊ะ ๆ)
            !(core.length < 2 && core != "I" && core != "A") && !noLetterRe.matches(b.text)
        }
    }

    /** สัดส่วนคำที่เป็นคำอังกฤษจริง (พจนานุกรม/stopword) ใช้ตรวจว่า OCR อ่านเป็นขยะไหม */
    private fun score(s: String): Float {
        val ws = wordRe.findAll(s).map { it.value }.toList()
        if (ws.isEmpty()) return 0f
        if (!Dict.ready()) return 0.5f
        return ws.count { w -> w.length <= 2 || w.lowercase() in stop || Dict.lookup(w) != null }.toFloat() / ws.size
    }

    /** ความคล้ายกันของสองข้อความ 0..1 (ใช้โหวตระหว่างผลอ่านหลายแบบ) */
    private fun sim(a: String, b: String): Float {
        val x = a.lowercase().filter { it.isLetterOrDigit() }; val y = b.lowercase().filter { it.isLetterOrDigit() }
        val m = max(x.length, y.length); if (m == 0) return 1f
        return 1f - min(1f, Spell.cost(x, y, m.toFloat()) / m)
    }

    private class Cand(val text: String, val conf: Float, val model: Boolean = false)

    private fun crop(src: Bitmap, r: Rect): Triple<IntArray, Int, Int>? {
        val x0 = max(0, r.left - 6); val y0 = max(0, r.top - 6); val x1 = min(src.width, r.right + 6); val y1 = min(src.height, r.bottom + 6)
        val cw = x1 - x0; val ch = y1 - y0
        if (cw < 8 || ch < 8) return null
        val px = IntArray(cw * ch); src.getPixels(px, 0, cw, x0, y0, cw, ch)
        return Triple(px, cw, ch)
    }

    /**
     * OCR ซ้ำด้วยภาพที่เราแยกตัวอักษรออกจากพื้นเอง (Bin.variants) หลายแบบ แล้วโหวต:
     * คะแนน = สัดส่วนคำจริง + มั่นใจของ OCR + ความสอดคล้องกับผลแบบอื่น ; จากนั้นแก้คำเพี้ยนด้วย Spell
     */
    private suspend fun refine(bmp: Bitmap, bl: List<Blk>): List<Blk> {
        // PP-OCR อ่านทุกบล็อกพร้อมกัน (เบา) : ML Kit = ตัวหาบรรทัด, PP-OCR = ตัวอ่าน แล้วโหวตกัน ;
        // ส่วนอ่านซ้ำด้วย ML Kit หลายแบบ (หนัก) ยังจำกัด 5 บล็อก/หน้า และทำเฉพาะเมื่อ PP-OCR ก็ยังไม่มั่นใจ
        val pp: List<Cand?> = if (Prefs.useModel && PpRec.ready())
            coroutineScope { bl.map { b -> async(Dispatchers.Default) { ppRead(bmp, b) } }.awaitAll() } else bl.map { null }
        var budget = 5
        val out = ArrayList<Blk>(bl.size)
        for ((i, b) in bl.withIndex()) {
            val s0 = score(b.text); val p = pp[i]
            val agree = p == null || sim(p.text, b.text) >= 0.85f
            if (agree && b.conf >= 0.85f && s0 >= 0.6f) { out.add(b); continue }
            if (p == null && budget <= 0) { out.add(b); continue }
            val cands = arrayListOf(Cand(b.text, b.conf))
            if (p != null) cands.add(p)
            val modelGood = p != null && p.conf >= 0.85f && score(p.text) >= 0.8f
            if (!modelGood && budget > 0) {
                budget--
                val cr = crop(bmp, b.r)
                if (cr != null) {
                    val sc = (96f / max(cr.third, 1)).toInt().coerceIn(2, 4)   // ให้ตัวอักษรสูงราว 100px+ ซึ่งเป็นช่วงที่ OCR อ่านดีที่สุด
                    for (v in Bin.variants(cr.first, cr.second, cr.third, sc)) {
                        val pb = try { Bitmap.createBitmap(v.px, v.w, v.h, Bitmap.Config.ARGB_8888) } catch (e: Exception) { continue }
                        val t2 = try { rec.process(InputImage.fromBitmap(pb, 0)).await() }
                                 catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                        pb.recycle()
                        if (t2 == null) continue
                        val ls = t2.textBlocks.flatMap { it.lines }
                        if (ls.isEmpty()) continue
                        val s = clean(t2.textBlocks.joinToString(" ") { it.text })
                        if (s.isBlank() || junkRe.containsMatchIn(s)) continue
                        cands.add(Cand(s, ls.map { it.confidence }.average().toFloat()))
                        if (cands.size >= 3 && score(s) >= 0.8f && cands.last().conf >= 0.8f) break
                    }
                }
            }
            val best = cands.maxByOrNull { c ->
                val agreeC = if (cands.size > 1) cands.filter { it !== c }.map { sim(c.text, it.text) }.average().toFloat() else 0f
                score(c.text) + 0.5f * c.conf + 0.5f * agreeC + (if (c.model && c.conf >= 0.8f) 0.2f else 0f)
            }!!
            out.add(Blk(b.r, spellFix(best.text), b.lh, best.conf, b.lines))
        }
        // ยังเป็นขยะหลังอ่านซ้ำ (หลายคำ ไม่ใช่คำจริงเป็นส่วนใหญ่ มั่นใจต่ำ) = ไม่แปล ดีกว่าแสดงคำแปลมั่ว
        return out.filter { b -> !(Dict.ready() && wordRe.findAll(b.text).count() >= 3 && score(b.text) < 0.4f && b.conf < 0.75f) }
    }

    /** โมเดล PP-OCR อ่านทีละบรรทัด (ใช้กรอบบรรทัดจาก ML Kit) : แม่นกับฟอนต์แคบ/เอฟเฟกต์ที่ ML Kit อ่านเป็นขยะ */
    private fun ppRead(bmp: Bitmap, b: Blk): Cand? {
        if (b.lines.isEmpty()) return null
        val rs = b.lines.sortedBy { it.top }.map { PpRec.recognize(bmp, it) }
        if (!rs.all { it != null }) return null
        val s = clean(rs.joinToString(" ") { it!!.first })
        if (s.isBlank() || junkRe.containsMatchIn(s)) return null
        return Cand(s, rs.map { it!!.second }.average().toFloat(), true)
    }

    private fun spellFix(s: String): String {
        if (!Dict.ready()) return s
        return Spell.fix(s, { w -> w.length <= 2 || w.lowercase() in stop || Dict.lookup(w) != null }, Dict.vocab(), stop)
    }

    private suspend fun toItem(img: Img, b: Blk, text: String, bubbles: List<Rect>, inpLeft: java.util.concurrent.atomic.AtomicInteger): Item {
        val bi = bubbleOf(b.r, bubbles)
        // YOLO เจอกรอบ = ใช้ขอบเขตจากโมเดล (ทนกว่า flood-fill เวลากรอบไล่สี/ขอบรั่ว) ; ไม่เจอ = flood-fill แบบเดิม
        val f = if (!Prefs.fit) null else if (bi >= 0) fitBubble(img, b.r, bubbles[bi]) else fit(img, b.r)
        val rect = f?.r ?: Rect(b.r)
        val pd = if (f != null) 0 else 6   // ต้องตรงกับ pd ใน OverlayService.addItems
        val patch = if (Prefs.useInpaint && Inpaint.ready())
            patchFor(img, b, Rect(rect.left - pd, rect.top - pd, rect.right + pd, rect.bottom + pd), inpLeft) else null
        return Item(rect, text, f?.bg ?: bgOf(img, b.r), f != null, patch)
    }

    /** MI-GAN: ลบบรรทัดข้อความเดิมในกล่อง pr (มาสก์ = กรอบบรรทัดขยายออกเล็กน้อย) ; null = ใช้สีพื้นแทน */
    private suspend fun patchFor(img: Img, b: Blk, pr: Rect, left: java.util.concurrent.atomic.AtomicInteger): Bitmap? {
        if (pr.left < 0 || pr.top < 0 || pr.right > img.w || pr.bottom > img.h) return null
        val cw = pr.width(); val ch = pr.height()
        if (cw < 24 || ch < 24 || left.getAndDecrement() <= 0) return null
        val px = IntArray(cw * ch)
        for (y in 0 until ch) System.arraycopy(img.px, (pr.top + y) * img.w + pr.left, px, y * cw, cw)
        val e = max(3, (b.lh * 0.25f).toInt())
        val holes = (if (b.lines.isEmpty()) listOf(b.r) else b.lines).mapNotNull { l ->
            val r = Rect(l.left - e - pr.left, l.top - e - pr.top, l.right + e - pr.left, l.bottom + e - pr.top)
            if (r.intersect(0, 0, cw, ch)) r else null
        }
        val o = Inpaint.erase(px, cw, ch, holes) ?: return null
        return Bitmap.createBitmap(o, cw, ch, Bitmap.Config.ARGB_8888)
    }

    /** กรอบคำพูดที่ YOLO ตรวจได้ที่ครอบจุดกึ่งกลางของ r (เล็กสุดถ้าซ้อนกัน) ; -1 = ไม่มี */
    private fun bubbleOf(r: Rect, bubbles: List<Rect>): Int {
        var best = -1; var ba = Long.MAX_VALUE
        for ((i, b) in bubbles.withIndex()) if (b.contains(r.centerX(), r.centerY())) {
            val a = b.width().toLong() * b.height(); if (a < ba) { ba = a; best = i }
        }
        return best
    }

    /** กล่องวางคำแปลจากขอบเขตกรอบของ YOLO : กรอบสี่เหลี่ยมใช้เกือบเต็ม วงรีใช้ส่วนใน (0.88) แล้วสมมาตรรอบข้อความเหมือน fit() */
    private fun fitBubble(img: Img, t: Rect, bb: Rect): Fit {
        val hw = (bb.width() / 2f * 0.88f).toInt(); val hh = (bb.height() / 2f * 0.88f).toInt()
        val r = Rect(bb.centerX() - hw, bb.centerY() - hh, bb.centerX() + hw, bb.centerY() + hh)
        r.union(Rect(t.left - 4, t.top - 4, t.right + 4, t.bottom + 4))
        if (!r.intersect(bb)) r.set(t)
        val ex = min(t.left - r.left, r.right - t.right).coerceAtLeast(0)
        val ey = min(t.top - r.top, r.bottom - t.bottom).coerceAtLeast(0)
        r.set(t.left - ex, t.top - ey, t.right + ex, t.bottom + ey)
        return Fit(r, bgOf(img, t))
    }

    /** แก้ที่ OCR อ่านผิดบ่อยในฟอนต์มังงะ */
    private fun clean(s0: String): String {
        var s = s0.replace("-\n", "").replace("\n", " ")
            .replace('\u2019', '\'').replace('\u2018', '\'').replace('\u201C', '"').replace('\u201D', '"')
            .replace('|', 'I')
        // 0 -> O: ติดตัวพิมพ์ใหญ่และไม่ใช่ส่วนของเลขหลายหลัก (N0, S0, G0, 0K) เดิมจับเฉพาะ 0 ที่มีตัวใหญ่ทั้งสองข้าง
        s = s.replace(Regex("(?<=[A-Z])0(?![0-9])|(?<![0-9A-Za-z])0(?=[A-Z])"), "O").replace(Regex("(?<=[A-Z])1(?=[A-Z])"), "I")
        s = s.replace(Regex("(?i)(?<=[a-z])\\s*'\\s*(?=(?:t|s|m|d|ll|re|ve)\\b)"), "'")
        s = s.replace(Regex("\\s+"), " ").trim()
        // ตัวพิมพ์ใหญ่ล้วนในมังงะ: "l" ตัวเล็กที่โผล่กลางคำใหญ่คือ "I" ที่ OCR อ่านผิด (lT'S, WlLL, lNSIDE)
        // นับเฉพาะตัวอักษรที่ไม่ใช่ "l" (เดิมนับ l เป็นตัวหาร: lT'S = 2/3 < 0.7 จึงไม่ถูกแก้) และต้องมีตัวอื่นอย่างน้อย 2 ตัว กัน "All" -> "AII"
        val rest = s.filter { it.isLetter() && it != 'l' }
        if ('l' in s && rest.length >= 2 && rest.count { it.isUpperCase() } >= rest.length * 0.8) s = s.replace('l', 'I')
        return s
    }

    // ---------- รวมบล็อกที่อยู่ในกรอบคำพูดเดียวกัน ----------
    private fun merge(img: Img, a: List<Blk>, bubbles: List<Rect>): List<Blk> {
        val n = a.size
        val p = IntArray(n) { it }
        fun f(x: Int): Int { var y = x; while (p[y] != y) { p[y] = p[p[y]]; y = p[y] }; return y }
        for (i in 0 until n) for (j in i + 1 until n) if (same(img, a[i], a[j], bubbles)) p[f(i)] = f(j)
        return (0 until n).groupBy { f(it) }.values.map { g ->
            val m = g.map { a[it] }.sortedBy { it.r.top }
            val r = Rect(m[0].r)
            m.forEach { r.union(it.r) }
            Blk(r, m.joinToString(" ") { it.text }, m.map { it.lh }.sorted()[m.size / 2], m.minOf { it.conf }, m.flatMap { it.lines }.sortedBy { it.top })
        }
    }

    private fun same(img: Img, a: Blk, b: Blk, bubbles: List<Rect>): Boolean {
        // YOLO ชี้ชัดทั้งคู่ = กรอบเดียวกันก็รวม คนละกรอบก็ไม่รวม (ไม่ต้องเดาจากระยะห่าง/เส้นขอบ)
        val ba = bubbleOf(a.r, bubbles); val bb = bubbleOf(b.r, bubbles)
        if (ba >= 0 && bb >= 0) return ba == bb
        val u = if (a.r.top <= b.r.top) a else b
        val d = if (u === a) b else a
        val lo = min(u.lh, d.lh).coerceAtLeast(1)
        if (max(u.lh, d.lh).toFloat() / lo > 1.5f) return false
        val ov = min(u.r.right, d.r.right) - max(u.r.left, d.r.left)
        if (ov < 0.3f * min(u.r.width(), d.r.width())) return false
        val gap = d.r.top - u.r.bottom
        if (gap > 1.1f * lo) return false
        if (gap <= 0) return true
        return !divided(img, max(u.r.left, d.r.left), min(u.r.right, d.r.right), u.r.bottom, d.r.top)
    }

    /** มีเส้นขอบกรอบคำพูดคั่นระหว่างสองบล็อกไหม (สแกนแนวตั้ง 3 เส้น ต้องเจอขอบอย่างน้อย 2 เส้น) */
    private fun divided(img: Img, x0: Int, x1: Int, y0: Int, y1: Int): Boolean {
        if (y1 - y0 < 3) return false
        var hits = 0
        for (k in 1..3) {
            val x = x0 + (x1 - x0) * k / 4
            val ref = img.lum(x, y0 + 1)
            var run = 0; var hit = false
            for (y in y0 + 1 until y1) {
                if (abs(img.lum(x, y) - ref) > 70) { run++; if (run >= 2) { hit = true; break } } else run = 0
            }
            if (hit) hits++
        }
        return hits >= 2
    }

    // ---------- หากรอบคำพูดรอบข้อความ ----------
    private fun fit(img: Img, t: Rect): Fit? {
        val m = (max(t.width(), t.height()) * 0.8f).toInt().coerceIn(48, 360)
        val cl = max(0, t.left - m); val ct = max(0, t.top - m)
        val cr = min(img.w, t.right + m); val cb = min(img.h, t.bottom + m)
        val w = cr - cl; val h = cb - ct
        val gw = w / ST; val gh = h / ST
        if (gw < 12 || gh < 12) return null
        val lum = IntArray(gw * gh)
        for (y in 0 until gh) {
            val row = (ct + y * ST) * img.w + cl
            for (x in 0 until gw) {
                val p = img.px[row + x * ST]
                lum[y * gw + x] = (77 * Color.red(p) + 150 * Color.green(p) + 29 * Color.blue(p)) shr 8
            }
        }
        val tl = (t.left - cl) / ST; val tt = (t.top - ct) / ST
        val tr = (t.right - cl) / ST; val tb = (t.bottom - ct) / ST
        val k = 3; val cx = (tl + tr) / 2; val cy = (tt + tb) / 2
        val seeds = listOf(
            Pair(cx, tt - k), Pair(cx, tb + k), Pair(tl - k, cy), Pair(tr + k, cy),
            Pair(tl - k, tt - k), Pair(tr + k, tt - k), Pair(tl - k, tb + k), Pair(tr + k, tb + k))
        val seen = BooleanArray(gw * gh)
        val q = IntArray(gw * gh)
        var best: IntArray? = null   // x0, y0, x1, y1, area
        var bestColor = Color.WHITE
        for ((sx, sy) in seeds) {
            if (sx < 0 || sy < 0 || sx >= gw || sy >= gh || seen[sy * gw + sx]) continue
            val ref = lum[sy * gw + sx]
            var qh = 0; var qt = 0
            q[qt++] = sy * gw + sx; seen[sy * gw + sx] = true
            var x0 = sx; var x1 = sx; var y0 = sy; var y1 = sy; var area = 0
            while (qh < qt) {
                val c = q[qh++]; val x = c % gw; val y = c / gw
                area++
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
                if (x > 0 && !seen[c - 1] && abs(lum[c - 1] - ref) <= TOL) { seen[c - 1] = true; q[qt++] = c - 1 }
                if (x < gw - 1 && !seen[c + 1] && abs(lum[c + 1] - ref) <= TOL) { seen[c + 1] = true; q[qt++] = c + 1 }
                if (y > 0 && !seen[c - gw] && abs(lum[c - gw] - ref) <= TOL) { seen[c - gw] = true; q[qt++] = c - gw }
                if (y < gh - 1 && !seen[c + gw] && abs(lum[c + gw] - ref) <= TOL) { seen[c + gw] = true; q[qt++] = c + gw }
            }
            if (best == null || area > best[4]) { best = intArrayOf(x0, y0, x1, y1, area); bestColor = img.px[(ct + sy * ST) * img.w + cl + sx * ST] }
        }
        val b = best ?: return null
        val sides = (if (b[0] <= 0) 1 else 0) + (if (b[1] <= 0) 1 else 0) + (if (b[2] >= gw - 1) 1 else 0) + (if (b[3] >= gh - 1) 1 else 0)
        if (sides >= 3 || b[4] > 0.92f * gw * gh) return null // รั่วออกนอกกรอบ = ไม่ใช่กรอบคำพูด
        val bw = b[2] - b[0] + 1; val bh = b[3] - b[1] + 1
        val bounds = Rect(cl + b[0] * ST, ct + b[1] * ST, cl + (b[2] + 1) * ST, ct + (b[3] + 1) * ST)
        if (bounds.width() < t.width() || bounds.height() < t.height()) return null
        // กรอบสี่เหลี่ยมใช้เกือบเต็ม, กรอบวงรีใช้ส่วนที่ปลอดภัยภายใน
        val k2 = if (b[4].toFloat() / (bw * bh) > 0.86f) 0.94f else 0.78f
        val hw = (bounds.width() / 2f * k2).toInt(); val hh = (bounds.height() / 2f * k2).toInt()
        val r = Rect(bounds.centerX() - hw, bounds.centerY() - hh, bounds.centerX() + hw, bounds.centerY() + hh)
        r.union(Rect(t.left - 4, t.top - 4, t.right + 4, t.bottom + 4))
        if (!r.intersect(bounds)) return null
        // กล่องต้องสมมาตรรอบข้อความ (เดิมเบี้ยวไปด้านที่ flood-fill ลามได้มากกว่า เช่นฟองไล่สี -> ข้อความแปลไม่ตรงต้นฉบับ)
        val ex = min(t.left - r.left, r.right - t.right).coerceAtLeast(0)
        val ey = min(t.top - r.top, r.bottom - t.bottom).coerceAtLeast(0)
        r.set(t.left - ex, t.top - ey, t.right + ex, t.bottom + ey)
        return Fit(r, Color.rgb(Color.red(bestColor), Color.green(bestColor), Color.blue(bestColor)))
    }

    /**
     * ตัวพิมพ์ใหญ่ล้วนในมังงะ -> sentence case ให้ Google แปลแม่นขึ้น
     * คำที่ไม่อยู่ในพจนานุกรมและไม่ใช่คำพื้นฐาน (น่าจะเป็นชื่อคน/สถานที่) จะขึ้นต้นด้วยตัวใหญ่ ไม่งั้นกลางประโยคจะกลายเป็นตัวเล็กแล้วถูกแปลเป็นคำทั่วไป
     */
    private fun normalize(s: String): String {
        val l = s.filter { it.isLetter() }
        if (l.length < 3 || l.count { it.isUpperCase() } < l.length * 0.8) return s
        val sb = StringBuilder(); var cap = true
        for (ch in s.lowercase()) {
            if (cap && ch.isLetter()) { sb.append(ch.uppercaseChar()); cap = false } else sb.append(ch)
            if (ch == '.' || ch == '!' || ch == '?') cap = true
        }
        var t = sb.toString().replace(Regex("\\bi\\b"), "I").replace(Regex("\\bi'"), "I'")
        if (Dict.ready()) t = wordRe.replace(t) { m ->
            val w = m.value; val base = w.substringBefore('\'')
            if (base.length < 3 || base.lowercase() in stop || w.lowercase() in stop || w[0].isUpperCase() || Dict.lookup(base) != null) w
            else w.replaceFirstChar { it.uppercaseChar() }
        }
        return t
    }

    /** สีพื้นกรอบคำพูด (สำรอง): มัธยฐานจากจุด 8 จุดรอบนอกข้อความ */
    private fun bgOf(img: Img, r: Rect): Int {
        val xs = intArrayOf(r.left - 5, (r.left + r.right) / 2, r.right + 5)
        val ys = intArrayOf(r.top - 5, (r.top + r.bottom) / 2, r.bottom + 5)
        val l = ArrayList<Int>()
        for (xi in 0..2) for (yi in 0..2) {
            if (xi == 1 && yi == 1) continue
            l.add(img.p(xs[xi], ys[yi]))
        }
        l.sortBy { 77 * Color.red(it) + 150 * Color.green(it) + 29 * Color.blue(it) }
        val p = l[l.size / 2]
        return Color.rgb(Color.red(p), Color.green(p), Color.blue(p))
    }
}

/** คำเรียกต่อท้าย/ศัพท์มังงะ + ศัพท์เฉพาะของผู้ใช้ (ชื่อตัวละคร) ใส่เป็นภาษาไทยก่อนส่งให้ Google */
object Glossary {
    private fun word(a: String) = Regex("(?i)(?<![A-Za-z])" + Regex.escape(a) + "(?![A-Za-z])")
    private val terms = listOf(
        "onii-chan" to "โอนี่จัง", "oniichan" to "โอนี่จัง", "onee-chan" to "โอเนะจัง", "oneechan" to "โอเนะจัง",
        "onii-san" to "โอนี่ซัง", "onee-san" to "โอเนะซัง", "senpai" to "เซมไป", "sensei" to "เซนเซ", "kouhai" to "โคไฮ"
    ).map { (a, b) -> word(a) to b }
    private val suffixes = listOf("san" to "ซัง", "kun" to "คุง", "chan" to "จัง", "sama" to "ซามะ", "dono" to "โดโนะ", "sensei" to "เซนเซ", "senpai" to "เซมไป")
        .map { (a, b) -> Regex("(?i)(?<=[A-Za-z])-" + a + "(?![A-Za-z])") to " $b" }
    // ต้องเป็นคำลงท้ายจริง (ตามด้วยช่องว่าง/วรรคตอน/จบข้อความ) ไม่งั้น "จังหวะ" ฯลฯ จะถูกต่อติดคำหน้า
    private val joinRe = Regex("\\s+(ซัง|คุง|จัง|ซามะ|โดโนะ|เซนเซ|เซมไป)(?=\\s|$|[!?…,.\"'”)])")

    @Volatile private var userKey: String? = null
    @Volatile private var userRules: List<Pair<Regex, String>> = emptyList()

    private fun user(): List<Pair<Regex, String>> {
        val g = Prefs.glossary
        if (g != userKey) {
            userRules = g.lines().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) return@mapNotNull null
                val k = line.substring(0, i).trim(); val v = line.substring(i + 1).trim()
                if (k.isEmpty() || v.isEmpty()) null else word(k) to v
            }.sortedByDescending { it.first.pattern.length } // วลียาวก่อน เช่น "Straw Hat Pirates" ก่อน "Straw Hat"
            userKey = g
        }
        return userRules
    }

    fun pre(s: String): String {
        var t = s
        for ((r, b) in terms) t = r.replace(t, Regex.escapeReplacement(b))
        for ((r, b) in suffixes) t = r.replace(t, Regex.escapeReplacement(b))
        for ((r, v) in user()) t = r.replace(t, Regex.escapeReplacement(v))
        return t
    }

    fun post(s: String): String {
        var t = s.replace(joinRe, "$1").replace(Regex("\\s+([!?…])"), "$1")
        if (t.endsWith(".") && !t.endsWith("..")) t = t.dropLast(1)
        return t
    }
}

/** พจนานุกรมอังกฤษ->ไทย (Thai WordNet + Princeton WordNet) ใช้ออฟไลน์ */
object Dict {
    private var map: HashMap<String, String>? = null
    private val irregular = mapOf(
        "was" to "be", "were" to "be", "am" to "be", "is" to "be", "are" to "be", "been" to "be",
        "had" to "have", "has" to "have", "did" to "do", "does" to "do", "done" to "do", "went" to "go", "gone" to "go",
        "said" to "say", "made" to "make", "took" to "take", "taken" to "take", "came" to "come", "saw" to "see", "seen" to "see",
        "knew" to "know", "known" to "know", "got" to "get", "gotten" to "get", "gave" to "give", "given" to "give",
        "found" to "find", "thought" to "think", "told" to "tell", "became" to "become", "left" to "leave", "felt" to "feel",
        "brought" to "bring", "began" to "begin", "begun" to "begin", "kept" to "keep", "held" to "hold", "wrote" to "write",
        "written" to "write", "stood" to "stand", "heard" to "hear", "meant" to "mean", "met" to "meet", "ran" to "run",
        "paid" to "pay", "sat" to "sit", "spoke" to "speak", "spoken" to "speak", "led" to "lead", "grew" to "grow",
        "grown" to "grow", "lost" to "lose", "fell" to "fall", "fallen" to "fall", "sent" to "send", "built" to "build",
        "understood" to "understand", "drew" to "draw", "drawn" to "draw", "broke" to "break", "broken" to "break",
        "spent" to "spend", "won" to "win", "chose" to "choose", "chosen" to "choose", "ate" to "eat", "eaten" to "eat",
        "men" to "man", "women" to "woman", "children" to "child", "people" to "person", "feet" to "foot", "teeth" to "tooth")

    @Synchronized fun load(ctx: Context) {
        if (map != null) return
        val m = HashMap<String, String>(150000)
        ctx.assets.open("dict_en_th.tsv").bufferedReader().useLines { ls ->
            ls.forEach { l -> val i = l.indexOf('\t'); if (i > 0) m[l.substring(0, i)] = l.substring(i + 1) }
        }
        map = m
    }
    fun ready() = map != null
    @Volatile private var vocabList: List<String>? = null
    /** คำเดี่ยวตัวอักษรล้วนจากพจนานุกรม ใช้เป็นคลังคำของ Spell (สร้างครั้งเดียว) */
    fun vocab(): List<String> = vocabList ?: (map?.keys?.filter { k -> k.length in 3..14 && k.all { it in 'a'..'z' } } ?: emptyList()).also { if (it.isNotEmpty()) vocabList = it }

    /** คืนคำแปลทั้งหมดของคำเดียว (คั่นด้วย ;) รวมการแปลงรูปคำ -s -ed -ing */
    fun lookup(word: String): String? {
        val m = map ?: return null
        val k = word.lowercase()
        m[k]?.takeIf { it.isNotBlank() }?.let { return it }
        irregular[k]?.let { b -> m[b]?.takeIf { it.isNotBlank() }?.let { return it } }
        val c = ArrayList<String>()
        if (k.endsWith("ies")) c.add(k.dropLast(3) + "y")
        if (k.endsWith("ied")) c.add(k.dropLast(3) + "y")
        if (k.endsWith("es")) c.add(k.dropLast(2))
        if (k.endsWith("s")) c.add(k.dropLast(1))
        if (k.endsWith("ed")) { c.add(k.dropLast(2)); c.add(k.dropLast(1)); if (k.length > 4 && k[k.length - 3] == k[k.length - 4]) c.add(k.dropLast(3)) }
        if (k.endsWith("ing")) { val b = k.dropLast(3); c.add(b); c.add(b + "e"); if (b.length > 2 && b.last() == b[b.length - 2]) c.add(b.dropLast(1)) }
        if (k.endsWith("ly")) c.add(k.dropLast(2))
        for (x in c) m[x]?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    /** แปลทีละคำ/วลี (จับคู่วลีสูงสุด 3 คำ) ใช้เมื่อเน็ตและโมเดลออฟไลน์ใช้ไม่ได้ */
    fun translate(s: String): String {
        val m = map ?: return ""
        val words = Regex("[A-Za-z]+(?:'[A-Za-z]+)?").findAll(s).map { it.value }.toList()
        val out = ArrayList<String>(); var hit = 0; var i = 0
        while (i < words.size) {
            var done = false
            for (n in 3 downTo 2) {
                if (i + n <= words.size) {
                    val g = m[words.subList(i, i + n).joinToString("_") { it.lowercase() }]
                    if (g != null) { out.add(g.substringBefore(';')); hit += n; i += n; done = true; break }
                }
            }
            if (done) continue
            val g = lookup(words[i])
            if (g != null) { hit++; g.substringBefore(';').let { if (it.isNotEmpty()) out.add(it) } } else out.add(words[i])
            i++
        }
        if (hit == 0) return ""
        val t = s.trimEnd()
        return out.joinToString(" ") + (if (t.endsWith("?")) "?" else if (t.endsWith("!")) "!" else "")
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }.addOnFailureListener { c.resumeWithException(it) }
}

/**
 * ลำดับการแปล (เร็ว): ตารางวลีมังงะ -> แคช -> Google -> Bing/Edge -> ML Kit ออฟไลน์ -> พจนานุกรม
 * แล้ว refine(): AI (ถ้าตั้งไว้) แปลทั้งหน้าพร้อมบริบท ได้สำนวนมังงะและความแม่นยำสูงสุด แทนที่ผลเร็วเมื่อเสร็จ
 * ทุกชั้นมีตัวสำรอง จึงพยายามให้ทุกกรอบมีคำแปลเสมอ
 */
object Translator {
    private const val CACHE_VER = 4   // เปลี่ยนเมื่อสำนวน/วิธีแปลเปลี่ยน เพื่อทิ้งแคชเก่าที่คุณภาพต่ำ
    private lateinit var app: Context
    @Volatile var onStatus: ((String) -> Unit)? = null
    private val cache = LruCache<String, String>(5000)
    private val aiSet: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private fun newClient() = Translation.getClient(TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.THAI).build())
    @Volatile private var client: com.google.mlkit.nl.translate.Translator? = null
    private val offline: com.google.mlkit.nl.translate.Translator
        @Synchronized get() = client ?: newClient().also { client = it }
    @Volatile private var offlineReady = false
    @Volatile private var lastErr = ""
    @Volatile private var lastEngine = ""
    @Volatile private var googleFails = 0
    @Volatile private var googleDownUntil = 0L
    @Volatile private var edgeFails = 0
    @Volatile private var edgeDownUntil = 0L
    @Volatile private var edgeTok: String? = null
    @Volatile private var edgeExp = 0L
    @Volatile private var glKey: String? = null
    @Volatile private var lastSave = 0L

    /** แคชคำแปลถูกเก็บลงดิสก์ด้วย (เปิดแอปใหม่/อ่านหน้าเดิมซ้ำ = ได้คำแปลทันที) ล้างเมื่อศัพท์เฉพาะ/เวอร์ชันสำนวนเปลี่ยน */
    fun init(ctx: Context) {
        app = ctx.applicationContext
        val g = Prefs.glossary + "|" + Prefs.mangaStyle
        if (g != glKey) { cache.evictAll(); aiSet.clear(); glKey = g; loadDisk(g) }
    }

    private fun sig(g: String) = "#" + (g.hashCode() * 31 + CACHE_VER)

    private fun loadDisk(g: String) {
        try {
            val f = File(app.filesDir, "tr_cache.tsv")
            if (!f.exists()) return
            val lines = f.readLines()
            if (lines.firstOrNull() != sig(g)) return
            for (k in 1 until lines.size) {
                var l = lines[k]; val ai = l.startsWith("*"); if (ai) l = l.substring(1)
                val i = l.indexOf('\t')
                if (i > 0) { val key = l.substring(0, i); cache.put(key, l.substring(i + 1)); if (ai) aiSet.add(key) }
            }
        } catch (e: Exception) { }
    }

    @Synchronized private fun saveDisk() {
        try {
            val k0 = glKey ?: return
            val e = cache.snapshot().entries.toList().takeLast(2000)
            val sb = StringBuilder(sig(k0)).append('\n')
            for ((k, v) in e) if ('\t' !in k && '\n' !in k && '\t' !in v && '\n' !in v) {
                if (k in aiSet) sb.append('*')
                sb.append(k).append('\t').append(v).append('\n')
            }
            val tmp = File(app.filesDir, "tr_cache.tmp"); tmp.writeText(sb.toString())
            tmp.renameTo(File(app.filesDir, "tr_cache.tsv"))
        } catch (e: Exception) { }
    }

    /** ปล่อยโมเดล/ตัวแปลเมื่อปิดบริการ (init ครั้งถัดไปจะสร้างใหม่เอง) */
    @Synchronized fun close() {
        onStatus = null
        saveDisk()
        client?.close(); client = null; offlineReady = false
        PpRec.close(); BubbleDet.close(); Inpaint.close()
    }
    private fun status(m: String) { onStatus?.invoke(m) }
    private fun setEngine(e: String) { if (e != lastEngine) { lastEngine = e; status("แปลด้วย: $e") } }
    fun engine() = lastEngine

    suspend fun warmUp() = coroutineScope {
        launch(Dispatchers.IO) { Dict.load(app) }
        launch(Dispatchers.IO) { if (Prefs.useModel && PpRec.installed(app) && !PpRec.load(app)) status("โมเดลอ่านตัวอักษรโหลดไม่ได้ (${PpRec.lastErr})") }
        launch(Dispatchers.IO) { if (Prefs.useDet && BubbleDet.installed(app) && !BubbleDet.load(app)) status("YOLO โหลดไม่ได้ (${BubbleDet.lastErr})") }
        launch(Dispatchers.IO) { if (Prefs.useInpaint && Inpaint.installed(app) && !Inpaint.load(app)) status("MI-GAN โหลดไม่ได้ (${Inpaint.lastErr})") }
        launch(Dispatchers.IO) { callRetry("hi") }
        launch(Dispatchers.IO) { edgeToken(false) }
        launch {
            try {
                offline.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                offlineReady = true; status("โมเดลแปลออฟไลน์พร้อมใช้")
            } catch (e: Exception) { status("ยังโหลดโมเดลออฟไลน์ไม่ได้ (ต้องใช้เน็ตครั้งแรก)") }
        }
    }

    /** แปลเร็ว (ไม่ใช้ AI) strict = ไม่ใช้พจนานุกรมแปลทีละคำกับประโยคยาว force = ลองตัวแปลที่เคยพักไว้ใหม่ */
    suspend fun translate(src: List<String>, strict: Boolean = true, force: Boolean = false): List<String?> = withContext(Dispatchers.IO) {
        val res = HashMap<String, String>()
        val miss = ArrayList<String>()
        if (force) { googleDownUntil = 0; edgeDownUntil = 0; googleFails = 0; edgeFails = 0 }
        for (s in src.distinct()) {
            val ph = if (Prefs.mangaStyle) Manga.phrase(s) else null
            if (ph != null) { res[s] = ph; continue }
            val c = cache.get(s)
            if (c != null) res[s] = c else miss.add(s)
        }
        if (miss.isNotEmpty()) coroutineScope {
            miss.chunked(12).map { ch -> async { runChain(ch, strict) } }.awaitAll().forEach { res.putAll(it) }
        }
        src.map { res[it]?.takeIf { t -> t.isNotBlank() } }
    }

    private val aiGate = kotlinx.coroutines.sync.Mutex()

    /** แปลด้วย AI ทั้งหน้าพร้อมบริบท คืนเฉพาะที่สำเร็จ (ผลถูกเก็บลงแคชแทนผลของเครื่องแปลเร็ว) */
    suspend fun refine(src: List<String>): Map<String, String> = withContext(Dispatchers.IO) {
        val out = HashMap<String, String>()
        if (!Llm.enabled()) return@withContext out
        val all = src.distinct().filter { !(Prefs.mangaStyle && Manga.phrase(it) != null) }
        val need = ArrayList<String>()
        for (s in all) { val c = cache.get(s); if (c != null && s in aiSet) out[s] = c else need.add(s) }
        if (need.isEmpty()) return@withContext out
        aiGate.withLock {
            for (ch in need.chunked(30)) {
                var r: List<String>? = null
                for (attempt in 0..1) { r = Llm.translate(ch); if (r != null) break; delay(400) }
                if (r == null) { status("AI แปลไม่ได้ (${Llm.lastErr}) ใช้ผลจากเครื่องแปลแทน"); continue }
                for (k in ch.indices) {
                    val t = Glossary.post(r[k])
                    if (t.isNotBlank()) { out[ch[k]] = t; cache.put(ch[k], t); aiSet.add(ch[k]) }
                }
            }
        }
        if (out.isNotEmpty()) { setEngine("AI"); val now = System.currentTimeMillis(); if (now - lastSave > 10000) { lastSave = now; saveDisk() } }
        out
    }

    private suspend fun runChain(chunk: List<String>, strict: Boolean): Map<String, String> {
        val got = HashMap<String, String>()
        var rest = chunk
        fun take(m: Map<String, String>, engine: String) {
            if (m.isEmpty()) return
            for ((k, v) in m) { val t = Manga.style(k, Glossary.post(v)); if (t.isNotBlank()) { got[k] = t; cache.put(k, t) } }
            setEngine(engine)
            rest = rest.filter { it !in got }
        }
        val now = System.currentTimeMillis()
        // 1) Google (ส่งเป็นชุดเดียว จับคู่ทีละบรรทัดจาก segment ที่ Google ส่งกลับ)
        if (now >= googleDownUntil) {
            val g = google(rest)
            if (g.isNotEmpty()) { googleFails = 0; take(g, "Google") }
            else { if (++googleFails >= 2) googleDownUntil = now + 30000; status("Google แปลไม่ได้ ($lastErr)") }
        }
        // 2) Bing/Edge (ส่งเป็น array จับคู่ตามลำดับ แม่นและไม่ถูกบล็อกพร้อม Google)
        if (rest.isNotEmpty() && System.currentTimeMillis() >= edgeDownUntil) {
            val e = edge(rest)
            if (e.isNotEmpty()) { edgeFails = 0; take(e, "Bing") }
            else if (++edgeFails >= 2) edgeDownUntil = System.currentTimeMillis() + 30000
        }
        // 3) Google ทีละบรรทัด (ช้าลงนิดหน่อยแต่ครบ) เมื่อทั้งชุดจับคู่ไม่ได้
        if (rest.isNotEmpty() && System.currentTimeMillis() >= googleDownUntil) take(googleEach(rest), "Google")
        // 4) ML Kit ออฟไลน์
        if (rest.isNotEmpty() && offlineReady) take(offline(rest), "ออฟไลน์ (ML Kit)")
        // 5) พจนานุกรม (ศัพท์สั้น ๆ เท่านั้น)
        if (rest.isNotEmpty()) {
            if (!Dict.ready()) Dict.load(app)
            val d = rest.filter { !strict || it.split(' ').count { w -> w.isNotBlank() } <= 4 }
                .associateWith { Dict.translate(it) }.filterValues { it.isNotBlank() }
            for ((k, v) in d) got[k] = v   // ไม่ใส่แคช: ครั้งหน้าลองตัวแปลที่ดีกว่าใหม่
            if (d.isNotEmpty()) setEngine("พจนานุกรม")
        }
        val t = System.currentTimeMillis()
        if (got.isNotEmpty() && t - lastSave > 20000) { lastSave = t; saveDisk() }
        return got
    }

    private suspend fun offline(src: List<String>): Map<String, String> {
        val out = HashMap<String, String>()
        for (s in src) try { out[s] = offline.translate(Glossary.pre(s)).await() } catch (e: Exception) { }
        return out
    }

    // ---------------- Google ----------------
    private fun google(chunk: List<String>): Map<String, String> {
        if (chunk.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        val pre = chunk.map { Glossary.pre(it) }
        val arr = callArr(pre.joinToString("\n")) ?: return out
        val joined = lines(arr)
        if (joined.size == chunk.size) chunk.indices.forEach { if (joined[it].isNotBlank()) out[chunk[it]] = joined[it] }
        return out   // จับคู่ไม่ลงตัว -> ปล่อยให้ชั้นถัดไปจัดการ (ไม่ยิงทีละบรรทัดทันที กันโดนจำกัดอัตรา)
    }

    private suspend fun googleEach(chunk: List<String>): Map<String, String> {
        val out = HashMap<String, String>()
        for (grp in chunk.chunked(3)) coroutineScope {
            grp.map { s -> async { callRetry(Glossary.pre(s))?.trim()?.takeIf { it.isNotBlank() }?.let { r -> synchronized(out) { out[s] = r } } } }.awaitAll()
        }
        return out
    }

    private fun seg(j: JSONArray, i: Int, k: Int): String {
        val a = j.optJSONArray(i) ?: return ""
        return if (a.isNull(k)) "" else a.optString(k, "")
    }

    /** จับคู่คำแปลกับบรรทัดต้นฉบับจากข้อความต้นฉบับที่ Google ส่งกลับมาในแต่ละ segment (แม่นกว่านับจำนวนบรรทัด) */
    private fun lines(j: JSONArray): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder()
        for (i in 0 until j.length()) {
            cur.append(seg(j, i, 0))
            if (seg(j, i, 1).endsWith("\n")) { out.add(cur.toString().trim()); cur.setLength(0) }
        }
        out.add(cur.toString().trim())
        return out
    }

    private fun joinAll(j: JSONArray) = buildString { for (i in 0 until j.length()) append(seg(j, i, 0)) }

    private fun callRetry(q: String): String? = callArr(q)?.let { joinAll(it) }

    private fun callArr(q: String): JSONArray? = call(q)

    private fun call(q: String): JSONArray? {
        val base = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=th&dt=t&ie=UTF-8&oe=UTF-8"
        val enc = URLEncoder.encode(q, "UTF-8")
        for (post in booleanArrayOf(true, false)) {
            var c: HttpURLConnection? = null
            var clean = false
            try {
                if (!post && enc.length > 3500) continue
                c = URL(if (post) base else "$base&q=$enc").openConnection() as HttpURLConnection
                c.connectTimeout = 2500; c.readTimeout = 4000
                c.setRequestProperty("User-Agent", UA)
                if (post) {
                    c.requestMethod = "POST"; c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    c.outputStream.use { it.write(("q=" + enc).toByteArray()) }
                }
                if (c.responseCode != 200) { lastErr = "HTTP ${c.responseCode}"; c.errorStream?.close(); clean = true; continue }
                val body = c.inputStream.bufferedReader().use { it.readText() }
                clean = true // อ่านจนจบและปิดสตรีมแล้ว: ปล่อยให้ connection กลับเข้า pool เพื่อใช้ซ้ำ
                return JSONArray(body).getJSONArray(0)
            } catch (e: Exception) { lastErr = e.javaClass.simpleName }
            finally { if (!clean) c?.disconnect() }
        }
        return null
    }

    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    // ---------------- Bing / Edge ----------------
    private fun edgeToken(force: Boolean): String? {
        val now = System.currentTimeMillis()
        val t = edgeTok
        if (!force && t != null && now < edgeExp) return t
        var c: HttpURLConnection? = null
        try {
            c = URL("https://edge.microsoft.com/translate/auth").openConnection() as HttpURLConnection
            c.connectTimeout = 2500; c.readTimeout = 3000; c.setRequestProperty("User-Agent", UA)
            if (c.responseCode != 200) { lastErr = "auth HTTP ${c.responseCode}"; return null }
            val tok = c.inputStream.bufferedReader().use { it.readText() }.trim()
            if (tok.length < 50) return null
            edgeTok = tok; edgeExp = now + 8 * 60_000L
            return tok
        } catch (e: Exception) { lastErr = e.javaClass.simpleName; return null }
        finally { c?.disconnect() }
    }

    private fun edge(chunk: List<String>): Map<String, String> {
        if (chunk.isEmpty()) return emptyMap()
        val body = JSONArray().also { a -> chunk.forEach { a.put(JSONObject().put("Text", Glossary.pre(it))) } }.toString()
        for (attempt in 0..1) {
            val tok = edgeToken(attempt == 1) ?: return emptyMap()
            var c: HttpURLConnection? = null
            try {
                c = URL("https://api-edge.cognitive.microsofttranslator.com/translate?from=en&to=th&api-version=3.0").openConnection() as HttpURLConnection
                c.connectTimeout = 2500; c.readTimeout = 5000; c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("User-Agent", UA)
                c.setRequestProperty("Authorization", "Bearer $tok")
                c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                if (code == 401) { edgeTok = null; c.errorStream?.close(); continue }
                if (code != 200) { lastErr = "Bing HTTP $code"; c.errorStream?.close(); return emptyMap() }
                val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
                if (arr.length() != chunk.size) return emptyMap()
                val out = HashMap<String, String>()
                for (i in chunk.indices) {
                    val t = arr.getJSONObject(i).getJSONArray("translations").getJSONObject(0).optString("text").trim()
                    if (t.isNotBlank()) out[chunk[i]] = t
                }
                return out
            } catch (e: Exception) { lastErr = e.javaClass.simpleName; return emptyMap() }
            finally { c?.disconnect() }
        }
        return emptyMap()
    }
}
