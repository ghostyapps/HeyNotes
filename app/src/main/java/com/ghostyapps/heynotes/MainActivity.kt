package com.ghostyapps.heynotes

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    // Helpers
    private var driveServiceHelper: DriveServiceHelper? = null
    lateinit var localServiceHelper: LocalServiceHelper
    private lateinit var notesAdapter: NotesAdapter
    private lateinit var colorStorage: ColorStorage
    private lateinit var securityStorage: SecurityStorage

    // State
    private var isDriveMode = false
    private var isSelectionMode = false
    private var isGridMode = false
    private var userName: String = "User"

    // Data Holders
    private var currentNotes = mutableListOf<NoteItem>()
    private var currentFolders = mutableListOf<NoteItem>()

    // Navigation State
    private var currentLocalDir: File? = null
    private var currentDriveId: String? = null
    private var rootDriveId: String? = null
    private val driveBreadcrumbs = mutableListOf("Main")

    // UI Variables
    private lateinit var tvAppTitle: TextView
    private lateinit var tvGreeting: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var layoutFolderSelector: LinearLayout
    private lateinit var recyclerNotes: RecyclerView


    // Alt Aksiyon Butonları
    private lateinit var bottomActionContainer: LinearLayout

    // Selection Bar Variables
    private lateinit var selectionBar: MaterialCardView
    private lateinit var btnBulkMove: LinearLayout
    private lateinit var btnBulkDelete: LinearLayout

    // GEMINI UI
    private lateinit var geminiLoadingContainer: LinearLayout
    private lateinit var lottieGemini: LottieAnimationView
    private lateinit var tvGeminiStatus: TextView
    private lateinit var tvGeminiAction: TextView

    private val dateFormat = java.text.SimpleDateFormat("dd_MM_YY_HH_mm_ss", java.util.Locale.getDefault())

    // Result Launchers
    private val voiceNoteLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val path = result.data?.getStringExtra("AUDIO_PATH")
            if (path != null) {
                val file = File(path)
                val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                val isGeminiEnabled = prefs.getBoolean("gemini_enabled", false)

                if (isGeminiEnabled) {
                    // Gemini AÇIKSA: Animasyonu başlat ve işle
                    processGeminiAndNavigate(file)
                } else {
                    // Gemini KAPALIYSA: Direkt kaydet ve git
                    saveAndNavigateToVoiceFolder(file)
                }
            }
        }
    }

    private fun processGeminiAndNavigate(audioFile: File) {
        // 1. UI BAŞLATMA
        geminiLoadingContainer.visibility = View.VISIBLE
        geminiLoadingContainer.setOnClickListener(null)
        lottieGemini.playAnimation()

        // Durum Başlığı
        tvGeminiStatus.text = "Transcribing..."
        tvGeminiStatus.setTextColor(getColor(R.color.text_color))

        // Açıklama Metni (GÖRÜNÜR YAPILDI)
        tvGeminiAction.text = "AI is analyzing your voice..."
        tvGeminiAction.setTextColor(getColor(R.color.text_color_alt))
        tvGeminiAction.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            var finalTitle = ""
            var finalBody = ""
            var isError = false
            var uiErrorTitle = ""
            var uiErrorDescription = ""

            // --- GÜNCELLENMİŞ PROMPT ---
            // Dil kuralı ve temizlik kuralı güçlendirildi.
            val geminiPrompt = """
                Analyze this audio. 
                Return ONLY a raw JSON object (no markdown code blocks, no explanations) with exactly these two keys:
                "title": "A short, concise title (max 5 words) written in the SAME LANGUAGE as the spoken audio. It must capture the main topic.",
                "body": "Provide a full 'Clean Verbatim' transcription in the SAME LANGUAGE as the spoken audio. Remove filler words (like 'umm', 'ahh', 'ııı', 'eee'), stutters, and false starts. Do not summarize; write exactly what was said but make it readable. Format nicely with Markdown."
            """.trimIndent()

            try {
                val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                val apiKey = prefs.getString("gemini_api_key", "") ?: ""

                if (apiKey.isEmpty()) throw Exception("API Key is missing")

                val generativeModel = com.google.ai.client.generativeai.GenerativeModel(
                    modelName = "gemini-3-flash-preview",
                    apiKey = apiKey
                )

                val inputContent = com.google.ai.client.generativeai.type.content {
                    blob("audio/mp4", audioFile.readBytes())
                    text(geminiPrompt)
                }

                val response = generativeModel.generateContent(inputContent)
                val rawText = response.text ?: ""

                val jsonString = rawText.replace("```json", "").replace("```", "").trim()
                try {
                    val jsonObject = org.json.JSONObject(jsonString)
                    finalTitle = jsonObject.optString("title", "")
                    finalBody = jsonObject.optString("body", "")
                } catch (e: Exception) {
                    finalTitle = ""
                    finalBody = rawText
                }

            } catch (e: Exception) {
                isError = true
                val msg = e.localizedMessage ?: ""
                e.printStackTrace()

                // HATA TESPİTİ
                when {
                    msg.contains("API key", true) || msg.contains("403") -> {
                        uiErrorTitle = "Invalid API Key"
                        uiErrorDescription = "Please check your settings."
                    }
                    msg.contains("quota", true) || msg.contains("429") -> {
                        uiErrorTitle = "Quota Exceeded"
                        uiErrorDescription = "Limit reached. Try again later."
                    }
                    msg.contains("Connect", true) || msg.contains("UnknownHost") -> {
                        uiErrorTitle = "No Internet"
                        uiErrorDescription = "Check your connection."
                    }
                    msg.contains("missing", true) -> {
                        uiErrorTitle = "Missing API Key"
                        uiErrorDescription = "Enter Key in Settings."
                    }
                    msg.contains("found", true) || msg.contains("404") -> {
                        uiErrorTitle = "Model Unavailable"
                        uiErrorDescription = "Gemini-3 is not ready."
                    }
                    else -> {
                        uiErrorTitle = "Process Failed"
                        uiErrorDescription = "Error occurred."
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (isError) {
                        // --- HATA DURUMU ---
                        lottieGemini.pauseAnimation()

                        tvGeminiStatus.text = uiErrorTitle
                        tvGeminiStatus.setTextColor(getColor(R.color.text_color))

                        tvGeminiAction.text = "$uiErrorDescription\n\n(Tap anywhere to close)"
                        tvGeminiAction.setTextColor(getColor(R.color.text_color))
                        tvGeminiAction.visibility = View.VISIBLE

                        geminiLoadingContainer.setOnClickListener {
                            geminiLoadingContainer.visibility = View.GONE

                            val timestamp = java.text.SimpleDateFormat("yyyy.MM.dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                            val defaultTitle = "Voice Note $timestamp"
                            val errorBody = "(Transcription failed: $uiErrorDescription)"

                            saveAndNavigateToVoiceFolder(audioFile, defaultTitle, errorBody)
                        }

                    } else {
                        // --- BAŞARI DURUMU ---
                        geminiLoadingContainer.visibility = View.GONE

                        val finalTitleToSave = if (finalTitle.isNotBlank()) finalTitle else "Voice Note " + java.text.SimpleDateFormat("yyyy.MM.dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())

                        saveAndNavigateToVoiceFolder(audioFile, finalTitleToSave, finalBody)
                    }
                }
            }
        }
    }

    private val editorLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val originalId = data.getStringExtra("NOTE_ID")
            val shouldDelete = data.getBooleanExtra("REQUEST_DELETE", false)
            val title = data.getStringExtra("NOTE_TITLE") ?: "Untitled"
            val content = data.getStringExtra("NOTE_CONTENT") ?: ""
            val colorHex = data.getStringExtra("NOTE_COLOR")

            if (shouldDelete && originalId != null) {
                deleteSingleFile(originalId)
            } else {
                lifecycleScope.launch(Dispatchers.IO) {
                    // --- AKILLI KAYIT KONTROLÜ ---
                    // Eğer bu var olan bir not ise (originalId != null),
                    // İçeriğin gerçekten değişip değişmediğini kontrol et.
                    var hasChanged = true

                    if (originalId != null) {
                        try {
                            // 1. Dosyadaki mevcut içeriği oku
                            val oldContent = if (isDriveMode) {
                                driveServiceHelper?.readFile(originalId) ?: ""
                            } else {
                                localServiceHelper.readFile(originalId)
                            }

                            // 2. Listedeki eski başlığı bul
                            val oldNoteItem = currentNotes.find { it.id == originalId }
                            val oldTitleRaw = oldNoteItem?.name ?: ""

                            // Başlıkların sonundaki .md uzantılarını temizleyip karşılaştır
                            val cleanOldTitle = oldTitleRaw.removeSuffix(".md").trim()
                            val cleanNewTitle = title.removeSuffix(".md").trim()

                            // 3. İçerik ve Başlık Aynı mı?
                            if (oldContent == content && cleanOldTitle == cleanNewTitle) {
                                // İçerik ve başlık aynıysa, sadece renk değişmiş olabilir.
                                // Eğer renk de değişmediyse veya önemsizse KAYDETME.
                                // Renk kontrolünü de ekleyelim:
                                val oldColorInt = oldNoteItem?.color
                                val newColorInt = if (colorHex != null) Color.parseColor(colorHex) else oldColorInt

                                if (oldColorInt == newColorInt) {
                                    hasChanged = false
                                }
                            }
                        } catch (e: Exception) {
                            // Okuma hatası olursa güvenli tarafı seçip kaydedelim
                            hasChanged = true
                        }
                    }

                    // Sadece değişiklik varsa veya yeni bir not ise kaydet
                    if (hasChanged) {
                        saveNote(title, content, originalId, colorHex)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Tema ayarları
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val themeMode = prefs.getInt("theme_mode", androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(themeMode)

        super.onCreate(savedInstanceState)

        // Status bar ayarları
        window.statusBarColor = androidx.core.content.ContextCompat.getColor(this, R.color.background_color)
        val isNightMode = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        window.decorView.systemUiVisibility = if (!isNightMode) android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0

        // --- KRİTİK KONTROL BAŞLANGICI ---
        val userPrefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val isOnboardingDone = userPrefs.getBoolean("is_onboarding_done", false)

        if (!isOnboardingDone) {
            // Eğer onboarding yapılmadıysa WelcomeActivity'i aç
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
            return // <--- BU SATIR ÇOK ÖNEMLİ! Kodun aşağıya devam etmesini engeller.
        }
        // --- KRİTİK KONTROL BİTİŞİ ---

        setContentView(R.layout.activity_main)

        // --- DİNAMİK HESAPLAMA BAŞLIYOR ---

        // 1. XML'deki ana ekranı bul
        val rootLayout = findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.rootLayout)

        // 2. Sisteme sor: "Kenar boşlukları (Insets) ne kadar?"
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->

            // bars değişkeni, o anki telefonun üst ve alt çubuk ölçülerini alır
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())

            // 3. İŞTE BURADA UYGULUYORUZ:
            // view.paddingLeft   -> Sol aynen kalsın
            // bars.top           -> Üst boşluk = Telefonun Çentik Yüksekliği (Otomatik)
            // view.paddingRight  -> Sağ aynen kalsın
            // bars.bottom        -> Alt boşluk = Navigasyon çubuğu yüksekliği
            view.setPadding(view.paddingLeft, bars.top, view.paddingRight, bars.bottom)

            insets
        }
        // ----------------------------------

        handleWidgetIntent(intent)

        // Padding Fix
        val bottomContainer = findViewById<LinearLayout>(R.id.bottomActionContainer)
        val initialBottomPadding = bottomContainer.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(bottomContainer) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, initialBottomPadding + bars.bottom)
            insets
        }

        localServiceHelper = LocalServiceHelper(this)
        colorStorage = ColorStorage(this)
        securityStorage = SecurityStorage(this)
        currentLocalDir = localServiceHelper.getRootFolder()

        // checkOnboarding() çağrısını sildik çünkü yukarıda hallettik.
        setupUI()
        setupBottomActions()
        setupAdapters()
        setupBackNavigation()

        // İzin kontrolü (Sadece onboarding bittiyse çalışır)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }
    }


    override fun onResume() {
        super.onResume()
        val prefsUser = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        userName = prefsUser.getString("user_name", "User") ?: "User"

        val prefsSettings = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val savedGridMode = prefsSettings.getBoolean("grid_mode", false)
        if (isGridMode != savedGridMode) {
            isGridMode = savedGridMode
            updateLayoutManager()
        }

        if (::localServiceHelper.isInitialized) loadContent()
    }

    private fun checkOnboarding() {
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        if (prefs.getString("user_name", null) == null) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Intent'i güncelle
        handleWidgetIntent(intent)
    }

    // --- WIDGET ENTEGRASYONU ---

    // Widget'tan gelen isteği karşıla
