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
import kotlin.coroutines.resume

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

object Translator {
    private val cache = LruCache<String, String>(5000)

    suspend fun warmUp() { withContext(Dispatchers.IO) { callRetry("hi") } }

    suspend fun translate(src: List<String>): List<String?> = withContext(Dispatchers.IO) {
        val miss = src.filter { cache.get(it) == null }.distinct()
        if (miss.isNotEmpty()) coroutineScope {
            miss.chunked(25).map { chunk -> async { batch(chunk) } }.awaitAll()
        }
        src.map { cache.get(it) }
    }

    private suspend fun batch(chunk: List<String>) {
        val out = callRetry(chunk.joinToString("\n"))?.split("\n")
        if (out != null && out.size == chunk.size) chunk.indices.forEach { cache.put(chunk[it], out[it].trim()) }
        else coroutineScope {
            chunk.map { q -> async { callRetry(q)?.let { r -> cache.put(q, r.trim()) } } }.awaitAll()
        }
    }

    private fun callRetry(q: String): String? { repeat(2) { call(q)?.let { return it } }; return null }

    private fun call(q: String): String? = try {
        val c = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=th&dt=t&ie=UTF-8&oe=UTF-8").openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 4000; c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        c.outputStream.use { it.write(("q=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
        val j = JSONArray(c.inputStream.bufferedReader().readText()).getJSONArray(0)
        buildString { for (i in 0 until j.length()) append(j.getJSONArray(i).getString(0)) }
    } catch (e: Exception) { null }
}
