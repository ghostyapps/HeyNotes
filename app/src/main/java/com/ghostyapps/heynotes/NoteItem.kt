package com.ghostyapps.heynotes

data class NoteItem(
    val name: String,
    val isFolder: Boolean,
    val id: String,
    val content: String = "",
    val date: String = "",      // Listede görünecek KISA metin (24 May)
    val timestamp: Long = 0L,   // Editör için HAM zaman verisi (YENİ)
    val isDivider: Boolean = false,
    var isSelected: Boolean = false,
    var color: Int? = null,
    var isActive: Boolean = false,
    var isLocked: Boolean = false
)