// 1. handleWidgetIntent GÜNCEL HALİ
    private fun handleWidgetIntent(intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            "com.ghostyapps.heynotes.ACTION_QUICK_VOICE" -> {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    // Artık sınıf ismi dosya ismiyle aynı: VoiceRecorderDialog
                    val voiceIntent = Intent(this, VoiceRecorderDialog::class.java)
                    voiceNoteLauncher.launch(voiceIntent)
                }, 300)
            }
            "com.ghostyapps.heynotes.ACTION_QUICK_TEXT" -> {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    openQuickEditor()
                }, 300)
            }
        }
    }

    // 2. setupBottomActions İÇİNDEKİ SES BUTONU KISMI
    // (Bunu setupBottomActions fonksiyonunun içinde bulup değiştir)
    /* actionVoiceNote.setOnClickListener {
        if (isSelectionMode) return@setOnClickListener
        // Burada da VoiceRecorderDialog sınıfını çağırıyoruz
        val intent = Intent(this, VoiceRecorderDialog::class.java)
        voiceNoteLauncher.launch(intent)
    }
    */

    // Bu fonksiyonu Main Activity'ye ekle
    private fun showVoiceRecorderDialog() {
        if (checkPermissions()) {
            val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_voice_recorder, null)
            val dialog = AlertDialog.Builder(this).setView(dialogView).create()
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

            // --- BURAYA DİKKAT ---
            // dialog_voice_recorder içindeki kodlarını (Lottie, startRecording vb.)
            // buraya KOPYALAMALISIN.
            // (Mevcut btnVoiceNote.setOnClickListener içindeki her şeyi buraya al)

            dialog.show()
        } else {
            requestPermissions()
        }
    }
    private fun openQuickEditor() {
        val timestamp = dateFormat.format(java.util.Date())
        val intent = Intent(this, EditorActivity::class.java)

        // Hızlı not için isim ve yol
        val fileName = "Note $timestamp"
        intent.putExtra("FILE_NAME", fileName)
        intent.putExtra("FILE_PATH", File(localServiceHelper.getRootFolder(), "$fileName.md").absolutePath)

        // Editor'ü başlat
        editorLauncher.launch(intent)
    }

    private fun setupUI() {
        tvAppTitle = findViewById(R.id.tvAppTitle)
        tvGreeting = findViewById(R.id.tvGreeting)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        layoutFolderSelector = findViewById(R.id.layoutFolderSelector)

        bottomActionContainer = findViewById(R.id.bottomActionContainer)

        selectionBar = findViewById(R.id.selectionBar)
        btnBulkMove = findViewById(R.id.btnBulkMove)
        btnBulkDelete = findViewById(R.id.btnBulkDelete)

        geminiLoadingContainer = findViewById(R.id.geminiLoadingContainer)
        lottieGemini = findViewById(R.id.lottieGemini)
        tvGeminiStatus = findViewById(R.id.tvGeminiStatus)
        tvGeminiAction = findViewById(R.id.tvGeminiAction)

        findViewById<ImageView>(R.id.ivHeaderGraphic).setOnClickListener { showSettingsMenu(it) }

        layoutFolderSelector.setOnClickListener { showFolderSelectionDialog() }

        btnBulkMove.setOnClickListener { showBulkMoveDialog() }
        btnBulkDelete.setOnClickListener { showDeleteConfirmation() }
    }

    private fun setupBottomActions() {
        val actionNewNote = findViewById<View>(R.id.actionNewNote)
        val actionVoiceNote = findViewById<View>(R.id.actionVoiceNote) // Değişken adı bu
        val actionNewFolder = findViewById<View>(R.id.actionNewFolder)

        actionNewNote.setOnClickListener {
            if (isSelectionMode) return@setOnClickListener
            val intent = Intent(this, EditorActivity::class.java)
            val activeFolder = currentFolders.find { it.isActive }
            if (activeFolder != null && activeFolder.id != "ROOT" && activeFolder.id != "favorites") {
                intent.putExtra("FOLDER_ID", activeFolder.id)
            }
            editorLauncher.launch(intent)
        }

        // --- HATA BURADAYDI, DÜZELTİLDİ ---
        actionVoiceNote.setOnClickListener {
            if (isSelectionMode) return@setOnClickListener
            // Artık VoiceRecorderActivity yok, VoiceRecorderDialog var
            val intent = Intent(this, VoiceRecorderDialog::class.java)
            voiceNoteLauncher.launch(intent)
        }
        // ----------------------------------

        actionNewFolder.setOnClickListener {
            if (isSelectionMode) return@setOnClickListener
            showCreateFolderDialog()
        }
    }





    // Dosya isminde olmaması gereken karakterleri temizler
    private fun getSafeFileName(input: String): String {
        return input.replace(":", " -")
            .replace("/", "_")
            .replace("\\", "_")
            .trim()
    }

    /**
     * TEK VE NİHAİ KAYDETME FONKSİYONU
     * title = "" diyerek varsayılan değer atadık.
     * Böylece eski kod (satır 104) başlık göndermese bile burası çalışır.
     */
    private fun saveAndNavigateToVoiceFolder(audioFile: File, title: String = "", content: String = "") {
        // 1. Hedef Klasör: Documents/HeyNotes/Voice Notes
        val rootFolder = localServiceHelper.getRootFolder()
        val voiceFolder = java.io.File(rootFolder, "Voice Notes")

        if (!voiceFolder.exists()) {
            voiceFolder.mkdirs()
        }

        // 2. İsimlendirme (Gemini'dan gelen Title varsa onu kullan, yoksa Tarih)
        val timestamp = java.text.SimpleDateFormat("yyyy.MM.dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        val displayTitle = if (title.isNotEmpty()) title else "Voice Note $timestamp"

        // Dosya sistemi için güvenli isim yap
        val safeFileName = displayTitle.replace(Regex("[\\\\/:*?\"<>|#]"), "").trim()
        val finalFileName = if (safeFileName.isNotEmpty()) safeFileName else "Voice Note $timestamp"

        // 3. Dosyalar (.md ve .m4a)
        val finalNoteFile = java.io.File(voiceFolder, "$finalFileName.md") // ARTIK .md
        val finalAudioFile = java.io.File(voiceFolder, "$finalFileName.m4a")

        try {
            // 4. Ses Dosyasını Kopyala
            // Geçici dosyayı kalıcı yerine, yeni ismiyle taşıyoruz
            if (audioFile.exists()) {
                audioFile.copyTo(finalAudioFile, overwrite = true)
            }

            // 5. İçerik Oluşturma (Markdown Formatı)
            val noteContent = StringBuilder()

            // Eğer Gemini transkripsiyon yaptıysa metni ekle
            if (content.isNotEmpty()) {
                noteContent.append(content)
                noteContent.append("\n\n") // Metinden sonra boşluk bırak
            }

            // EN ÖNEMLİ KISIM: EditorActivity'nin sesi tanıması için bu etiketi ekliyoruz
            noteContent.append("Audio Note: ${finalAudioFile.name}")

            // 6. Dosyayı Yaz (.md olarak)
            java.io.FileWriter(finalNoteFile).use { it.write(noteContent.toString()) }

            runOnUiThread {
                Toast.makeText(this, "Saved to Voice Notes", Toast.LENGTH_SHORT).show()
                loadContent() // Listeyi yenile
            }

        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread {
                Toast.makeText(this, "Error saving note: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }


    // --- NAVİGASYON YARDIMCISI ---
    private fun navigateToFolderAndRefresh(folderFile: File, folderName: String) {
        // Hedef klasörü ayarla
        currentLocalDir = folderFile

        // Eğer Drive modundaysak çıkalım (Ses notları yerel çalışır)
        isDriveMode = false

        // Listeyi yükle
        loadContent()

    }


    private fun showGeminiLoading(initialMessage: String) {
        geminiLoadingContainer.bringToFront()
        geminiLoadingContainer.requestLayout()

        geminiLoadingContainer.visibility = View.VISIBLE
        geminiLoadingContainer.alpha = 0f
        geminiLoadingContainer.animate().alpha(1f).setDuration(300).start()
        tvGeminiStatus.text = initialMessage
        tvGeminiStatus.setTextColor(getColor(R.color.text_color))
        tvGeminiAction.visibility = View.GONE
        lottieGemini.playAnimation()
    }

    private fun updateGeminiStatus(newMessage: String) {
        runOnUiThread { tvGeminiStatus.text = newMessage }
    }

    private fun showGeminiError(errorMessage: String) {
        runOnUiThread {
            lottieGemini.pauseAnimation()
            tvGeminiStatus.text = errorMessage
            tvGeminiAction.visibility = View.VISIBLE
            val dismissAction = View.OnClickListener { hideGeminiLoading() }
            geminiLoadingContainer.setOnClickListener(dismissAction)
        }
    }

    private fun hideGeminiLoading() {
        runOnUiThread {
            lottieGemini.cancelAnimation()
            geminiLoadingContainer.animate().alpha(0f).setDuration(300).withEndAction {
                geminiLoadingContainer.visibility = View.GONE
            }.start()
        }
    }

    private fun toggleSelection(item: NoteItem) {
        item.isSelected = !item.isSelected
        checkSelectionState()
        notesAdapter.notifyDataSetChanged()
    }

    private fun checkSelectionState() {
        val anyNotes = currentNotes.any { it.isSelected }
        isSelectionMode = anyNotes
        updateSelectionModeUI()
    }

    private fun updateSelectionModeUI() {
        if (isSelectionMode) {
            if (selectionBar.visibility != View.VISIBLE) {
                selectionBar.visibility = View.VISIBLE
                selectionBar.alpha = 0f
                selectionBar.animate().alpha(1f).setDuration(200).start()
                bottomActionContainer.animate().alpha(0.3f).setDuration(200).start()
                bottomActionContainer.isClickable = false
            }
        } else {
            if (selectionBar.visibility == View.VISIBLE) {
                selectionBar.animate().alpha(0f).setDuration(200).withEndAction {
                    selectionBar.visibility = View.GONE
                }.start()
                bottomActionContainer.animate().alpha(1f).setDuration(200).start()
                bottomActionContainer.isClickable = true
            }
        }
    }

    private fun showDeleteConfirmation() {
        val notesToDelete = currentNotes.filter { it.isSelected }
        if (notesToDelete.isEmpty()) return
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_delete_confirm, null)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tvMessage)
        val btnDelete = dialogView.findViewById<TextView>(R.id.btnDelete)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btnCancel)
        tvMessage.text = "Delete ${notesToDelete.size} items?"
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        btnDelete.setOnClickListener { deleteSelectedItems(); dialog.dismiss() }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun deleteSelectedItems() {
        val notesToDelete = currentNotes.filter { it.isSelected }
        lifecycleScope.launch(Dispatchers.IO) {
            notesToDelete.forEach { item ->
                if (isDriveMode) driveServiceHelper?.deleteFile(item.id)
                else localServiceHelper.deleteFile(item.id)
            }
            withContext(Dispatchers.Main) { loadContent() }
        }
    }

    private fun showSingleDeleteConfirmation(item: NoteItem) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_delete_confirm, null)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tvMessage)
        val btnDelete = dialogView.findViewById<TextView>(R.id.btnDelete)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btnCancel)
        tvMessage.text = "Delete '${item.name}'?"
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        btnDelete.setOnClickListener {
            dialog.dismiss()
            if (item.isLocked) showPinDialogForSingleDeletion(item)
            else deleteSingleFile(item.id)
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }



    private fun showPinDialogForSingleDeletion(item: NoteItem) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_pin_entry, null)
        val btnConfirm = dialogView.findViewById<TextView>(R.id.btnConfirm)
        val etPin = dialogView.findViewById<EditText>(R.id.etPin)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btnCancel)
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        btnConfirm.setOnClickListener {
            if (securityStorage.checkPassword(item.id, etPin.text.toString())) {
                deleteSingleFile(item.id)
                dialog.dismiss()
            } else { Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show() }
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showCreateFolderDialog() {
        // XML'de checkbox olmasına gerek yok, kodla eklemeye de gerek yok.
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_create_folder, null)
        val etFolderName = dialogView.findViewById<EditText>(R.id.etFolderName)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btnCancel)
        val btnCreate = dialogView.findViewById<TextView>(R.id.btnCreate)
        val colorContainer = dialogView.findViewById<LinearLayout>(R.id.colorContainer)

        // --- RENK SEÇİMİ (Aynı kalıyor) ---
        val colors = listOf(
            Color.parseColor("#607D8B"), Color.parseColor("#EF5350"),
            Color.parseColor("#FFA726"), Color.parseColor("#FFEE58"),
            Color.parseColor("#66BB6A"), Color.parseColor("#42A5F5"),
            Color.parseColor("#AB47BC"), Color.parseColor("#EC407A"),
            Color.parseColor("#8D6E63")
        )
        var selectedColor = colors[0]
        val colorViews = mutableListOf<View>()
        for (colorInt in colors) {
            val colorView = View(this)
            val size = (24 * resources.displayMetrics.density).toInt()
            val margin = (6 * resources.displayMetrics.density).toInt()
            val params = LinearLayout.LayoutParams(size, size).apply { setMargins(margin, 0, margin, 0) }
            colorView.layoutParams = params
            val drawable = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(colorInt)
            }
            colorView.background = drawable
            colorView.setOnClickListener {
                selectedColor = colorInt
                updateColorBorders(colorViews, colors, selectedColor)
            }
            colorContainer.addView(colorView)
            colorViews.add(colorView)
        }
        updateColorBorders(colorViews, colors, selectedColor)

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnCreate.setOnClickListener {
            val folderName = etFolderName.text.toString().trim()
            if (folderName.isNotEmpty()) {
                // Sadece normal klasör oluşturuyoruz
                val colorHex = String.format("#%06X", (0xFFFFFF and selectedColor))
                createFolder(folderName, colorHex)
                dialog.dismiss()
            }
        }
        dialog.show()
    }


    // --- YARDIMCI: RENK ÇERÇEVESİ GÜNCELLEME ---
    private fun updateColorBorders(views: List<View>, colors: List<Int>, selected: Int) {
        for (i in views.indices) {
            val view = views[i]
            val color = colors[i]

            val drawable = android.graphics.drawable.GradientDrawable()
            drawable.shape = android.graphics.drawable.GradientDrawable.OVAL
            drawable.setColor(color)

            if (color == selected) {
                // SEÇİLİ İSE: Etrafına koyu gri belirgin bir çerçeve çiz (3dp kalınlık)
                val strokeColor = Color.parseColor("#424242")
                val strokeWidth = (3 * resources.displayMetrics.density).toInt()
                drawable.setStroke(strokeWidth, strokeColor)
            }

            view.background = drawable
        }
    }


    // GÜNCELLENMİŞ VERSİYON (Tek Parametre)
    private fun createLockedFolder(name: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val folderId: String?

            if (isDriveMode) {
                folderId = driveServiceHelper?.createFolder(currentDriveId ?: rootDriveId!!, name)
            } else {
                localServiceHelper.createFolder(currentLocalDir!!, name)
                val newFolder = File(currentLocalDir, name)
                folderId = newFolder.absolutePath
            }

            if (folderId != null) {
                // Şifre olarak sembolik "MASTER" kaydediyoruz.
                // Asıl kontrolü getMasterPin() ile yapacağız.
                securityStorage.setPassword(folderId, "MASTER")
                // Gizli klasör rengini Siyah yapıyoruz
                colorStorage.saveColor(folderId, "#000000")
            }

            withContext(Dispatchers.Main) {
                loadContent()
                Toast.makeText(this@MainActivity, "Secret Folder Created", Toast.LENGTH_SHORT).show()
            }
        }
    }


    private fun createFolder(name: String, colorHex: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (isDriveMode) {
                val newId = driveServiceHelper?.createFolder(rootDriveId!!, name)
                if (newId != null && colorHex != null) colorStorage.saveColor(newId, colorHex)
            } else {
                val rootDir = File(filesDir, "HeyNotes")
                if (!rootDir.exists()) rootDir.mkdirs()
                localServiceHelper.createFolder(rootDir, name)
                if (colorHex != null) {
                    val newFolder = File(rootDir, name)
                    colorStorage.saveColor(newFolder.absolutePath, colorHex)
                }
            }
            withContext(Dispatchers.Main) { loadContent() }
        }
    }

    private fun showColorPopup(item: NoteItem, anchorView: View) {
        val inflater = LayoutInflater.from(this)
        val popupView = inflater.inflate(R.layout.popup_color_picker, null)
        val container = popupView.findViewById<LinearLayout>(R.id.colorContainer)
        val popupWindow = PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popupWindow.elevation = 10f
        val colors = listOf("#BDBDBD", "#616161", "#EF5350", "#FFA726", "#FFEE58", "#66BB6A", "#42A5F5", "#AB47BC", "#EC407A")
        for (colorHex in colors) {
            val dot = View(this)
            val size = (24 * resources.displayMetrics.density).toInt()
            val params = LinearLayout.LayoutParams(size, size).apply { setMargins(10,0,10,0) }
            dot.layoutParams = params
            val bg = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.shape_circle)?.mutate()
            bg?.setTint(Color.parseColor(colorHex))
            dot.background = bg
            dot.setOnClickListener {
                colorStorage.saveColor(item.id, colorHex)
                popupWindow.dismiss()
                loadContent()
            }
            container.addView(dot)
        }
        popupWindow.showAsDropDown(anchorView, 0, -20)
    }

    // GÜNCELLENMİŞ KİLİT AÇMA (Master PIN Kontrolü)
    private fun showUnlockDialog(item: NoteItem) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_pin_entry, null)
        val btnConfirm = dialogView.findViewById<TextView>(R.id.btnConfirm)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btnCancel)
        val etPin = dialogView.findViewById<EditText>(R.id.etPin)

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnConfirm.setOnClickListener {
            val enteredPin = etPin.text.toString()
            val masterPin = getMasterPin()

            // DÜZELTME BURADA: Girilen PIN, Master PIN ile eşleşiyor mu?
            if (masterPin != null && enteredPin == masterPin) {
                dialog.dismiss()
                // Şifre Doğru -> İçeri Gir
                handleNavigation(item, forceUnlock = true)
            } else {
                Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show()
            }
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }


    private fun openEditor(item: NoteItem?) {
        val intent = Intent(this, EditorActivity::class.java)
        if (item != null) {
            intent.putExtra("NOTE_TITLE", item.name)
            intent.putExtra("NOTE_ID", item.id)
            intent.putExtra("NOTE_TIMESTAMP", item.timestamp)
            lifecycleScope.launch(Dispatchers.IO) {
                val content = if (!isDriveMode) localServiceHelper.readFile(item.id) else driveServiceHelper?.readFile(item.id) ?: ""
                withContext(Dispatchers.Main) {
                    intent.putExtra("NOTE_CONTENT", content)
                    editorLauncher.launch(intent)
                }
            }
        } else { editorLauncher.launch(intent) }
    }

    private fun deleteSingleFile(id: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (isDriveMode) driveServiceHelper?.deleteFile(id) else localServiceHelper.deleteFile(id)
            withContext(Dispatchers.Main) { loadContent() }
        }
    }

    private fun saveNote(title: String, content: String, originalId: String?, colorHex: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (isDriveMode) {
                if (originalId != null) {
                    driveServiceHelper?.updateFile(originalId, title, content)
                    if (colorHex != null) colorStorage.saveColor(originalId, colorHex)
                } else {
                    val newId = driveServiceHelper?.createNote(currentDriveId!!, title, content)
                    if (newId != null && colorHex != null) colorStorage.saveColor(newId, colorHex)
                }
            } else {
                if (originalId != null) {
                    // --- SES DOSYASI SENKRONİZASYONU ---
                    // Eğer başlık değiştiyse, bağlı olan ses dosyasını da taşı ve içeriği güncelle
                    var finalContent = content
                    val oldFile = File(originalId)
                    val oldTitle = oldFile.nameWithoutExtension
                    val cleanNewTitle = title.trim()

                    if (oldTitle != cleanNewTitle) {
                        val parentDir = oldFile.parentFile
                        val oldAudio = File(parentDir, "$oldTitle.m4a")

                        if (oldAudio.exists()) {
                            val newAudio = File(parentDir, "$cleanNewTitle.m4a")
                            // Ses dosyasını yeniden adlandır
                            if (oldAudio.renameTo(newAudio)) {
                                // Notun içindeki referans metnini güncelle
                                finalContent = finalContent.replace(
                                    "Audio Note: ${oldAudio.name}",
                                    "Audio Note: ${newAudio.name}"
                                )
                            }
                        }
                    }
                    // -----------------------------------

                    localServiceHelper.updateNote(originalId, title, finalContent)
                    if (colorHex != null) colorStorage.saveColor(originalId, colorHex)
                } else {
                    localServiceHelper.saveNote(currentLocalDir!!, title, content)
                    val safeTitle = if (title.endsWith(".md")) title else "$title.md"
                    val newPath = File(currentLocalDir, safeTitle).absolutePath
                    if (colorHex != null) colorStorage.saveColor(newPath, colorHex)
                }
            }
            withContext(Dispatchers.Main) { loadContent() }
        }
    }
    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isSelectionMode) {
                    isSelectionMode = false
                    currentNotes.forEach { it.isSelected = false }
                    notesAdapter.notifyDataSetChanged()
                    updateSelectionModeUI()
                    return
                }
                if (isDriveMode) {
                    if (driveBreadcrumbs.size > 1) {
                        currentDriveId = rootDriveId
                        driveBreadcrumbs.clear(); driveBreadcrumbs.add("Main")
                        loadContent()
                    } else { finish() }
                } else {
                    if (currentLocalDir != null && currentLocalDir!!.name != "HeyNotes") {
                        currentLocalDir = localServiceHelper.getRootFolder()
                        loadContent()
                    } else { finish() }
                }
            }
        })
    }





    private fun setupAdapters() {
        // SADECE NOT LİSTESİ (Yatay liste kodu silindi)
        recyclerNotes = findViewById(R.id.recyclerNotes)
        notesAdapter = NotesAdapter(
            onItemClick = { item -> if (isSelectionMode) toggleSelection(item) else openEditor(item) },
            onItemLongClick = { item, view -> if (!isSelectionMode) { isSelectionMode = true; toggleSelection(item) } else toggleSelection(item) },
            onIconClick = { item, view -> if (!isSelectionMode) showColorPopup(item, view) else toggleSelection(item) }
        )
        updateLayoutManager()
        recyclerNotes.adapter = notesAdapter
    }



    // --- KLASÖR SEÇİMİ DIALOG (GÜNCELLENDİ: HEPSİ BOLD + SEÇİLİ EFEKTİ) ---
