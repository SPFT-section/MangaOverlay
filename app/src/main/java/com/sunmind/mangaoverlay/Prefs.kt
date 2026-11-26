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

    var glossary: String
        get() = if (::sp.isInitialized) sp.getString("glossary", "") ?: "" else ""
        set(v) { sp.edit().putString("glossary", v).apply() }
}
