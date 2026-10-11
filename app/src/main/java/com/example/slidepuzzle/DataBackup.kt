package com.example.slidepuzzle

import android.content.Context
import org.json.JSONObject

/**
 * Serializes every local record/stat/setting to JSON so the user can back up
 * and restore via the system file picker (SAF) — fully offline.
 */
class DataBackup(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("slide_puzzle_store", Context.MODE_PRIVATE)

    fun exportJson(): JSONObject {
        val data = JSONObject()
        for ((key, value) in prefs.all) {
            when (value) {
                is Int -> data.put(key, value)
                is Boolean -> data.put(key, value)
                is Long -> data.put(key, value)
                is Float -> data.put(key, value.toDouble())
                is String -> data.put(key, value)
            }
        }
        return JSONObject()
            .put("app", "slide-puzzle")
            .put("formatVersion", 1)
            .put("data", data)
    }

    fun importJson(root: JSONObject) {
        val data = root.optJSONObject("data") ?: return
        val editor = prefs.edit()
        for (key in data.keys()) {
            when (val value = data.get(key)) {
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Long -> editor.putLong(key, value)
                is Double -> editor.putFloat(key, value.toFloat())
                is String -> editor.putString(key, value)
            }
        }
        editor.apply()
    }
}
