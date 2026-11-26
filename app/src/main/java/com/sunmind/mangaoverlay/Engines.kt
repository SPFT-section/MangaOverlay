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

class Item(val r: Rect, val text: String, val bg: Int, val bubble: Boolean = false)

/** items = กรอบที่พร้อมวาง, tr = คำแปล (เริ่มแปลไปพร้อมกับการหากรอบ จึงมักเสร็จก่อน/พร้อมกัน) */
class Found(val items: List<Item>, val tr: Deferred<Map<String, String?>>)

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

    private class Blk(val r: Rect, val text: String, val lh: Int)
    private class Fit(val r: Rect, val bg: Int)

    suspend fun run(bmp: Bitmap, scope: CoroutineScope, minTop: Int = 0): Found {
        val none = Found(emptyList(), CompletableDeferred<Map<String, String?>>(emptyMap()))
        val t = try { rec.process(InputImage.fromBitmap(bmp, 0)).await() }
                catch (e: CancellationException) { throw e }   // ห้ามกลืน cancellation
                catch (e: Exception) { return none }
        return withContext(Dispatchers.Default) {
            try {
                val w = bmp.width; val h = bmp.height
                val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
                val img = Img(px, w, h)
                val bl = blocks(img, t, minTop).take(40)
                val texts = bl.map { normalize(it.text) }
                // เริ่มแปลทันที ขนานไปกับการหากรอบคำพูด (เดิมต้องรอหากรอบเสร็จก่อนถึงเริ่มแปล)
                val tr = scope.async(Dispatchers.IO) {
                    val d = texts.distinct()
                    d.zip(Translator.translate(d, strict = true)).toMap()
                }
                val items = bl.mapIndexed { i, b -> async { toItem(img, b, texts[i]) } }.awaitAll()
                Found(items, tr)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { none }
        }
    }

    private fun blocks(img: Img, t: Text, minTop: Int): List<Blk> {
        val raw = ArrayList<Blk>()
        for (b in t.textBlocks) {
            val bb = b.boundingBox ?: continue
            if (bb.top < minTop) continue
            if (b.lines.isNotEmpty() && b.lines.map { it.confidence }.average() < 0.35) continue
            val s = clean(b.text)
            if (junkRe.containsMatchIn(s)) continue // ลายน้ำ/URL
            val hs = b.lines.mapNotNull { it.boundingBox?.height() }.sorted()
            raw.add(Blk(Rect(bb), s, if (hs.isEmpty()) bb.height() else hs[hs.size / 2]))
        }
        val merged = if (Prefs.merge) merge(img, raw) else raw
        return merged.sortedWith(compareBy({ it.r.top }, { it.r.left })).filter { b ->
            val letters = b.text.count { it.isLetter() }
            !(letters < 2 && b.text != "I" && b.text != "A") && !noLetterRe.matches(b.text)
        }
    }

    private fun toItem(img: Img, b: Blk, text: String): Item {
        val f = if (Prefs.fit) fit(img, b.r) else null
        return Item(f?.r ?: Rect(b.r), text, f?.bg ?: bgOf(img, b.r), f != null)
    }

    /** แก้ที่ OCR อ่านผิดบ่อยในฟอนต์มังงะ */
    private fun clean(s0: String): String {
        var s = s0.replace("-\n", "").replace("\n", " ")
            .replace('\u2019', '\'').replace('\u2018', '\'').replace('\u201C', '"').replace('\u201D', '"')
            .replace('|', 'I')
        s = s.replace(Regex("(?<=[A-Z])0(?=[A-Z])"), "O").replace(Regex("(?<=[A-Z])1(?=[A-Z])"), "I")
        s = s.replace(Regex("(?i)(?<=[a-z])\\s*'\\s*(?=(?:t|s|m|d|ll|re|ve)\\b)"), "'")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    // ---------- รวมบล็อกที่อยู่ในกรอบคำพูดเดียวกัน ----------
    private fun merge(img: Img, a: List<Blk>): List<Blk> {
        val n = a.size
        val p = IntArray(n) { it }
        fun f(x: Int): Int { var y = x; while (p[y] != y) { p[y] = p[p[y]]; y = p[y] }; return y }
        for (i in 0 until n) for (j in i + 1 until n) if (same(img, a[i], a[j])) p[f(i)] = f(j)
        return (0 until n).groupBy { f(it) }.values.map { g ->
            val m = g.map { a[it] }.sortedBy { it.r.top }
            val r = Rect(m[0].r)
            m.forEach { r.union(it.r) }
            Blk(r, m.joinToString(" ") { it.text }, m.map { it.lh }.sorted()[m.size / 2])
        }
    }

    private fun same(img: Img, a: Blk, b: Blk): Boolean {
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

/** ลำดับการแปล: Google (เร็ว/ดี) -> ML Kit ออฟไลน์ (บนเครื่อง) -> พจนานุกรมในแอป */
object Translator {
    private lateinit var app: Context
    @Volatile var onStatus: ((String) -> Unit)? = null
    private val cache = LruCache<String, String>(5000)
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

    @Volatile private var glKey: String? = null
    @Volatile private var lastSave = 0L

    /** แคชคำแปลถูกเก็บลงดิสก์ด้วย (เปิดแอปใหม่/อ่านหน้าเดิมซ้ำ = ได้คำแปลทันทีไม่ต้องรอเน็ต) ล้างเมื่อศัพท์เฉพาะเปลี่ยน */
    fun init(ctx: Context) {
        app = ctx.applicationContext
        val g = Prefs.glossary
        if (g != glKey) { cache.evictAll(); glKey = g; loadDisk(g) }
    }

    private fun loadDisk(g: String) {
        try {
            val f = File(app.filesDir, "tr_cache.tsv")
            if (!f.exists()) return
            val lines = f.readLines()
            if (lines.firstOrNull() != "#" + g.hashCode()) return
            for (k in 1 until lines.size) {
                val l = lines[k]; val i = l.indexOf('\t')
                if (i > 0) cache.put(l.substring(0, i), l.substring(i + 1))
            }
        } catch (e: Exception) { }
    }

    @Synchronized private fun saveDisk() {
        try {
            val k0 = glKey ?: return
            val e = cache.snapshot().entries.toList().takeLast(2000)
            val sb = StringBuilder("#").append(k0.hashCode()).append('\n')
            for ((k, v) in e) if ('\t' !in k && '\n' !in k && '\t' !in v && '\n' !in v) sb.append(k).append('\t').append(v).append('\n')
            val tmp = File(app.filesDir, "tr_cache.tmp"); tmp.writeText(sb.toString())
            tmp.renameTo(File(app.filesDir, "tr_cache.tsv"))
        } catch (e: Exception) { }
    }

    /** ปล่อยโมเดล/ตัวแปลเมื่อปิดบริการ (init ครั้งถัดไปจะสร้างใหม่เอง) */
    @Synchronized fun close() {
        onStatus = null
        saveDisk()
        client?.close(); client = null; offlineReady = false
    }
    private fun status(m: String) { onStatus?.invoke(m) }
    private fun setEngine(e: String) { if (e != lastEngine) { lastEngine = e; status("แปลด้วย: $e") } }

    suspend fun warmUp() = coroutineScope {
        launch(Dispatchers.IO) { Dict.load(app) }
        launch(Dispatchers.IO) { callRetry("hi") }
        launch {
            try {
                offline.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                offlineReady = true; status("โมเดลแปลออฟไลน์พร้อมใช้")
            } catch (e: Exception) { status("ยังโหลดโมเดลออฟไลน์ไม่ได้ (ต้องใช้เน็ตครั้งแรก)") }
        }
    }

    /** strict = ไม่ใช้พจนานุกรมแปลทีละคำกับประโยคยาว (ได้คำเรียงกันไม่เป็นประโยค) ปล่อยให้เห็นต้นฉบับแทน */
    suspend fun translate(src: List<String>, strict: Boolean = false): List<String?> = withContext(Dispatchers.IO) {
        val res = HashMap<String, String>()
        val miss = ArrayList<String>()
        for (s in src.distinct()) { val c = cache.get(s); if (c != null) res[s] = c else miss.add(s) }
        if (miss.isNotEmpty()) coroutineScope {
            miss.chunked(25).map { ch -> async { runChain(ch, strict) } }.awaitAll().forEach { res.putAll(it) }
        }
        src.map { res[it]?.takeIf { t -> t.isNotBlank() } }
    }

    private suspend fun runChain(chunk: List<String>, strict: Boolean): Map<String, String> {
        val now = System.currentTimeMillis()
        val g = if (now >= googleDownUntil) google(chunk) else emptyMap()
        if (g.isNotEmpty()) { googleFails = 0; setEngine("Google") }
        else if (now >= googleDownUntil) {
            googleFails++
            if (googleFails >= 2) googleDownUntil = now + 30000 // พัก Google 30 วิ ไม่ให้ช้า
            status("Google แปลไม่ได้ ($lastErr) ใช้ออฟไลน์แทน")
        }
        var rest = chunk.filter { it !in g }
        val o = if (rest.isNotEmpty() && offlineReady) offline(rest) else emptyMap()
        if (o.isNotEmpty()) setEngine("ออฟไลน์ (ML Kit)")
        rest = rest.filter { it !in o }
        if (rest.isNotEmpty() && !Dict.ready()) Dict.load(app)
        val d = rest.filter { !strict || it.split(' ').count { w -> w.isNotBlank() } <= 3 }.associateWith { Dict.translate(it) }
        if (d.values.any { it.isNotBlank() }) setEngine("พจนานุกรม")
        for ((k, v) in g) cache.put(k, v)
        for ((k, v) in o) cache.put(k, v)
        if ((g.isNotEmpty() || o.isNotEmpty()) && now - lastSave > 20000) { lastSave = now; saveDisk() }
        return g + o + d
    }

    private suspend fun offline(src: List<String>): Map<String, String> {
        val out = HashMap<String, String>()
        for (s in src) try { out[s] = offline.translate(s).await() } catch (e: Exception) { }
        return out
    }

    private suspend fun google(chunk: List<String>): Map<String, String> {
        val out = HashMap<String, String>()
        val pre = chunk.map { Glossary.pre(it) }
        val arr = callArr(pre.joinToString("\n"))
        val joined = arr?.let { lines(it) }
        if (joined != null && joined.size == chunk.size) chunk.indices.forEach { out[chunk[it]] = Glossary.post(joined[it]) }
        else if (arr != null) coroutineScope {
            chunk.indices.map { i -> async { callRetry(pre[i])?.let { r -> synchronized(out) { out[chunk[i]] = Glossary.post(r.trim()) } } } }.awaitAll()
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

    private fun callArr(q: String): JSONArray? { repeat(2) { call(q)?.let { return it } }; return null }

    private fun call(q: String): JSONArray? {
        val base = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=th&dt=t&ie=UTF-8&oe=UTF-8"
        val enc = URLEncoder.encode(q, "UTF-8")
        for (post in booleanArrayOf(true, false)) {
            var c: HttpURLConnection? = null
            var clean = false
            try {
                if (!post && enc.length > 3500) continue
                c = URL(if (post) base else "$base&q=$enc").openConnection() as HttpURLConnection
                c.connectTimeout = 3000; c.readTimeout = 4000
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
                if (post) {
                    c.requestMethod = "POST"; c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    c.outputStream.use { it.write(("q=" + enc).toByteArray()) }
                }
                if (c.responseCode != 200) { lastErr = "HTTP ${c.responseCode}"; c.errorStream?.close(); clean = true; continue }
                val body = c.inputStream.bufferedReader().use { it.readText() }
                clean = true // อ่านจนจบและปิดสตรีมแล้ว: ปล่อยให้ connection กลับเข้า pool เพื่อใช้ซ้ำ (ไม่ต้อง handshake TLS ใหม่)
                return JSONArray(body).getJSONArray(0)
            } catch (e: Exception) { lastErr = e.javaClass.simpleName }
            finally { if (!clean) c?.disconnect() }
        }
        return null
    }
}