// --- KLASÖR SEÇİMİ DIALOG (GÜNCELLENDİ: ÖZEL İKONLAR) ---
    private fun showFolderSelectionDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_folder_selection, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.rvFolderList)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_move_folder, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val folder = currentFolders[position]
                val tvName = holder.itemView.findViewById<TextView>(R.id.tvFolderName)
                val ivIcon = holder.itemView.findViewById<ImageView>(R.id.ivFolderIcon)
                val context = holder.itemView.context

                tvName.text = folder.name

                // --- 1. İKON SEÇİMİ (YENİ KISIM) ---
                val iconRes = when {
                    folder.id == "PRIVATE_ROOT" -> R.drawable.ic_lock_icon
                    folder.name.contains("Voice Note") -> R.drawable.ic_voice_icon
                    folder.id == "ROOT" -> R.drawable.ic_home_icon
                    else -> R.drawable.ic_folder_icon // Diğerleri için Klasör ikonu
                }
                ivIcon.setImageResource(iconRes)
                // -----------------------------------

                // 2. FONT: HEPSİ HER ZAMAN BOLD OLSUN
                tvName.alpha = 1f
                try {
                    tvName.typeface = ResourcesCompat.getFont(context, R.font.productsans_bold)
                } catch (e: Exception) {
                    tvName.setTypeface(null, Typeface.BOLD)
                }

                // 3. SEÇİLİ OLANIN ARKASINA "PILL" (HAP) EFEKTİ
                if (folder.isActive) {
                    val drawable = android.graphics.drawable.GradientDrawable()
                    drawable.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    drawable.cornerRadius = 100f

                    val baseColor = tvName.currentTextColor
                    val pillColor = androidx.core.graphics.ColorUtils.setAlphaComponent(baseColor, 40)
                    drawable.setColor(pillColor)

                    holder.itemView.background = drawable
                } else {
                    holder.itemView.background = null
                }

                // İkon Rengi
                if (folder.color != null) {
                    ivIcon.setColorFilter(folder.color!!)
                    ivIcon.alpha = 1f
                } else {
                    ivIcon.setColorFilter(Color.GRAY)
                    ivIcon.alpha = 0.7f
                }

                holder.itemView.setOnClickListener {
                    dialog.dismiss()
                    handleNavigation(folder)
                }

                holder.itemView.setOnLongClickListener {
                    // Root ve Voice Note klasörlerine dokunulmasın
                    if (folder.id != "ROOT" && !folder.name.contains("Voice Note") && folder.id != "PRIVATE_ROOT") {
                        dialog.dismiss()
                        showFolderOptions(folder)
                    }
                    true
                }
            }
            override fun getItemCount() = currentFolders.size
        }
        dialog.show()
    }

    // --- KLASÖR SEÇENEKLERİ POPUP ---
