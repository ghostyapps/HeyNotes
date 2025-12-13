package com.ghostyapps.heynotes

import android.content.Context
import android.os.Environment
import java.io.File

class LocalServiceHelper(private val context: Context) {

    private val rootDirName = "HeyNotes"

    // 1. Visible Public Folder (Documents)
    fun getPublicRoot(): File {
        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val folder = File(documentsDir, rootDirName)
        if (!folder.exists()) folder.mkdirs()
        return folder
    }

    // 2. Invisible Private Folder (Internal App Storage)
    fun getPrivateRoot(): File {
        val folder = File(context.filesDir, rootDirName) // /data/user/0/com.../files/HeyNotes
        if (!folder.exists()) folder.mkdirs()
        return folder
    }

    // 3. List Items (Merges Public and Private if we are at Root)
    fun listItems(directory: File): List<NoteItem> {
        val items = mutableListOf<NoteItem>()

        // Check if we are listing the ROOT level
        // We compare absolute paths to see if we are currently looking at the Public Root
        if (directory.absolutePath == getPublicRoot().absolutePath) {

            // A. Add Public Items
            items.addAll(scanDirectory(getPublicRoot()))

            // B. Add Private (Locked) Items
            // We map them so they appear in the list, but their ID points to the private path
            items.addAll(scanDirectory(getPrivateRoot()))

        } else {
            // We are inside a specific folder (Public or Private), just scan it
            items.addAll(scanDirectory(directory))
        }

        // Sort: Folders first, then Newest
        return items.sortedWith(
            compareByDescending<NoteItem> { it.isFolder }
                .thenByDescending { File(it.id).lastModified() }
        )
    }

    // Helper to scan a specific dir
    private fun scanDirectory(dir: File): List<NoteItem> {
        if (!dir.exists()) dir.mkdirs()
        return dir.listFiles()?.map { file ->
            // Read Preview
            val preview = if (!file.isDirectory) {
                try { file.readText().take(150).replace("\n", " ") } catch (e: Exception) { "" }
            } else { "" }

            NoteItem(
                name = file.name,
                isFolder = file.isDirectory,
                id = file.absolutePath, // This path will be Private or Public automatically
                content = preview
            )
        } ?: emptyList()
    }

    // 4. Move Logic (Lock/Unlock)
    // 1. KİLİTLEME: Genel -> Özel (Kopyala ve Sil)
    fun moveFolderToPrivate(folderName: String): String? {
        val source = File(getPublicRoot(), folderName)
        val dest = File(getPrivateRoot(), folderName)

        if (source.exists()) {
            try {
                // Eğer hedefte aynı isimde klasör kalıntısı varsa temizle
                if (dest.exists()) dest.deleteRecursively()

                // 1. Kopyala
                val copySuccess = source.copyRecursively(dest, overwrite = true)

                // 2. Kopyalama başarılıysa kaynağı sil (Public'ten kaldır)
                if (copySuccess) {
                    source.deleteRecursively()
                    return dest.absolutePath
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Hata olursa ve yarım kopyalandıysa, bozuk kopyayı temizle
                if (dest.exists()) dest.deleteRecursively()
            }
        }
        return null
    }

    // 2. KİLİDİ AÇMA: Özel -> Genel (Kopyala ve Sil)
    fun moveFolderToPublic(folderName: String): String? {
        val source = File(getPrivateRoot(), folderName)
        val dest = File(getPublicRoot(), folderName)

        if (source.exists()) {
            try {
                // Hedefte çakışma varsa temizle (veya (1) ekle mantığı kurabilirsin ama şimdilik üzerine yazalım)
                if (dest.exists()) dest.deleteRecursively()

                // 1. Kopyala
                val copySuccess = source.copyRecursively(dest, overwrite = true)

                // 2. Başarılıysa özel alandakini sil
                if (copySuccess) {
                    source.deleteRecursively()
                    return dest.absolutePath
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return null
    }

    // ... (Keep your existing createFolder, saveNote, updateNote, deleteFile methods) ...
    // IMPORTANT: Update getUniqueFile to take 'parentDir' which is already passed correctly by MainActivity

    // ... Copy your existing create/save/delete logic here ...

    // Re-pasting standard helpers for completeness:
    fun createFolder(parentDir: File, name: String) {
        val newFolder = File(parentDir, name)
        if (!newFolder.exists()) newFolder.mkdirs()
    }

    fun saveNote(parentDir: File, title: String, content: String) {
        val safeTitle = if (title.endsWith(".md")) title else "$title.md"
        val file = getUniqueFile(parentDir, safeTitle)
        file.writeText(content)
    }

    fun updateNote(oldPath: String, newTitle: String, content: String) {
        val oldFile = File(oldPath)
        val parentDir = oldFile.parentFile ?: return
        val safeTitle = if (newTitle.endsWith(".md")) newTitle else "$newTitle.md"

        if (oldFile.name != safeTitle) {
            val uniqueFile = getUniqueFile(parentDir, safeTitle)
            oldFile.renameTo(uniqueFile)
            uniqueFile.writeText(content)
        } else {
            oldFile.writeText(content)
        }
    }

    fun readFile(path: String): String {
        return File(path).readText()
    }

    fun deleteFile(path: String) {
        val file = File(path)
        if (file.exists()) file.deleteRecursively()
    }

    private fun getUniqueFile(parentDir: File, desiredName: String): File {
        var file = File(parentDir, desiredName)
        var nameWithoutExt = desiredName.removeSuffix(".md")
        var counter = 1
        while (file.exists()) {
            val newName = "$nameWithoutExt ($counter).md"
            file = File(parentDir, newName)
            counter++
        }
        return file
    }

    // Helper to get Root (Defaults to Public for initial load)
    fun getRootFolder(): File {
        return getPublicRoot()
    }

    // Recursive Count (Scans BOTH)
    fun getTotalNoteCount(): Int {
        val publicCount = getPublicRoot().walk().filter { it.isFile && it.name.endsWith(".md") }.count()
        val privateCount = getPrivateRoot().walk().filter { it.isFile && it.name.endsWith(".md") }.count()
        return publicCount + privateCount
    }
}