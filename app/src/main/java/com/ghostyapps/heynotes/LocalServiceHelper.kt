package com.ghostyapps.heynotes

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        val folder = File(context.filesDir, rootDirName)
        if (!folder.exists()) folder.mkdirs()
        return folder
    }

    // 3. List Items (OPTIMIZED: Suspend & IO Dispatcher)
    suspend fun listItems(directory: File): List<NoteItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<NoteItem>()

        // Check if we are listing the ROOT level
        if (directory.absolutePath == getPublicRoot().absolutePath) {
            // Merge Public + Private
            items.addAll(scanDirectory(getPublicRoot()))
            items.addAll(scanDirectory(getPrivateRoot()))
        } else {
            // Specific folder
            items.addAll(scanDirectory(directory))
        }

        // Sort: Folders first, then Newest
        return@withContext items.sortedWith(
            compareByDescending<NoteItem> { it.isFolder }
                .thenByDescending { File(it.id).lastModified() }
        )
    }

    // Dosya isimlerini temizleyen fonksiyon (Bunu class içine ekle)
    private fun getSafeFileName(input: String): String {
        return input.replace(":", "-")
            .replace("/", "_")
            .replace("\\", "_")
            .trim()
    }

    // Helper to scan a specific dir
// Helper to scan a specific dir (GÜNCELLENDİ: FİLTRELEME EKLENDİ)
// LocalServiceHelper.kt -> scanDirectory fonksiyonu

// LocalServiceHelper.kt -> scanDirectory fonksiyonu

// LocalServiceHelper.kt -> scanDirectory fonksiyonu

    private fun scanDirectory(dir: File): List<NoteItem> {
        if (!dir.exists()) dir.mkdirs()

        // 1. LİSTE İÇİN KISA FORMAT (Yıl Yok: "24 May")
        val shortFormat = java.text.SimpleDateFormat("dd MMM", java.util.Locale.getDefault())

        return dir.listFiles()?.filter {
            it.isDirectory || it.extension.equals("md", ignoreCase = true)
        }?.map { file ->
            val preview = if (!file.isDirectory) {
                try { file.readText().take(150).replace("\n", " ") } catch (e: Exception) { "" }
            } else { "" }

            val lastMod = file.lastModified()
            val dateStr = shortFormat.format(java.util.Date(lastMod))

            NoteItem(
                name = file.name,
                isFolder = file.isDirectory,
                id = file.absolutePath,
                content = preview,
                date = dateStr,   // Listede görünecek kısa tarih
                timestamp = lastMod // Editöre gönderilecek ham zaman
            )
        } ?: emptyList()
    }


    // 4. Move Logic (Locking)
    fun moveFolderToPrivate(folderName: String): String? {
        val source = File(getPublicRoot(), folderName)
        val dest = File(getPrivateRoot(), folderName)

        if (source.exists()) {
            try {
                if (dest.exists()) dest.deleteRecursively()
                val copySuccess = source.copyRecursively(dest, overwrite = true)
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

    // 5. Move Logic (Unlocking)
    fun moveFolderToPublic(folderName: String): String? {
        val source = File(getPrivateRoot(), folderName)
        val dest = File(getPublicRoot(), folderName)

        if (source.exists()) {
            try {
                if (dest.exists()) dest.deleteRecursively()
                val copySuccess = source.copyRecursively(dest, overwrite = true)
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

    // 6. Create Folder
    fun createFolder(parentDir: File, name: String) {
        val newFolder = File(parentDir, name)
        if (!newFolder.exists()) newFolder.mkdirs()
    }

    // 7. Save Note (Updated to Return File)
    fun saveNote(parentDir: File, title: String, content: String): File {
        val safeTitle = getSafeFileName(title)
        val finalName = if (safeTitle.isNotEmpty()) safeTitle else "Untitled Note ${System.currentTimeMillis()}"

        // DÜZELTME BURADA: 'folder' yerine 'parentDir' kullanıyoruz
        val file = File(parentDir, "$finalName.md")

        file.writeText(content)
        return file
    }

    // --- YENİ EKLENEN FONKSİYON ---
    // MainActivity'deki hatayı çözen kısım burası.
    // ID parametresini alıyor ama dosya sistemi yapısında şimdilik "saveNote" ile aynı işi yapıyor.
    // İleride ID ile özel bir meta-data tutmak istersen burayı geliştirebilirsin.
    fun saveNoteWithId(parentDir: File, title: String, content: String, id: String): File {
        return saveNote(parentDir, title, content)
    }
    // -----------------------------

    // 8. Update Note
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

    // Delete File (GÜNCELLENDİ: SES DOSYASINI DA SİLME)
    fun deleteFile(path: String) {
        val file = File(path)

        // 1. Asıl dosyayı sil (.md)
        if (file.exists()) file.deleteRecursively()

        // 2. Eğer bu bir not ise, yanında ses dosyası var mı bak ve sil (.m4a)
        if (file.isFile && file.extension.equals("md", ignoreCase = true)) {
            val audioPath = path.substringBeforeLast(".") + ".m4a"
            val audioFile = File(audioPath)
            if (audioFile.exists()) {
                audioFile.delete()
            }
        }
    }


    // Helper for unique names (File (1).md)
    private fun getUniqueFile(parentDir: File, desiredName: String): File {
        var file = File(parentDir, desiredName)
        val nameWithoutExt = desiredName.removeSuffix(".md")
        var counter = 1
        while (file.exists()) {
            val newName = "$nameWithoutExt ($counter).md"
            file = File(parentDir, newName)
            counter++
        }
        return file
    }

    fun getRootFolder(): File {
        return getPublicRoot()
    }

    // 9. RECURSIVE COUNT
    suspend fun getTotalNoteCount(): Int = withContext(Dispatchers.IO) {
        val publicCount = getPublicRoot().walk().filter { it.isFile && it.name.endsWith(".md") }.count()
        val privateCount = getPrivateRoot().walk().filter { it.isFile && it.name.endsWith(".md") }.count()
        return@withContext publicCount + privateCount
    }
}