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

    /** ขนาดตัวอักษรสูงสุดของคำแปล (sp) */
    var maxSp: Int
        get() = sp.getInt("maxsp", 28).coerceIn(16, 40)
        set(v) { sp.edit().putInt("maxsp", v.coerceIn(16, 40)).apply() }

    var glossary: String
        get() = if (::sp.isInitialized) sp.getString("glossary", "") ?: "" else ""
        set(v) { sp.edit().putString("glossary", v).apply() }
}