// --- KLASÖR SEÇENEKLERİ DIALOG ---
    // (Popup yerine Dialog kullanıyoruz)
    private fun showFolderOptions(folder: NoteItem) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.popup_folder_options, null)

        val btnRename = dialogView.findViewById<LinearLayout>(R.id.btnRename)
        val btnColor = dialogView.findViewById<LinearLayout>(R.id.btnColor)
        val btnDelete = dialogView.findViewById<LinearLayout>(R.id.btnDelete)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        // Arka planı şeffaf yapıyoruz ki bizim rounded background (background_popup) görünsün
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnRename.setOnClickListener {
            dialog.dismiss()
            showRenameFolderDialog(folder)
        }

        btnColor.setOnClickListener {
            dialog.dismiss()
            // Renk seçici popup olduğu için bir referans noktasına ihtiyaç duyar.
            // Klasör seçici butonunu (layoutFolderSelector) referans alabiliriz.
            showColorPopup(folder, layoutFolderSelector)
        }

        btnDelete.setOnClickListener {
            dialog.dismiss()
            showSingleDeleteConfirmation(folder)
        }

        dialog.show()
    }



    private fun showRenameFolderDialog(folder: NoteItem) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_rename_folder, null)
        val etFolderName = dialogView.findViewById<EditText>(R.id.etFolderName)
        val btnSave = dialogView.findViewById<TextView>(R.id.btnSave)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btnCancel)
        etFolderName.setText(folder.name)
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        btnSave.setOnClickListener {
            val newName = etFolderName.text.toString().trim()
            if (newName.isNotEmpty() && newName != folder.name) {
                performFolderRename(folder, newName)
                dialog.dismiss()
            }
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun performFolderRename(folder: NoteItem, newName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (isDriveMode) {
                    driveServiceHelper?.renameFile(folder.id, newName)
                } else {
                    val oldFile = File(folder.id)
                    val newFile = File(oldFile.parent, newName)
                    if (oldFile.renameTo(newFile)) {
                        val oldColor = colorStorage.getColor(oldFile.absolutePath)
                        if (oldColor != null) {
                            val hexColor = String.format("#%06X", (0xFFFFFF and oldColor))
                            colorStorage.saveColor(newFile.absolutePath, hexColor)
                        }
                    }
                }
                withContext(Dispatchers.Main) { loadContent() }
            } catch (e: Exception) { }
        }
    }

    private fun showBulkMoveDialog(itemsToMove: List<NoteItem>? = null) {
        val selectedNotes = itemsToMove ?: currentNotes.filter { it.isSelected }
        if (selectedNotes.isEmpty()) return

        // --- 1. LİSTEYİ SIFIRDAN OLUŞTUR (Root'tan Tara) ---
        val availableFolders = mutableListOf<NoteItem>()

        // A. "Main" (Ana Dizin) seçeneğini her zaman ekle
        availableFolders.add(NoteItem("Main", true, "ROOT"))

        // B. Root altındaki gerçek klasörleri tara
        // Nerede olursan ol, her zaman en üst dizindeki klasörleri bulur.
        if (!isDriveMode) {
            val rootDir = localServiceHelper.getRootFolder()
            val files = rootDir.listFiles()

            if (files != null) {
                // Sadece klasörleri filtrele, alfabetik sırala ve NoteItem'a çevir
                val realFolders = files.filter { it.isDirectory && !it.name.contains("Voice Note") }
                    .sortedBy { it.name }
                    .map { NoteItem(it.name, true, it.absolutePath) }

                availableFolders.addAll(realFolders)
            }
        } else {
            // Drive modundaysak ve o an root listesi elimizde yoksa,
            // mecburen mevcut listedeki klasörleri kullanırız (Drive async çalıştığı için burada bekletemiyoruz)
            // Ama genelde Drive modunda da currentFolders iş görür.
            val driveFolders = currentFolders.filter { !it.isActive && it.id != "ROOT" && !it.name.contains("Voice Note") }
            availableFolders.addAll(driveFolders)
        }

        // C. Filtreleme: Klasör kendisini kendi içine taşıyamaz
        // (Eğer bir klasör seçip taşı diyorsan, hedef listede kendisi olmamalı)
        val finalFolderList = availableFolders.filter { folder ->
            val isSelf = selectedNotes.any { it.id == folder.id }
            !isSelf
        }.toMutableList()

        // --- DIALOG KURULUMU ---
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_move_bottom_sheet, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.rvFolderList)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btnCancel)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_move_folder, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val folder = finalFolderList[position]
                val tvName = holder.itemView.findViewById<TextView>(R.id.tvFolderName)

                tvName.text = folder.name

                holder.itemView.setOnClickListener {
                    dialog.dismiss()
                    performBulkMove(selectedNotes, folder)
                }
            }

            override fun getItemCount() = finalFolderList.size
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun performBulkMove(notes: List<NoteItem>, targetFolder: NoteItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            notes.forEach { note ->
                try {
                    if (isDriveMode) {
                        val targetId = if (targetFolder.id == "ROOT") rootDriveId!! else targetFolder.id
                        driveServiceHelper?.moveFile(note.id, targetId)
                    } else {
                        val sourceFile = File(note.id)
                        val targetDir = if (targetFolder.id == "ROOT") localServiceHelper.getRootFolder() else File(targetFolder.id)
                        var destFile = File(targetDir, sourceFile.name)
                        var counter = 1
                        while (destFile.exists()) {
                            destFile = File(targetDir, "${sourceFile.nameWithoutExtension} ($counter).md")
                            counter++
                        }
                        sourceFile.copyTo(destFile, overwrite = true)
                        val audioSource = File(sourceFile.parent, "${sourceFile.nameWithoutExtension}.m4a")
                        if (audioSource.exists()) {
                            val audioDest = File(targetDir, destFile.nameWithoutExtension + ".m4a")
                            audioSource.copyTo(audioDest, overwrite = true)
                            audioSource.delete()
                        }
                        sourceFile.delete()
                    }
                } catch (e: Exception) { }
            }
            withContext(Dispatchers.Main) {
                isSelectionMode = false
                loadContent()
            }
        }
    }

    private fun updateLayoutManager() {
        notesAdapter.isGridMode = isGridMode
        recyclerNotes.layoutManager = if (isGridMode) GridLayoutManager(this, 2) else LinearLayoutManager(this)
        notesAdapter.notifyDataSetChanged()
    }

    // handleNavigation'ı bul ve başlığını şu şekilde değiştir:
    private fun handleNavigation(item: NoteItem, forceUnlock: Boolean = false) {
        // 1. ROOT
        if (item.id == "ROOT") {
            if (isDriveMode) { currentDriveId = rootDriveId; driveBreadcrumbs.clear(); driveBreadcrumbs.add("Main") }
            else { currentLocalDir = localServiceHelper.getRootFolder() }
            loadContent(); return
        }

        if (item.isActive) return

        // --- 2. PRIVATE FOLDER KONTROLÜ (YENİ) ---
        if (item.id == "PRIVATE_ROOT") {
            val masterPin = getMasterPin()

            if (masterPin == null) {
                // Durum A: Hiç PIN yok -> "Yeni PIN Oluştur" penceresi aç
                showSetMasterPinDialog {
                    // PIN başarıyla oluştu, şimdi içeri al
                    openPrivateFolder()
                }
            } else {
                // Durum B: PIN var -> "PIN Gir" penceresi aç
                showUnlockDialog {
                    // PIN doğru girildi, şimdi içeri al
                    openPrivateFolder()
                }
            }
            return // İşlemi burada kes, dialog sonucunu bekle
        }

        // 3. ESKİ KİLİTLİ KLASÖR KONTROLÜ (Varsa)
        if (item.isLocked && !forceUnlock) {
            showUnlockDialog(item) // NoteItem alan eski versiyon
            return
        }

        // 4. NORMAL YÖNLENDİRME
        if (isDriveMode) {
            driveBreadcrumbs.add(item.name)
            currentDriveId = item.id
        } else {
            currentLocalDir = java.io.File(item.id)
        }
        loadContent()
    }

    private fun loadContent() {
        isSelectionMode = false
        updateSelectionModeUI()
        val isRoot = (!isDriveMode && currentLocalDir?.name == "HeyNotes") || (isDriveMode && currentDriveId == rootDriveId)

        // --- Header Mantığı ---
        var currentFolderColor = Color.BLACK
        var currentFolderName = "Main"

        if (!isRoot) {
            val rawName = if (isDriveMode) driveBreadcrumbs.last() else currentLocalDir?.name ?: "Folder"
            currentFolderName = rawName.replace("!!", "").trim()
            val currentId = if (isDriveMode) currentDriveId else currentLocalDir?.absolutePath

            // --- GİZLİ KLASÖR KONTROLÜ ---
            val privateDir = java.io.File(getFilesDir(), "Private Notes")
            val isPrivate = !isDriveMode && currentLocalDir?.absolutePath == privateDir.absolutePath
            // -----------------------------

            if (currentId != null) {
                // Öncelik sırasına göre renk belirleme
                if (currentFolderName.contains("Voice Note")) {
                    currentFolderColor = Color.parseColor("#FF4B4B")
                } else if (isPrivate) {
                    currentFolderColor = Color.BLACK
                } else {
                    currentFolderColor = colorStorage.getColor(currentId) ?: Color.parseColor("#616161")
                }
            }
        } else {
            currentFolderName = if (isDriveMode) "Google Drive" else "Main"
        }

        tvAppTitle.text = "Hey, $userName"

        // Selamlama Mantığı (Saat dilimine göre)
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val greeting = when {
            hour < 12 -> "Good morning."
            hour < 17 -> "Good afternoon."
            hour < 21 -> "Good evening."
            else -> "Good night."
        }
        tvGreeting.text = greeting

        tvSubtitle.text = currentFolderName
        layoutFolderSelector.backgroundTintList = android.content.res.ColorStateList.valueOf(currentFolderColor)

        lifecycleScope.launch(Dispatchers.IO) {
            // --- Veri Çekme ---
            val allFoldersRaw: List<NoteItem> = if (isDriveMode) {
                driveServiceHelper?.listFiles(rootDriveId ?: "")?.filter { it.mimeType.contains("folder") }?.map { NoteItem(it.name, true, it.id) } ?: emptyList()
            } else {
                var rootDir = currentLocalDir
                // Root dizine kadar yukarı çıkma (Breadcrumb mantığı için)
                while (rootDir?.parentFile != null && rootDir.parentFile.name != "0" && rootDir.parentFile.name != "files") {
                    if (rootDir.name == "HeyNotes") break
                    rootDir = rootDir.parentFile
                }
                if (rootDir != null) localServiceHelper.listItems(rootDir).filter { it.isFolder } else emptyList()
            }

            val currentItemsRaw: List<NoteItem> = if (isDriveMode) {
                if (currentDriveId == null) emptyList() else driveServiceHelper?.listFiles(currentDriveId!!)?.map { NoteItem(it.name, it.mimeType.contains("folder"), it.id) } ?: emptyList()
            } else {
                if (currentLocalDir == null) emptyList() else localServiceHelper.listItems(currentLocalDir!!)
            }

            withContext(Dispatchers.Main) {
                // --- KLASÖR LİSTESİNİ HAZIRLA ---
                val tempFolderList = mutableListOf<NoteItem>()

                // 1. MAIN (Sabit)
                tempFolderList.add(NoteItem("Main", true, "ROOT", color = Color.BLACK, isActive = isRoot))

                // 2. PRIVATE FOLDER (Sabit - getFilesDir içinde)
                val privateDir = java.io.File(getFilesDir(), "Private Notes")
                val isPrivateActive = currentLocalDir?.absolutePath == privateDir.absolutePath

                tempFolderList.add(NoteItem(
                    name = "Private",
                    isFolder = true,
                    id = "PRIVATE_ROOT",
                    color = Color.BLACK,
                    isActive = isPrivateActive,
                    isLocked = true
                ))

                // 3. VOICE NOTES (Sabit - Documents/HeyNotes içinde)
                // Klasör yolunu ana kök dizinden alıyoruz
                val rootFolder = localServiceHelper.getRootFolder()
                val voiceDir = java.io.File(rootFolder, "Voice Notes")

                // Klasör yoksa oluştur (Böylece listede her zaman görünür)
                if (!voiceDir.exists()) {
                    voiceDir.mkdirs()
                }

                val isVoiceActive = currentLocalDir?.absolutePath == voiceDir.absolutePath

                tempFolderList.add(NoteItem(
                    name = "Voice Notes",
                    isFolder = true,
                    id = voiceDir.absolutePath,
                    color = Color.parseColor("#FF4B4B"),
                    isActive = isVoiceActive
                ))

                // 4. DİĞER KLASÖRLER (Filtreleme)
                val processedFolders = allFoldersRaw.map { folder ->
                    val currentPath = if (isDriveMode) currentDriveId else currentLocalDir?.absolutePath
                    val isThisFolderActive = !isRoot && folder.id == currentPath
                    val isLocked = securityStorage.isLocked(folder.id)
                    folder.copy(
                        name = folder.name.replace("!!", ""),
                        // Renk: Voice Note ise kırmızı, diğerleri kayıtlı renk veya gri
                        color = if (folder.name.contains("Voice Note")) Color.parseColor("#FF4B4B") else (colorStorage.getColor(folder.id) ?: Color.parseColor("#616161")),
                        isActive = isThisFolderActive,
                        isLocked = isLocked
                    )
                }

                // Listeyi temizle: Private ve Voice Notes zaten yukarıda manuel eklendi, tekrar gelmesin
                val otherFolders = processedFolders.filter {
                    !it.name.contains("Voice Note") && it.name != "Private Notes"
                }.sortedBy { it.name }

                tempFolderList.addAll(otherFolders)

                // Notları Hazırla
                val notesWithColors = currentItemsRaw.filter { !it.isFolder }.map { note ->
                    note.copy(name = note.name.replace("!!", ""), color = colorStorage.getColor(note.id))
                }

                // Listeleri Güncelle
                currentFolders = tempFolderList
                currentNotes = notesWithColors.toMutableList()

                notesAdapter.submitList(currentNotes)
            }
        }
    }


    private fun showSettingsMenu(anchorView: View) {
        val inflater = LayoutInflater.from(this)
        val popupView = inflater.inflate(R.layout.popup_settings_menu, null)
        val popupWindow = PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popupWindow.elevation = 10f
        val btnSystem = popupView.findViewById<TextView>(R.id.menuThemeSystem)
        val btnLight = popupView.findViewById<TextView>(R.id.menuThemeLight)
        val btnDark = popupView.findViewById<TextView>(R.id.menuThemeDark)
        val btnSettings = popupView.findViewById<TextView>(R.id.menuSettings)
        val btnAbout = popupView.findViewById<TextView>(R.id.menuAbout)
        btnSystem.setOnClickListener { popupWindow.dismiss(); updateTheme(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) }
        btnLight.setOnClickListener { popupWindow.dismiss(); updateTheme(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO) }
        btnDark.setOnClickListener { popupWindow.dismiss(); updateTheme(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) }
        btnSettings.setOnClickListener { popupWindow.dismiss(); startActivity(Intent(this, SettingsActivity::class.java)) }
        btnAbout.setOnClickListener { popupWindow.dismiss(); startActivity(Intent(this, AboutActivity::class.java)) }
        popupWindow.showAsDropDown(anchorView, 0, 0)
    }

    private fun updateTheme(mode: Int) {
        getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit().putInt("theme_mode", mode).apply()
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
    }

    // --- İZİN YÖNETİMİ ---

    private fun checkPermissions(): Boolean {
        // 1. Ses Kayıt İzni Var mı?
        val recordAudio = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        // 2. Depolama İzni Var mı?
        // Android 11 (R) ve üzeri için "Tüm Dosyalara Erişim" kontrolü
        val storage = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // Android 10 ve altı için standart yazma izni
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        return recordAudio && storage
    }

    private fun requestPermissions() {
        // Ses Kaydı İzni İste
        val permissions = mutableListOf(android.Manifest.permission.RECORD_AUDIO)

        // Android 10 ve altı için Depolama iznini listeye ekle
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            permissions.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        // İzin Penceresini Aç
        androidx.core.app.ActivityCompat.requestPermissions(
            this, permissions.toTypedArray(), 101
        )

        // Android 11 ve üzeri için Özel Depolama İzni Ekranına Yönlendir
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.addCategory("android.intent.category.DEFAULT")
                    intent.data = Uri.parse(String.format("package:%s", applicationContext.packageName))
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent()
                    intent.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                    startActivity(intent)
                }
                Toast.makeText(this, "Please allow 'All files access' to save notes.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Kullanıcı izin verirse ne olacağını yönet (Opsiyonel ama iyi olur)
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
                // İzinler verildi, kullanıcı tekrar basarsa çalışacak
                Toast.makeText(this, "Permissions granted. Tap again to record.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permissions are required to record audio.", Toast.LENGTH_SHORT).show()
            }
        }
    }


    // --- MASTER PIN YÖNETİMİ ---

    // --- MASTER PIN YÖNETİMİ (YENİ) ---


    // Özel klasöre giriş yapan yardımcı fonksiyon
