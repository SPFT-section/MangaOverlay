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

object Ocr {
    private val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private const val ST = 2      // ลดความละเอียดตอนหากรอบคำพูด
    private const val TOL = 28    // ความต่างความสว่างที่ยังนับเป็นพื้นกรอบเดียวกัน
    private val junkRe = Regex("(?i)(https?:|www\\.|\\w\\.(com|net|org)\\b|@\\w)")
    private val noLetterRe = Regex("^[\\d\\s\\p{Punct}]+$")

    private class Blk(val r: Rect, val text: String, val lh: Int)
    private class Fit(val r: Rect, val bg: Int)

    suspend fun run(bmp: Bitmap, minTop: Int = 0): List<Item> {
        val t = try { rec.process(InputImage.fromBitmap(bmp, 0)).await() } catch (e: Exception) { return emptyList() }
        return withContext(Dispatchers.Default) { build(bmp, t, minTop) }
    }

    private fun build(bmp: Bitmap, t: Text, minTop: Int): List<Item> {
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
        val blocks = if (Prefs.merge) merge(bmp, raw) else raw
        val out = ArrayList<Item>()
        for (b in blocks.sortedWith(compareBy({ it.r.top }, { it.r.left }))) {
            val letters = b.text.count { it.isLetter() }
            if (letters < 2 && b.text != "I" && b.text != "A") continue
            if (noLetterRe.matches(b.text)) continue
            val f = if (Prefs.fit) fit(bmp, b.r) else null
            out.add(Item(f?.r ?: Rect(b.r), normalize(b.text), f?.bg ?: bgOf(bmp, b.r), f != null))
        }
        return out
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
    private fun merge(bmp: Bitmap, a: List<Blk>): List<Blk> {
        val n = a.size
        val p = IntArray(n) { it }
        fun f(x: Int): Int { var y = x; while (p[y] != y) { p[y] = p[p[y]]; y = p[y] }; return y }
        for (i in 0 until n) for (j in i + 1 until n) if (same(bmp, a[i], a[j])) p[f(i)] = f(j)
        return (0 until n).groupBy { f(it) }.values.map { g ->
            val m = g.map { a[it] }.sortedBy { it.r.top }
            val r = Rect(m[0].r)
            m.forEach { r.union(it.r) }
            Blk(r, m.joinToString(" ") { it.text }, m.map { it.lh }.sorted()[m.size / 2])
        }
    }

    private fun same(bmp: Bitmap, a: Blk, b: Blk): Boolean {
        val u = if (a.r.top <= b.r.top) a else b
        val d = if (u === a) b else a
        val lo = min(u.lh, d.lh).coerceAtLeast(1)
        if (max(u.lh, d.lh).toFloat() / lo > 1.5f) return false
        val ov = min(u.r.right, d.r.right) - max(u.r.left, d.r.left)
        if (ov < 0.3f * min(u.r.width(), d.r.width())) return false
        val gap = d.r.top - u.r.bottom
        if (gap > 1.1f * lo) return false
        if (gap <= 0) return true
        return !divided(bmp, max(u.r.left, d.r.left), min(u.r.right, d.r.right), u.r.bottom, d.r.top)
    }

    private fun lumAt(bmp: Bitmap, x: Int, y: Int): Int {
        val p = bmp.getPixel(x.coerceIn(0, bmp.width - 1), y.coerceIn(0, bmp.height - 1))
        return (77 * Color.red(p) + 150 * Color.green(p) + 29 * Color.blue(p)) shr 8
    }

    /** มีเส้นขอบกรอบคำพูดคั่นระหว่างสองบล็อกไหม (สแกนแนวตั้ง 3 เส้น ต้องเจอขอบอย่างน้อย 2 เส้น) */
    private fun divided(bmp: Bitmap, x0: Int, x1: Int, y0: Int, y1: Int): Boolean {
        if (y1 - y0 < 3) return false
        var hits = 0
        for (k in 1..3) {
            val x = x0 + (x1 - x0) * k / 4
            val ref = lumAt(bmp, x, y0 + 1)
            var run = 0; var hit = false
            for (y in y0 + 1 until y1) {
                if (abs(lumAt(bmp, x, y) - ref) > 70) { run++; if (run >= 2) { hit = true; break } } else run = 0
            }
            if (hit) hits++
        }
        return hits >= 2
    }

    // ---------- หากรอบคำพูดรอบข้อความ ----------
    private fun fit(bmp: Bitmap, t: Rect): Fit? {
        val m = (max(t.width(), t.height()) * 0.8f).toInt().coerceIn(48, 360)
        val cl = max(0, t.left - m); val ct = max(0, t.top - m)
        val cr = min(bmp.width, t.right + m); val cb = min(bmp.height, t.bottom + m)
        val w = cr - cl; val h = cb - ct
        val gw = w / ST; val gh = h / ST
        if (gw < 12 || gh < 12) return null
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, cl, ct, w, h)
        val lum = IntArray(gw * gh)
        for (y in 0 until gh) for (x in 0 until gw) {
            val p = px[y * ST * w + x * ST]
            lum[y * gw + x] = (77 * Color.red(p) + 150 * Color.green(p) + 29 * Color.blue(p)) shr 8
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
            if (best == null || area > best[4]) { best = intArrayOf(x0, y0, x1, y1, area); bestColor = px[sy * ST * w + sx * ST] }
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

    /** ตัวพิมพ์ใหญ่ล้วนในมังงะ -> sentence case ให้ Google แปลแม่นขึ้น */
    private fun normalize(s: String): String {
        val l = s.filter { it.isLetter() }
        if (l.length < 3 || l.count { it.isUpperCase() } < l.length * 0.8) return s
        val sb = StringBuilder(); var cap = true
        for (ch in s.lowercase()) {
            if (cap && ch.isLetter()) { sb.append(ch.uppercaseChar()); cap = false } else sb.append(ch)
            if (ch == '.' || ch == '!' || ch == '?') cap = true
        }
        return sb.toString().replace(Regex("\\bi\\b"), "I").replace(Regex("\\bi'"), "I'")
    }

    /** สีพื้นกรอบคำพูด (สำรอง): มัธยฐานจากจุด 8 จุดรอบนอกข้อความ */
    private fun bgOf(bmp: Bitmap, r: Rect): Int {
        val xs = intArrayOf(r.left - 5, (r.left + r.right) / 2, r.right + 5)
        val ys = intArrayOf(r.top - 5, (r.top + r.bottom) / 2, r.bottom + 5)
        val l = ArrayList<Int>()
        for (xi in 0..2) for (yi in 0..2) {
            if (xi == 1 && yi == 1) continue
            l.add(bmp.getPixel(xs[xi].coerceIn(0, bmp.width - 1), ys[yi].coerceIn(0, bmp.height - 1)))
        }
        l.sortBy { 77 * Color.red(it) + 150 * Color.green(it) + 29 * Color.blue(it) }
        val p = l[l.size / 2]
        return Color.rgb(Color.red(p), Color.green(p), Color.blue(p))
    }
}

/** คำเรียกต่อท้าย/ศัพท์มังงะ + ศัพท์เฉพาะของผู้ใช้ (ชื่อตัวละคร) ใส่เป็นภาษาไทยก่อนส่งให้ Google */
object Glossary {
    private val terms = listOf(
        "onii-chan" to "โอนี่จัง", "oniichan" to "โอนี่จัง", "onee-chan" to "โอเนะจัง", "oneechan" to "โอเนะจัง",
        "onii-san" to "โอนี่ซัง", "onee-san" to "โอเนะซัง", "senpai" to "เซมไป", "sensei" to "เซนเซ", "kouhai" to "โคไฮ")
    private val suffixes = listOf("san" to "ซัง", "kun" to "คุง", "chan" to "จัง", "sama" to "ซามะ", "dono" to "โดโนะ", "sensei" to "เซนเซ", "senpai" to "เซมไป")
    private val joinRe = Regex("\\s+(ซัง|คุง|จัง|ซามะ|โดโนะ|เซนเซ|เซมไป)")

    fun pre(s: String): String {
        var t = s
        for ((a, b) in terms) t = t.replace(Regex("(?i)(?<![A-Za-z])" + Regex.escape(a) + "(?![A-Za-z])"), b)
        for ((a, b) in suffixes) t = t.replace(Regex("(?i)(?<=[A-Za-z])-" + a + "(?![A-Za-z])"), " $b")
        for (line in Prefs.glossary.lines()) {
            val i = line.indexOf('=')
            if (i <= 0) continue
            val k = line.substring(0, i).trim(); val v = line.substring(i + 1).trim()
            if (k.isEmpty() || v.isEmpty()) continue
            t = t.replace(Regex("(?i)(?<![A-Za-z])" + Regex.escape(k) + "(?![A-Za-z])"), v)
        }
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
        m[k]?.let { return it }
        irregular[k]?.let { b -> m[b]?.let { return it } }
        val c = ArrayList<String>()
        if (k.endsWith("ies")) c.add(k.dropLast(3) + "y")
        if (k.endsWith("ied")) c.add(k.dropLast(3) + "y")
        if (k.endsWith("es")) c.add(k.dropLast(2))
        if (k.endsWith("s")) c.add(k.dropLast(1))
        if (k.endsWith("ed")) { c.add(k.dropLast(2)); c.add(k.dropLast(1)); if (k.length > 4 && k[k.length - 3] == k[k.length - 4]) c.add(k.dropLast(3)) }
        if (k.endsWith("ing")) { val b = k.dropLast(3); c.add(b); c.add(b + "e"); if (b.length > 2 && b.last() == b[b.length - 2]) c.add(b.dropLast(1)) }
        if (k.endsWith("ly")) c.add(k.dropLast(2))
        for (x in c) m[x]?.let { return it }
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
    private val offline by lazy {
        Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.THAI).build())
    }
    @Volatile private var offlineReady = false
    @Volatile private var lastErr = ""
    @Volatile private var lastEngine = ""
    @Volatile private var googleFails = 0
    @Volatile private var googleDownUntil = 0L

    fun init(ctx: Context) { app = ctx.applicationContext; cache.evictAll() }
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

    suspend fun translate(src: List<String>): List<String?> = withContext(Dispatchers.IO) {
        val res = HashMap<String, String>()
        val miss = ArrayList<String>()
        for (s in src.distinct()) { val c = cache.get(s); if (c != null) res[s] = c else miss.add(s) }
        if (miss.isNotEmpty()) coroutineScope {
            miss.chunked(25).map { ch -> async { runChain(ch) } }.awaitAll().forEach { res.putAll(it) }
        }
        src.map { res[it]?.takeIf { t -> t.isNotBlank() } }
    }

    private suspend fun runChain(chunk: List<String>): Map<String, String> {
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
        val d = rest.associateWith { Dict.translate(it) }
        if (d.values.any { it.isNotBlank() }) setEngine("พจนานุกรม")
        for ((k, v) in g) cache.put(k, v)
        for ((k, v) in o) cache.put(k, v)
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
        val joined = callRetry(pre.joinToString("\n"))?.split("\n")
        if (joined != null && joined.size == chunk.size) chunk.indices.forEach { out[chunk[it]] = Glossary.post(joined[it].trim()) }
        else if (joined != null) coroutineScope {
            chunk.indices.map { i -> async { callRetry(pre[i])?.let { r -> synchronized(out) { out[chunk[i]] = Glossary.post(r.trim()) } } } }.awaitAll()
        }
        return out
    }

    private fun callRetry(q: String): String? { repeat(2) { call(q)?.let { return it } }; return null }

    private fun call(q: String): String? {
        val base = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=th&dt=t&ie=UTF-8&oe=UTF-8"
        val enc = URLEncoder.encode(q, "UTF-8")
        for (post in booleanArrayOf(true, false)) {
            try {
                if (!post && enc.length > 3500) continue
                val c = URL(if (post) base else "$base&q=$enc").openConnection() as HttpURLConnection
                c.connectTimeout = 3000; c.readTimeout = 4000
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
                if (post) {
                    c.requestMethod = "POST"; c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    c.outputStream.use { it.write(("q=" + enc).toByteArray()) }
                }
                if (c.responseCode != 200) { lastErr = "HTTP ${c.responseCode}"; continue }
                val j = JSONArray(c.inputStream.bufferedReader().readText()).getJSONArray(0)
                return buildString { for (i in 0 until j.length()) append(j.getJSONArray(i).getString(0)) }
            } catch (e: Exception) { lastErr = e.javaClass.simpleName }
        }
        return null
    }
}
