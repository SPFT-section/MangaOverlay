package com.sunmind.mangaoverlay

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * ตัวแปลด้วย AI (ไม่บังคับ — ต้องใส่ API key เอง) ส่งทุกกรอบของหน้าไปพร้อมกันเพื่อให้เห็นบริบททั้งหน้า
 * จึงได้ประโยคที่ต่อเนื่อง ใช้สรรพนาม/น้ำเสียงตรงตัวละคร แก้ข้อความที่ OCR อ่านเพี้ยน และเป็นสำนวนมังงะภาษาไทย
 */
object Llm {
    const val GEMINI = 1; const val OPENAI = 2; const val CLAUDE = 3
    @Volatile var lastErr = ""
    private val recent = ArrayDeque<String>()   // บรรทัดอังกฤษล่าสุด ใช้เป็นบริบทต่อเนื่องข้ามหน้า

    fun enabled() = Prefs.llmProvider != 0 && Prefs.llmKey.isNotBlank()

    fun defaultModel(p: Int) = when (p) { GEMINI -> "gemini-flash-latest"; OPENAI -> "gpt-4o-mini"; CLAUDE -> "claude-haiku-4-5"; else -> "" }
    fun defaultBase(p: Int) = when (p) { OPENAI -> "https://api.openai.com/v1"; else -> "" }

    private const val SYS = "You are a professional manga localizer translating English manga/comic dialogue into natural, colloquial Thai for Thai readers.\n" +
        "RULES:\n" +
        "- Write spoken Thai the way Thai fan-translation groups do: casual, punchy, emotional. Use particles (นะ, สิ, ล่ะ, เฮ้ย, เนี่ย, น่ะ, ซะ) where natural. Avoid stiff written/formal Thai, avoid ครับ/ค่ะ unless the speaker is clearly polite/servile (butler, shop clerk, honorific-heavy speech).\n" +
        "- Pick pronouns from context and speaker tone (ฉัน/ผม/ข้า/เรา/ตัวเอง for I; นาย/เธอ/แก/เจ้า/คุณ for you). Keep each character's voice consistent across lines.\n" +
        "- Keep Japanese honorifics as Thai transliteration (-san ซัง, -kun คุง, -chan จัง, -sama ซามะ, senpai เซมไป, sensei เซนเซ). Use the glossary exactly when given.\n" +
        "- Sound effects: use a short Thai onomatopoeia (ตูม, ปัง, ครืน, ฟิ้ว, ตึก). Keep '!' '?' '?!' '…' as in the source.\n" +
        "- The text comes from OCR: silently fix obvious OCR mistakes (l/I confusion, broken words, missing apostrophes) using context. Lines are in reading order and often continue each other across bubbles — translate so the dialogue flows.\n" +
        "- Keep each translation about as short as the source so it fits the speech bubble. No explanations, notes, romanization or quotation marks added.\n" +
        "- Never skip a line. If a line is only a watermark/URL/gibberish, return it unchanged.\n" +
        "OUTPUT: a JSON array of strings only, exactly N items in the same order as the input. No markdown fences."

    private fun userPrompt(lines: List<String>, ctx: List<String>): String {
        val sb = StringBuilder()
        val g = Prefs.glossary.trim()
        if (g.isNotEmpty()) sb.append("Glossary (English=Thai, must use):\n").append(g).append("\n\n")
        if (ctx.isNotEmpty()) { sb.append("Previous lines for context only (do NOT translate):\n"); ctx.forEach { sb.append("- ").append(it).append('\n') }; sb.append('\n') }
        sb.append("Translate these ").append(lines.size).append(" lines (N=").append(lines.size).append("):\n")
        lines.forEachIndexed { i, s -> sb.append(i + 1).append(". ").append(s.replace('\n', ' ')).append('\n') }
        return sb.toString()
    }

    /** คืนคำแปลตามลำดับเดียวกับ lines หรือ null ถ้าล้มเหลว (ผู้เรียกต้องมีตัวสำรอง) */
    fun translate(lines: List<String>): List<String>? {
        if (!enabled() || lines.isEmpty()) return null
        val ctx = synchronized(recent) { recent.toList() }
        val out = try { call(userPrompt(lines, ctx)) } catch (e: Exception) { lastErr = e.javaClass.simpleName + (e.message?.let { ": " + it.take(80) } ?: ""); null } ?: return null
        val arr = parse(out, lines.size)
        if (arr == null) { lastErr = "รูปแบบคำตอบไม่ถูกต้อง"; return null }
        synchronized(recent) { lines.takeLast(4).forEach { recent.addLast(it) }; while (recent.size > 8) recent.removeFirst() }
        return arr
    }

    private fun parse(text: String, n: Int): List<String>? {
        val a = text.indexOf('['); val b = text.lastIndexOf(']')
        if (a < 0 || b <= a) return null
        val j = try { JSONArray(text.substring(a, b + 1)) } catch (e: Exception) { return null }
        if (j.length() != n) return null
        val r = ArrayList<String>(n)
        for (i in 0 until n) { val v = j.opt(i); r.add(if (v is String) v.trim() else v?.toString()?.trim() ?: "") }
        return r
    }

    private fun post(url: String, headers: Map<String, String>, body: JSONObject): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 6000; c.readTimeout = 30000; c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw RuntimeException("HTTP $code " + txt.take(120).replace('\n', ' '))
            return txt
        } finally { c.disconnect() }
    }

    private fun call(user: String): String? {
        val p = Prefs.llmProvider; val key = Prefs.llmKey
        val model = Prefs.llmModel.ifBlank { defaultModel(p) }
        return when (p) {
            GEMINI -> {
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYS))))
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))))
                    .put("generationConfig", JSONObject().put("temperature", 0.3).put("responseMimeType", "application/json"))
                val r = JSONObject(post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", mapOf("x-goog-api-key" to key), body))
                val parts = r.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
                buildString { for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text")) }
            }
            OPENAI -> {
                val base = Prefs.llmBase.ifBlank { defaultBase(OPENAI) }.trimEnd('/')
                val body = JSONObject().put("model", model).put("temperature", 0.3).put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYS)).put(JSONObject().put("role", "user").put("content", user)))
                val r = JSONObject(post("$base/chat/completions", mapOf("Authorization" to "Bearer $key"), body))
                r.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
            }
            CLAUDE -> {
                val body = JSONObject().put("model", model).put("max_tokens", 4096).put("temperature", 0.3).put("system", SYS)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
                val r = JSONObject(post("https://api.anthropic.com/v1/messages", mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), body))
                val c = r.getJSONArray("content")
                buildString { for (i in 0 until c.length()) append(c.getJSONObject(i).optString("text")) }
            }
            else -> null
        }
    }

    /** ใช้กับปุ่มทดสอบในหน้าตั้งค่า */
    fun test(): String {
        if (!enabled()) return "ยังไม่ได้ใส่ API key"
        val r = translate(listOf("Damn it! I won't let you get away!", "Huh? Who are you?!"))
        return if (r == null) "ไม่สำเร็จ: $lastErr" else "สำเร็จ ✓\n" + r.joinToString("\n")
    }
}