// Özel klasöre giren yardımcı fonksiyon
    private fun openPrivateFolder() {
        val privateDir = java.io.File(getFilesDir(), "Private Notes")
        if (!privateDir.exists()) privateDir.mkdirs()
        currentLocalDir = privateDir
        loadContent()
    }

    // Şifre sorma diyaloğu (Basitleştirilmiş)
    // onSuccess: Şifre doğru girilince çalışacak kod bloğu
    private fun showUnlockDialog(onSuccess: () -> Unit) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_pin_entry, null)
        val etPin = dialogView.findViewById<EditText>(R.id.etPin)
        val btnConfirm = dialogView.findViewById<TextView>(R.id.btnConfirm)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btnCancel)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvTitle)

        tvTitle?.text = "Private Notes Locked"
        etPin.hint = "Enter PIN"

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnConfirm.setOnClickListener {
            val entered = etPin.text.toString()
            if (entered == getMasterPin()) {
                dialog.dismiss()
                onSuccess() // Şifre doğru, işlemi yap
            } else {
                Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show()
            }
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }


    private fun getMasterPin(): String? {
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        return prefs.getString("master_pin", null)
    }

    private fun saveMasterPin(pin: String) {
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("master_pin", pin).apply()
    }

    private fun showSetMasterPinDialog(onSuccess: () -> Unit) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_pin_entry, null)
        val etPin = dialogView.findViewById<EditText>(R.id.etPin)
        val btnConfirm = dialogView.findViewById<TextView>(R.id.btnConfirm)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btnCancel)

        // Başlığı kodla değiştiriyoruz ki "Enter PIN" yerine "Set PIN" olduğu anlaşılsın
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvTitle)
        tvTitle?.text = "Set Master PIN"
        etPin.hint = "Create a 4-digit PIN"

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnConfirm.setOnClickListener {
            val pin = etPin.text.toString()
            if (pin.length >= 4) {
                saveMasterPin(pin)
                Toast.makeText(this, "Master PIN Set!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                onSuccess()
            } else {
                Toast.makeText(this, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show()
            }
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}