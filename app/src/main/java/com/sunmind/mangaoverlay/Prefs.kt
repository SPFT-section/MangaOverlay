package com.sunmind.mangaoverlay

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (!::sp.isInitialized) sp = ctx.applicationContext.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    }

    var merge: Boolean
        get() = sp.getBoolean("merge", true)
        set(v) { sp.edit().putBoolean("merge", v).apply() }

    var fit: Boolean
        get() = sp.getBoolean("fit", true)
        set(v) { sp.edit().putBoolean("fit", v).apply() }

    var bold: Boolean
        get() = sp.getBoolean("bold", false)
        set(v) { sp.edit().putBoolean("bold", v).apply() }

    /** สั่นเบา ๆ เมื่อแตะ/กดค้างปุ่มลอย */
    var haptic: Boolean
        get() = sp.getBoolean("haptic", true)
        set(v) { sp.edit().putBoolean("haptic", v).apply() }

    /** ความทึบของกล่องคำแปล 40-100 (%) */
    var opacity: Int
        get() = sp.getInt("opacity", 100).coerceIn(40, 100)
        set(v) { sp.edit().putInt("opacity", v.coerceIn(40, 100)).apply() }

    /** ชดเชยความหน่วงของกรอบตอนเลื่อน 0-120 ms (มากไป = กรอบวิ่งนำหน้า น้อยไป = กรอบตามหลัง) */
    var lead: Int
        get() = if (::sp.isInitialized) sp.getInt("lead", 50).coerceIn(0, 120) else 50
        set(v) { sp.edit().putInt("lead", v.coerceIn(0, 120)).apply() }

    /** ขนาดตัวอักษรสูงสุดของคำแปล (sp) */
    var maxSp: Int
        get() = sp.getInt("maxsp", 28).coerceIn(16, 40)
        set(v) { sp.edit().putInt("maxsp", v.coerceIn(16, 40)).apply() }

    /** ใช้โมเดล OCR (PP-OCR จาก Hugging Face) ช่วยอ่านกรอบที่ ML Kit ไม่มั่นใจ */
    var useModel: Boolean
        get() = sp.getBoolean("usemodel", true)
        set(v) { sp.edit().putBoolean("usemodel", v).apply() }

    /** URL ไฟล์ .onnx ของโมเดลอ่านตัวอักษร (ว่าง = ค่าเริ่มต้นใน PpRec) */
    var modelUrl: String
        get() = sp.getString("modelurl", "") ?: ""
        set(v) { sp.edit().putString("modelurl", v.trim()).apply() }

    /** ใช้ YOLOv8n-bubble หากรอบคำพูด (ช่วยรวมข้อความกรอบเดียวกัน + ขอบเขตกล่องคำแปล) */
    var useDet: Boolean
        get() = sp.getBoolean("usedet", true)
        set(v) { sp.edit().putBoolean("usedet", v).apply() }

    var detUrl: String
        get() = sp.getString("deturl", "") ?: ""
        set(v) { sp.edit().putString("deturl", v.trim()).apply() }

    /** ใช้ MI-GAN ลบข้อความเดิมแล้วเติมพื้นหลัง แทนการวางสีพื้นทึบทับ */
    var useInpaint: Boolean
        get() = sp.getBoolean("useinp", true)
        set(v) { sp.edit().putBoolean("useinp", v).apply() }

    var inpUrl: String
        get() = sp.getString("inpurl", "") ?: ""
        set(v) { sp.edit().putString("inpurl", v.trim()).apply() }

    var glossary: String
        get() = if (::sp.isInitialized) sp.getString("glossary", "") ?: "" else ""
        set(v) { sp.edit().putString("glossary", v).apply() }

    /** ปรับสำนวนคำแปลให้เป็นภาษาพูดแบบมังงะ (วลีสำเร็จรูป + ตัดคำสุภาพ/ทางการที่เครื่องแปลใส่เกิน) */
    var mangaStyle: Boolean
        get() = sp.getBoolean("mstyle", true)
        set(v) { sp.edit().putBoolean("mstyle", v).apply() }

    /** ตัวแปลด้วย AI (ไม่บังคับ): 0=ปิด 1=Gemini 2=OpenAI-compatible (Groq/OpenRouter/Ollama ฯลฯ) 3=Claude */
    var llmProvider: Int
        get() = sp.getInt("llm_p", 0).coerceIn(0, 3)
        set(v) { sp.edit().putInt("llm_p", v.coerceIn(0, 3)).apply() }

    var llmKey: String
        get() = sp.getString("llm_k", "") ?: ""
        set(v) { sp.edit().putString("llm_k", v.trim()).apply() }

    var llmModel: String
        get() = sp.getString("llm_m", "") ?: ""
        set(v) { sp.edit().putString("llm_m", v.trim()).apply() }

    var llmBase: String
        get() = sp.getString("llm_b", "") ?: ""
        set(v) { sp.edit().putString("llm_b", v.trim()).apply() }
}
