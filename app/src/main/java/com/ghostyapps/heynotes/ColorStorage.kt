package com.ghostyapps.heynotes

import android.content.Context
import android.graphics.Color

class ColorStorage(context: Context) {

    private val prefs = context.getSharedPreferences("note_colors", Context.MODE_PRIVATE)

    // --- MEVCUT FONKSİYONLAR (KORUNUYOR) ---

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

    // --- YENİ EKLENEN RENK PALETİ ---
    // Bunu 'companion object' içine koyuyoruz, böylece her yerden (örn: ColorStorage.colors) diye erişebilirsin.
    companion object {
        val colors = listOf(
            "#FFFFFF", // Varsayılan (Beyaz)
            "#F28B82", // Pastel Kırmızı
            "#FBBC04", // Pastel Turuncu
            "#FFF475", // Pastel Sarı
            "#CCFF90", // Pastel Yeşil
            "#A7FFEB", // Pastel Turkuaz
            "#CBF0F8", // Pastel Açık Mavi
            "#AECBFA", // Pastel Koyu Mavi
            "#D7AEFB", // Pastel Mor
            "#FDCFE8", // Pastel Pembe
            "#E6C9A8", // Pastel Kahve
            "#E8EAED"  // Pastel Gri
        )
    }
}