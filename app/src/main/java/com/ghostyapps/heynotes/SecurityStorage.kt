package com.ghostyapps.heynotes

import android.content.Context

class SecurityStorage(context: Context) {
    private val prefs = context.getSharedPreferences("folder_security", Context.MODE_PRIVATE)

    // Set a password (empty string removes it)
    fun setPassword(folderId: String, pin: String) {
        if (pin.isEmpty()) {
            prefs.edit().remove(folderId).apply()
        } else {
            prefs.edit().putString(folderId, pin).apply()
        }
    }

    // Check if folder is locked
    fun isLocked(folderId: String): Boolean {
        return prefs.contains(folderId)
    }

    // Validate input against saved password
    fun checkPassword(folderId: String, inputPin: String): Boolean {
        val savedPin = prefs.getString(folderId, "")
        return savedPin == inputPin
    }
}