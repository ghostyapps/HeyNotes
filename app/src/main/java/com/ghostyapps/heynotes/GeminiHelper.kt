package com.ghostyapps.heynotes

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object GeminiHelper {

    suspend fun transcribeAudio(userApiKey: String, audioFile: File): String? {
        return withContext(Dispatchers.IO) {
            try {
                val generativeModel = GenerativeModel(
                    modelName = "gemini-3-flash-preview",
                    apiKey = userApiKey
                )

                val inputContent = content {
                    text("""
                        Listen to this audio carefully.
                        
                        Instructions:
                        1. **Speaker Detection:** Detect if there are multiple speakers. If yes, label them as "Speaker 1:", "Speaker 2:", etc.
                        2. **Clean Verbatim:** Transcribe the content removing filler words (like 'um', 'uh', 'hmm', 'eee', 'ııı') and stuttering. Fix simple false starts.
                        3. **Formatting:** Add a blank line (double line break) between different speakers to separate them clearly.
                        4. **Language:** Detect the primary language of the audio.
                        5. **Title:** Generate a short, relevant title (max 5-6 words) IN THE SAME LANGUAGE AS THE AUDIO.
                        
                        CRITICAL: You must return the output in this EXACT format:
                        TITLE: [The Title]
                        BODY: [The Transcript]
                        
                        Do not add any other text.
                    """.trimIndent())

                    blob("audio/mp4", audioFile.readBytes())
                }

                val response = generativeModel.generateContent(inputContent)
                response.text
            } catch (e: Exception) {
                e.printStackTrace()
                val msg = e.message?.lowercase() ?: ""

                // HATA TİPLERİNİ AYIRT ETME
                when {
                    // Yanlış API Anahtarı (Genelde 400 döner ve 'api key' lafı geçer)
                    msg.contains("api key") || msg.contains("400") || msg.contains("invalid_argument") -> {
                        "Error: Invalid API Key"
                    }
                    // Kota Dolumu (Senin az önce aldığın hata)
                    msg.contains("quota") || msg.contains("429") -> {
                        "Error: Quota Exceeded"
                    }
                    // İnternet Yok veya Bağlantı Hatası
                    msg.contains("network") || msg.contains("connection") || msg.contains("host") -> {
                        "Error: Connection Failed"
                    }
                    // Diğer Hatalar
                    else -> "Error: ${e.message}"
                }
            }
        }
    }
}