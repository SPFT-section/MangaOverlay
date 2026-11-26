package com.sunmind.mangaoverlay

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.LruCache
import com.google.mlkit.vision.common.InputImage
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class Item(val r: Rect, val text: String, val bg: Int)

object Ocr {
    private val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun run(bmp: Bitmap, minTop: Int = 0): List<Item> = suspendCancellableCoroutine { c ->
        rec.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { t ->
                c.resume(t.textBlocks.mapNotNull { b ->
                    val bb = b.boundingBox ?: return@mapNotNull null
                    if (bb.top < minTop) return@mapNotNull null
                    if (b.lines.isNotEmpty() && b.lines.map { it.confidence }.average() < 0.35) return@mapNotNull null
                    val raw = b.text.replace("-\n", "").replace("\n", " ").replace(Regex("\\s+"), " ").trim()
                    val letters = raw.count { it.isLetter() }
                    if (letters < 2 && raw != "I" && raw != "A") return@mapNotNull null
                    Item(Rect(bb), normalize(raw), bgOf(bmp, bb))
                })
            }
            .addOnFailureListener { c.resume(emptyList()) }
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

    /** สีพื้นกรอบคำพูด: เฉลี่ยจากจุดใกล้ขอบ/มุม */
    private fun bgOf(bmp: Bitmap, r: Rect): Int {
        val xs = intArrayOf(r.left + 3, (r.left + r.right) / 2, r.right - 3)
        val ys = intArrayOf(r.top + 3, (r.top + r.bottom) / 2, r.bottom - 3)
        var R = 0; var G = 0; var B = 0; var n = 0
        for (xi in 0..2) for (yi in 0..2) {
            if (xi == 1 && yi == 1) continue
            val x = xs[xi]; val y = ys[yi]
            if (x in 0 until bmp.width && y in 0 until bmp.height) {
                val p = bmp.getPixel(x, y); R += Color.red(p); G += Color.green(p); B += Color.blue(p); n++
            }
        }
        return if (n == 0) Color.WHITE else Color.rgb(R / n, G / n, B / n)
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

    fun init(ctx: Context) { app = ctx.applicationContext }
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
        val joined = callRetry(chunk.joinToString("\n"))?.split("\n")
        if (joined != null && joined.size == chunk.size) chunk.indices.forEach { out[chunk[it]] = joined[it].trim() }
        else if (joined != null) coroutineScope {
            chunk.map { q -> async { callRetry(q)?.let { r -> synchronized(out) { out[q] = r.trim() } } } }.awaitAll()
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
