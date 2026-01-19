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

    // 1. Bir klasördeki tüm dosyaları listeler (DosyaAdı ve ID döner)
    suspend fun listFiles(folderId: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val filesList = mutableListOf<Pair<String, String>>()
        try {
            // Sadece silinmemiş (.md ve .m4a) dosyaları getir
            val query = "'$folderId' in parents and trashed = false"
            var pageToken: String? = null
            do {
                val result = mDriveService.files().list()
                    .setQ(query)
                    .setFields("nextPageToken, files(id, name)")
                    .setPageToken(pageToken)
                    .execute()

                for (file in result.files) {
                    filesList.add(Pair(file.id, file.name))
                }
                pageToken = result.nextPageToken
            } while (pageToken != null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext filesList
    }

    // 2. Drive'daki bir dosyanın içeriğini metin olarak okur
    suspend fun readFileContent(fileId: String): String? = withContext(Dispatchers.IO) {
        try {
            val inputStream = mDriveService.files().get(fileId).executeMediaAsInputStream()
            return@withContext inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    // 3. Ses dosyasını indir (Stream olarak)
    suspend fun downloadFile(fileId: String, targetFile: java.io.File) = withContext(Dispatchers.IO) {
        try {
            val outputStream = java.io.FileOutputStream(targetFile)
            mDriveService.files().get(fileId).executeMediaAndDownloadTo(outputStream)
            outputStream.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

// İsimden ve ebeveyn klasörden ID bulma (Kesme işareti korumalı)
    suspend fun findFileId(folderId: String, fileName: String): String? = withContext(Dispatchers.IO) {
        // Kesme işareti varsa sorguyu bozmasın diye kaçış karakteri ekliyoruz
        val safeFileName = fileName.replace("'", "\\'")
        val query = "'$folderId' in parents and name = '$safeFileName' and trashed = false"

        try {
            val result = mDriveService.files().list()
                .setQ(query)
                .setFields("files(id)")
                .execute()

            if (result.files.isNotEmpty()) {
                return@withContext result.files[0].id
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext null
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


    // Dosya Taşıma (Ebeveyn Değiştirme)
    suspend fun moveFile(fileId: String, newParentId: String) = withContext(Dispatchers.IO) {
        // 1. Mevcut ebeveynleri bul
        val file = mDriveService.files().get(fileId).setFields("parents").execute()
        val previousParents = StringBuilder()
        for (parent in file.parents) {
            previousParents.append(parent)
            previousParents.append(',')
        }

        // 2. Yeni ebeveyni ekle, eskileri çıkar
        mDriveService.files().update(fileId, null)
            .setAddParents(newParentId)
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

    // 6. Ses Dosyası Yükleme (MP4/M4A)
    suspend fun uploadFile(folderId: String, file: java.io.File, mimeType: String): String? = withContext(Dispatchers.IO) {
        val metadata = File()
            .setParents(Collections.singletonList(folderId))
            .setName(file.name)
            .setMimeType(mimeType)

        val fileContent = com.google.api.client.http.FileContent(mimeType, file)
        val uploadedFile = mDriveService.files().create(metadata, fileContent).execute()
        return@withContext uploadedFile.id
    }
}