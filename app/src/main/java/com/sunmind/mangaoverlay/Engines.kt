package com.sunmind.mangaoverlay

import android.graphics.Bitmap
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

object Ocr {
    private val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    suspend fun run(bmp: Bitmap): List<Pair<Rect, String>> = suspendCancellableCoroutine { c ->
        rec.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { t ->
                c.resume(t.textBlocks.mapNotNull { b ->
                    val bb = b.boundingBox ?: return@mapNotNull null
                    val s = b.text.replace("-\n", "").replace("\n", " ").trim()
                    if (s.length < 2 || !s.any { it.isLetter() }) null else Pair(bb, s)
                })
            }
            .addOnFailureListener { c.resume(emptyList()) }
    }
}

object Translator {
    private val cache = LruCache<String, String>(3000)

    suspend fun translate(src: List<String>): List<String> = withContext(Dispatchers.IO) {
        val miss = src.filter { cache.get(it) == null }.distinct()
        if (miss.isNotEmpty()) {
            val out = call(miss.joinToString("\n"))?.split("\n")
            if (out != null && out.size == miss.size) miss.indices.forEach { cache.put(miss[it], out[it].trim()) }
            else coroutineScope {
                miss.map { q -> async { call(q)?.let { r -> cache.put(q, r.trim()) } } }.awaitAll()
            }
        }
        src.map { cache.get(it) ?: it }
    }

    private fun call(q: String): String? = try {
        val c = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=th&dt=t").openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 4000; c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        c.outputStream.use { it.write(("q=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
        val j = JSONArray(c.inputStream.bufferedReader().readText()).getJSONArray(0)
        buildString { for (i in 0 until j.length()) append(j.getJSONArray(i).getString(0)) }
    } catch (e: Exception) { null }
}
