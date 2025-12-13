package com.ghostyapps.heynotes

import android.content.Context
import android.graphics.Color

class ColorStorage(context: Context) {

    private val prefs = context.getSharedPreferences("note_colors", Context.MODE_PRIVATE)

    // Save color for a specific file ID (or path)
    fun saveColor(id: String, colorHex: String) {
        prefs.edit().putString(id, colorHex).apply()
    }

    // Get color (Return null if none set)
    fun getColor(id: String): Int? {
        val hex = prefs.getString(id, null) ?: return null
        return try {
            Color.parseColor(hex)
        } catch (e: Exception) {
            null
        }
    }
}