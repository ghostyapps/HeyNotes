package com.ghostyapps.heynotes

import com.google.api.client.http.ByteArrayContent
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Collections

class DriveServiceHelper(private val mDriveService: Drive) {

    // 1. Initialize: Check for 'HeyNotes' folder, create if missing
    suspend fun getOrCreateRootFolder(): String? = withContext(Dispatchers.IO) {
        val query = "mimeType = 'application/vnd.google-apps.folder' and name = 'HeyNotes' and trashed = false"
        val result = mDriveService.files().list().setQ(query).execute()

        if (result.files.isNotEmpty()) {
            return@withContext result.files[0].id
        } else {
            // Create folder
            val metadata = File()
            metadata.name = "HeyNotes"
            metadata.mimeType = "application/vnd.google-apps.folder"

            val googleFile = mDriveService.files().create(metadata).execute()
            return@withContext googleFile.id
        }
    }

    // 2. Create a Note (Markdown file)
    // Changed return type from Unit to String?
    suspend fun createNote(folderId: String, title: String, content: String): String? = withContext(Dispatchers.IO) {
        val metadata = File()
        metadata.parents = Collections.singletonList(folderId)
        metadata.name = if (title.endsWith(".md")) title else "$title.md"
        metadata.mimeType = "text/markdown"

        val contentStream = ByteArrayContent.fromString("text/markdown", content)

        val file = mDriveService.files().create(metadata, contentStream).execute()
        return@withContext file.id // Return ID
    }

    // 3. Create a Sub-Folder
    suspend fun createFolder(parentId: String, name: String): String? = withContext(Dispatchers.IO) {
        val metadata = File()
        metadata.parents = Collections.singletonList(parentId)
        metadata.name = name
        metadata.mimeType = "application/vnd.google-apps.folder"

        val file = mDriveService.files().create(metadata).execute()
        return@withContext file.id
    }

    // 4. List Files in a specific folder
    // inside DriveServiceHelper.kt

    suspend fun listFiles(folderId: String): List<File> = withContext(Dispatchers.IO) {
        val query = "'$folderId' in parents and trashed = false"
        val result = mDriveService.files().list()
            .setQ(query)
            .setFields("files(id, name, mimeType, createdTime, modifiedTime)")
            .setOrderBy("folder, modifiedTime desc") // <--- Folders first, then Newest Modified
            .execute()
        return@withContext result.files
    }

    // 5. Read File Content
    suspend fun readFile(fileId: String): String = withContext(Dispatchers.IO) {
        val inputStream = mDriveService.files().get(fileId).executeMediaAsInputStream()
        val reader = BufferedReader(InputStreamReader(inputStream))
        val stringBuilder = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            stringBuilder.append(line).append("\n")
        }
        return@withContext stringBuilder.toString()
    }


    fun moveFile(fileId: String, folderId: String) {
        // 1. Mevcut ebeveyn klasörleri bul
        val file = mDriveService.files().get(fileId)
            .setFields("parents")
            .execute()

        val previousParents = StringBuilder()
        file.parents?.forEach { parent ->
            previousParents.append(parent)
            previousParents.append(',')
        }

        // 2. Yeni klasöre ekle, eskilerden çıkar
        mDriveService.files().update(fileId, null)
            .setAddParents(folderId)
            .setRemoveParents(previousParents.toString())
            .setFields("id, parents")
            .execute()
    }



    // Update an existing note's content and title
    suspend fun updateFile(fileId: String, newTitle: String, content: String) = withContext(Dispatchers.IO) {
        val metadata = File()
        metadata.name = if (newTitle.endsWith(".md")) newTitle else "$newTitle.md"

        val contentStream = ByteArrayContent.fromString("text/markdown", content)

        // The 'update' method handles both metadata (name) and media (content)
        mDriveService.files().update(fileId, metadata, contentStream).execute()
    }


    // DriveServiceHelper.kt dosyasını aç ve class içine şu fonksiyonu ekle:

    fun renameFile(fileId: String, newName: String) {
        try {
            val metadata = com.google.api.services.drive.model.File()
            metadata.name = newName

            // Drive API güncelleme isteği
            mDriveService.files().update(fileId, metadata).execute()
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    suspend fun deleteFile(fileId: String) = withContext(Dispatchers.IO) {
        try {
            mDriveService.files().delete(fileId).execute()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}