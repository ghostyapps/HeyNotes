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

    // YENİ EKLENECEK:
    private lateinit var titleStorage: TitleStorage

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


    // Search UI
    private lateinit var etSearch: EditText
    private lateinit var ivSearchIcon: ImageView
    private lateinit var ivCloseSearch: ImageView
    private var isSearching = false // Arama modunda mıyız?

    private lateinit var searchContainer: LinearLayout
    private lateinit var tvSearchLabel: TextView


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
        geminiLoadingContainer.alpha = 1f
        geminiLoadingContainer.setOnClickListener(null)
        lottieGemini.playAnimation()

        tvGeminiStatus.text = "Transcribing..."
        tvGeminiStatus.setTextColor(getColor(R.color.text_color))

        tvGeminiAction.text = "AI is analyzing your voice..."
        tvGeminiAction.setTextColor(getColor(R.color.text_color_alt))
        tvGeminiAction.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            var finalTitle = ""
            var finalBody = ""
            var isError = false
            var uiErrorTitle = ""
            var uiErrorDescription = ""

            // --- JSON İSTEYEN GARANTİ PROMPT ---
            val geminiPrompt = """
                You are a professional transcriber. 
                
                INSTRUCTIONS:
                1. **Language:** Detect the language of the audio. Output the text in the SAME language.
                2. **Format:** You MUST return a valid JSON object. Do not include any explanation or markdown formatting outside the JSON.
                
                The JSON structure must be exactly:
                {
                  "title": "A short, concise summary title (max 5 words)",
                  "body": "The full transcription content here..."
                }
                
                3. **Transcription Rules for the 'body' field:**
                   - **Speaker ID:** If multiple people are speaking, label them (e.g., **Speaker 1:**).
                   - **Paragraphs:** Use '\n\n' to separate speakers or paragraphs.
                   - **Cleaning:** Use 'Clean Verbatim'. Remove filler words (umm, ahh, err, ııı, eee).
                   - **Markdown:** You can use Markdown syntax (like **bold**) INSIDE the JSON string value, but ensure it is properly escaped for JSON.
            """.trimIndent()

            try {
                val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                val apiKey = prefs.getString("gemini_api_key", "") ?: ""

                if (apiKey.isEmpty()) throw Exception("API Key is missing")

                val generativeModel = com.google.ai.client.generativeai.GenerativeModel(
                    modelName = "gemini-3-flash-preview",
                    apiKey = apiKey,
                    // JSON çıktısını garantilemek için responseMimeType ayarı (Gemini 1.5 ve üzeri destekler)
                    // generationConfig = com.google.ai.client.generativeai.type.generationConfig { responseMimeType = "application/json" }
                    // Not: Eğer kütüphane sürümün eskiyse generationConfig satırını sil, prompt yeterli olur.
                )

                val inputContent = com.google.ai.client.generativeai.type.content {
                    blob("audio/mp4", audioFile.readBytes())
                    text(geminiPrompt)
                }

                val response = generativeModel.generateContent(inputContent)
                val rawText = response.text ?: ""

                // --- JSON TEMİZLİK VE PARSING ---
                // Gemini bazen ```json ... ``` blokları ekler, onları temizliyoruz.
                val cleanJson = rawText.replace("```json", "").replace("```", "").trim()

                try {
                    val jsonObject = org.json.JSONObject(cleanJson)
                    finalTitle = jsonObject.optString("title", "")
                    finalBody = jsonObject.optString("body", "")
                } catch (e: Exception) {
                    // JSON patlarsa (çok nadir), metni olduğu gibi gövdeye at
                    finalTitle = ""
                    finalBody = rawText // Ham metni kaybetmeyelim
                }

            } catch (e: Exception) {
                isError = true
                val msg = e.localizedMessage ?: ""
                e.printStackTrace()

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
                        tvGeminiAction.text = "$uiErrorDescription\n\n(Tap to save without AI)"
                        tvGeminiAction.visibility = View.VISIBLE

                        geminiLoadingContainer.setOnClickListener {
                            geminiLoadingContainer.visibility = View.GONE
                            val timestamp = java.text.SimpleDateFormat("yyyy.MM.dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                            val defaultTitle = "Voice Note $timestamp"
                            val errorBody = "(Transcription failed: $uiErrorDescription)"

                            // Hata durumunda (fromGemini = false -> Animasyon hemen kapanır)
                            saveAndNavigateToVoiceFolder(audioFile, defaultTitle, errorBody, fromGemini = false)
                        }

                    } else {
                        // --- BAŞARI (JSON BAŞARIYLA AYRIŞTIRILDI) ---
                        tvGeminiStatus.text = "Formatting note..."
                        tvGeminiAction.text = "Almost done."

                        // Eğer başlık boşsa tarih ata
                        val safeTitle = if (finalTitle.isNotBlank()) {
                            finalTitle.replace(Regex("[\\\\/:*?\"<>|#]"), "").take(50)
                        } else {
                            "Voice Note " + java.text.SimpleDateFormat("yyyy.MM.dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                        }

                        // Kayıt fonksiyonuna gönder (fromGemini = true -> İşlem bitince animasyon kapanır)
                        saveAndNavigateToVoiceFolder(audioFile, safeTitle, finalBody, fromGemini = true)
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
                    // --- AKILLI DEĞİŞİKLİK KONTROLÜ ---
                    var contentOrTitleChanged = true
                    var colorChanged = false

                    if (originalId != null) {
                        try {
                            // 1. Dosyadaki mevcut metin içeriğini oku
                            val oldContent = if (isDriveMode) {
                                driveServiceHelper?.readFile(originalId) ?: ""
                            } else {
                                localServiceHelper.readFile(originalId)
                            }

                            // 2. Listedeki eski başlığı ve rengi al
                            val oldNoteItem = currentNotes.find { it.id == originalId }
                            val oldTitleRaw = oldNoteItem?.name ?: ""

                            // Başlıkların sonundaki .md uzantılarını temizleyip karşılaştır
                            val cleanOldTitle = oldTitleRaw.removeSuffix(".md").trim()
                            val cleanNewTitle = title.removeSuffix(".md").trim()

                            // 3. İçerik ve Başlık Aynı mı?
                            if (oldContent == content && cleanOldTitle == cleanNewTitle) {
                                contentOrTitleChanged = false
                            }

                            // 4. Renk Değişti mi?
                            val oldColorInt = oldNoteItem?.color
                            if (colorHex != null) {
                                val newColorInt = Color.parseColor(colorHex)
                                if (oldColorInt != newColorInt) {
                                    colorChanged = true
                                }
                            }

                        } catch (e: Exception) {
                            // Okuma hatası olursa güvenli tarafı seçip normal kayıt yapalım
                            contentOrTitleChanged = true
                        }
                    }

                    // --- KARAR VE KAYIT ---
                    if (contentOrTitleChanged) {
                        // A) İçerik veya Başlık değişti:
                        // Normal kayıt fonksiyonunu çağır (Dosya güncellenir, Tarih DEĞİŞİR)
                        saveNote(title, content, originalId, colorHex)
                    } else if (colorChanged && originalId != null && colorHex != null) {
                        // B) Sadece Renk değişti:
                        // Dosyaya DOKUNMA (Böylece tarih değişmez), sadece rengi kaydet.
                        colorStorage.saveColor(originalId, colorHex)

                        // Arayüzü yenile
                        withContext(Dispatchers.Main) { loadContent() }
                    }
                    // C) Hiçbir şey değişmediyse hiçbir şey yapma.
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
        titleStorage = TitleStorage(this)

        // checkOnboarding() çağrısını sildik çünkü yukarıda hallettik.
        setupUI()
        setupBottomActions()
        setupAdapters()
        setupBackNavigation()
        checkDriveLogin()

        // İzin kontrolü (Sadece onboarding bittiyse çalışır)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }
    }


    private fun checkDriveLogin() {
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("drive_sync_enabled", false)

        if (isEnabled) {
            val account = com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(this)
            if (account != null) {
                val credential = com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential.usingOAuth2(
                    this, java.util.Collections.singleton(com.google.api.services.drive.DriveScopes.DRIVE_FILE)
                )
                credential.selectedAccount = account.account

                val googleDriveService = com.google.api.services.drive.Drive.Builder(
                    com.google.api.client.http.javanet.NetHttpTransport(),
                    com.google.api.client.json.gson.GsonFactory(),
                    credential
                ).setApplicationName("HeyNotes").build()

                driveServiceHelper = DriveServiceHelper(googleDriveService)

                // Drive modunu başlat
                isDriveMode = true
                initializeDriveStructure()
            }
        } else {
            isDriveMode = false
        }
    }

    private fun initializeDriveStructure() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val folderId = driveServiceHelper?.getOrCreateRootFolder()

                if (folderId != null) {
                    rootDriveId = folderId
                    currentDriveId = folderId

                    withContext(Dispatchers.Main) {
                        loadContent()

                        // --- DEĞİŞİKLİK BURADA: Soru sorma, direkt kontrol et ve eşitle ---
                        checkAndSyncBackground()
                        // -----------------------------------------------------------------
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isDriveMode = false
                    // Hata mesajını sessize alabiliriz veya loglayabiliriz
                    // Toast.makeText(this@MainActivity, "Drive Init Error...", ...).show()
                    loadContent()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val shouldBeDrive = prefs.getBoolean("drive_sync_enabled", false)

        // Eğer ayarlardaki durum ile şu anki mod uyuşmuyorsa, sayfayı tamamen yenile
        if (isDriveMode != shouldBeDrive) {
            val intent = Intent(this, MainActivity::class.java)
            finish()
            startActivity(intent)
            return
        }

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

        // --- DEĞİŞİKLİK BURADA ---
        // Eski ikon satırını sildik: findViewById<ImageView>(R.id.ivHeaderGraphic)...
        // Yerine başlık alanına tıklama özelliği ekledik:
        findViewById<LinearLayout>(R.id.headerTextContainer).setOnClickListener { showSettingsMenu(it) }
        // -------------------------

        layoutFolderSelector.setOnClickListener { showNavigationDialog() }
// Artık navigasyon dialogunu çağırıyoruz.
        // Örnek: Selection Mode menüsündeki Move ikonuna tıklandığında
        btnBulkMove.setOnClickListener {
            showBulkMoveDialog()
        }
        btnBulkDelete.setOnClickListener { showDeleteConfirmation() }

        // Search UI Tanımları
        searchContainer = findViewById(R.id.searchContainer) // Container'ı tanımla
        tvSearchLabel = findViewById(R.id.tvSearchLabel) // Label'ı tanımla
        // ... diğer tanımlamalar (etSearch, ivSearchIcon, ivCloseSearch) aynı kalıyor
        etSearch = findViewById(R.id.etSearch)
        ivSearchIcon = findViewById(R.id.ivSearchIcon)
        ivCloseSearch = findViewById(R.id.ivCloseSearch)

        setupSearchLogic()
    }

    private fun setupBottomActions() {
        val actionNewNote = findViewById<View>(R.id.actionNewNote)
        val actionVoiceNote = findViewById<View>(R.id.actionVoiceNote) // Değişken adı bu
        val actionNewFolder = findViewById<View>(R.id.actionNewFolder)

        actionNewNote.setOnClickListener {
            if (isSelectionMode) return@setOnClickListener
            val intent = Intent(this, EditorActivity::class.java)
            val activeFolder = currentFolders.find { it.isActive }

            // Eğer klasör ROOT değilse, favorites değilse VE Voice Notes değilse klasör ID'sini gönder.
            // (Voice Notes ise ID göndermez, böylece not Main'e düşer)
            if (activeFolder != null &&
                activeFolder.id != "ROOT" &&
                activeFolder.id != "favorites" &&
                !activeFolder.name.contains("Voice Note")) {

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



    // ------------------------------------------------------------------------
    // YENİ VE DÜZELTİLMİŞ showCreateFolderDialog
    // ------------------------------------------------------------------------
    private fun showCreateFolderDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_create_folder, null)
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val etFolderName = dialogView.findViewById<EditText>(R.id.etFolderName)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btnCancel)
        val btnCreate = dialogView.findViewById<TextView>(R.id.btnCreate)
        val colorContainer = dialogView.findViewById<LinearLayout>(R.id.colorContainer)

        var selectedColor = "#FFFFFF"
        val colors = ColorStorage.colors

        for (colorHex in colors) {
            val dot = View(this)
            val size = (32 * resources.displayMetrics.density).toInt()
            val margin = (4 * resources.displayMetrics.density).toInt()

            val params = LinearLayout.LayoutParams(size, size).apply {
                setMargins(margin, 0, margin, 0)
            }
            dot.layoutParams = params

            val bg = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.shape_circle)?.mutate() as? android.graphics.drawable.GradientDrawable

            try {
                bg?.setColor(Color.parseColor(colorHex))
                // Beyaz renk kaybolmasın diye ince gri çerçeve (Aynen koruyoruz)
                if (colorHex.equals("#FFFFFF", ignoreCase = true)) {
                    val strokeColor = Color.parseColor("#E0E0E0")
                    val strokeWidth = (1 * resources.displayMetrics.density).toInt()
                    bg?.setStroke(strokeWidth, strokeColor)
                } else {
                    bg?.setStroke(0, 0)
                }
            } catch (e: Exception) {
                bg?.setColor(Color.LTGRAY)
            }
            dot.background = bg

            // --- DEĞİŞİKLİK BURADA: OPAKLIĞI KALDIRDIK ---
            dot.alpha = 1.0f // Artık hepsi %100 canlı görünecek

            // Seçim durumu: Sadece BOYUT farkı ile gösteriyoruz
            if (colorHex == selectedColor) {
                dot.scaleX = 1.2f // Seçili olan biraz daha büyük
                dot.scaleY = 1.2f
            } else {
                dot.scaleX = 1.0f
                dot.scaleY = 1.0f
            }

            dot.setOnClickListener {
                selectedColor = colorHex
                // Animasyon: Tüm topların boyutunu normale döndür (Alpha ile oynamıyoruz)
                for (i in 0 until colorContainer.childCount) {
                    val child = colorContainer.getChildAt(i)
                    child.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                }
                // Tıklananı büyüt
                dot.animate().scaleX(1.2f).scaleY(1.2f).setDuration(200).start()
            }

            colorContainer.addView(dot)
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnCreate.setOnClickListener {
            val folderName = etFolderName.text.toString().trim()
            if (folderName.isNotEmpty()) {
                createNewFolder(folderName, selectedColor)
                dialog.dismiss()
            } else {
                Toast.makeText(this, "Enter a folder name", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }
    // ------------------------------------------------------------------------
    // EKSİK OLAN createNewFolder FONKSİYONU
    // ------------------------------------------------------------------------
    private fun createNewFolder(folderName: String, colorHex: String) {
        val rootFolder = localServiceHelper.getRootFolder()
        val newFolder = java.io.File(rootFolder, folderName)

        if (!newFolder.exists()) {
            val created = newFolder.mkdirs()
            if (created) {
                // Rengi klasörün tam yolu (path) ile kaydediyoruz
                colorStorage.saveColor(newFolder.absolutePath, colorHex)

                // Listeyi yeniliyoruz
                loadContent()

                Toast.makeText(this, "Folder created", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Error creating folder", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Folder already exists", Toast.LENGTH_SHORT).show()
        }
    }


    private fun getSafeFileName(input: String): String {
        // \ / : * ? " < > | karakterlerini _ ile değiştirir
        return input.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    }

    /**
     * TEK VE NİHAİ KAYDETME FONKSİYONU
     * title = "" diyerek varsayılan değer atadık.
     * Böylece eski kod (satır 104) başlık göndermese bile burası çalışır.
     */
    /**
     * GÜNCELLENMİŞ VERSİYON:
     * - Ses dosyasını Drive'a yükler
     * - Rastgele renk verir
     * - Hafızaya (History) kaydeder
     * - Otomatik olarak Voice Notes klasörünü açar
     */
    /**
     * GÜNCELLENMİŞ VERSİYON:
     * - "fromGemini" parametresi eklendi.
     * - Eğer Gemini'den geldiyse, kayıt işlemi bitene kadar animasyon dönmeye devam eder.
     * - Kayıt bitince animasyon kapanır ve klasör açılır.
     */
    private fun saveAndNavigateToVoiceFolder(
        audioFile: File,
        title: String = "",
        content: String = "",
        fromGemini: Boolean = false // Varsayılan değer false (Normal kayıt için)
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            // ... (Klasör hazırlığı, Dosya kopyalama, Renk atama, Drive işlemleri) ...
            // ... (Bu kısımlar önceki kodunuzla AYNI) ...

            // 1. Klasör Hazırlığı
            val rootFolder = localServiceHelper.getRootFolder()
            val voiceFolder = java.io.File(rootFolder, "Voice Notes")
            if (!voiceFolder.exists()) voiceFolder.mkdirs()

            // 2. İsimlendirme ve Dosya Oluşturma (Aynı)
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
            val displayTitle = if (title.isNotEmpty()) title else "Voice Note $timestamp"
            val safeFileName = displayTitle.replace(Regex("[\\\\/:*?\"<>|#]"), "").trim()
            val finalFileName = if (safeFileName.isNotEmpty()) safeFileName else "Voice Note $timestamp"

            val finalNoteFile = java.io.File(voiceFolder, "$finalFileName.md")
            val finalAudioFile = java.io.File(voiceFolder, "$finalFileName.m4a")

            try {
                // 3. Dosyaları Kaydet (Aynı)
                if (audioFile.exists()) {
                    audioFile.copyTo(finalAudioFile, overwrite = true)
                }

                val noteContent = StringBuilder()
                if (content.isNotEmpty()) noteContent.append(content).append("\n\n")
                noteContent.append("Audio Note: ${finalAudioFile.name}")
                java.io.FileWriter(finalNoteFile).use { it.write(noteContent.toString()) }

                // 4. Renk ve Drive (Aynı)
                val randomColor = ColorStorage.colors.random()
                colorStorage.saveColor(finalNoteFile.absolutePath, randomColor)

                if (isDriveMode && driveServiceHelper != null && rootDriveId != null) {
                    // ... Drive kodları aynı ...
                    val voiceDriveId = getTargetDriveFolder("Voice Notes")
                    driveServiceHelper?.createNote(voiceDriveId, finalNoteFile.name, noteContent.toString())
                    driveServiceHelper?.uploadFile(voiceDriveId, finalAudioFile, "audio/mp4")
                    addToSyncedHistory(finalNoteFile.name)
                    addToSyncedHistory(finalAudioFile.name)
                }

                // --- 6. NAVİGASYON VE UI (KRİTİK DEĞİŞİKLİK BURADA) ---
                withContext(Dispatchers.Main) {

                    // Eğer Gemini'den geldiyse ve animasyon hala dönüyorsa ŞİMDİ kapat
                    if (fromGemini) {
                        hideGeminiLoading()
                    }

                    // Kullanıcıyı direkt "Voice Notes" klasörüne götür
                    currentLocalDir = voiceFolder

                    if (isDriveMode) {
                        if (!driveBreadcrumbs.contains("Voice Notes")) {
                            driveBreadcrumbs.add("Voice Notes")
                        }
                    }

                    isSelectionMode = false
                    updateSelectionModeUI()
                    loadContent() // Listeyi yenile

                    // Toast mesajını sadece normal kayıtta gösterelim, Gemini zaten animasyonla belli etti
                    if (!fromGemini) {
                        Toast.makeText(this@MainActivity, "Voice note saved!", Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    if (fromGemini) hideGeminiLoading()
                    Toast.makeText(this@MainActivity, "Error saving note", Toast.LENGTH_SHORT).show()
                }
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
        val selected = currentNotes.filter { it.isSelected } + currentFolders.filter { it.isSelected }
        if (selected.isEmpty()) return

        // Silinecekleri önceden hazırla
        val itemsToDeleteInfo = selected.map { item ->
            val file = java.io.File(item.id)
            Triple(item.id, file.name, file.parentFile?.name ?: "HeyNotes")
        }

        lifecycleScope.launch(Dispatchers.IO) {
            // A. YEREL SİLME
            itemsToDeleteInfo.forEach { (path, fileName, _) ->
                localServiceHelper.deleteFile(path)

                // HAFIZADAN DA SİL (Önemli!)
                removeFromSyncedHistory(fileName)

                if (!path.endsWith("Voice Notes")) {
                    val noteFile = java.io.File(path)
                    val audioPath = java.io.File(noteFile.parent, noteFile.nameWithoutExtension + ".m4a")
                    if (audioPath.exists()) {
                        audioPath.delete()
                        removeFromSyncedHistory(audioPath.name) // Sesi de hafızadan sil
                    }
                }
            }

            withContext(Dispatchers.Main) {
                isSelectionMode = false
                updateSelectionModeUI()
                loadContent()
                Toast.makeText(this@MainActivity, "Deleted ${selected.size} items", Toast.LENGTH_SHORT).show()
            }

            // C. DRIVE SİLME
            if (isDriveMode && driveServiceHelper != null && rootDriveId != null) {
                itemsToDeleteInfo.forEach { (path, fileName, parentName) ->
                    try {
                        val targetFolderId = getTargetDriveFolder(parentName)
                        val driveFileId = driveServiceHelper?.findFileId(targetFolderId, fileName)
                        if (driveFileId != null) driveServiceHelper?.deleteFile(driveFileId)

                        if (!path.endsWith("Voice Notes")) {
                            val nameWithoutExt = if (fileName.contains(".")) fileName.substringBeforeLast(".") else fileName
                            val audioName = "$nameWithoutExt.m4a"
                            val driveAudioId = driveServiceHelper?.findFileId(targetFolderId, audioName)
                            if (driveAudioId != null) driveServiceHelper?.deleteFile(driveAudioId)
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }
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


    private fun openEditor(note: NoteItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // --- DÜZELTME BURADA ---
                // Eskiden: if (isDriveMode) driveServiceHelper.readFile(note.id) else ...
                // Yeni: Drive modu açık olsa bile, listeyi yerelden doldurduğumuz için ID bir dosya yoludur.
                // Bu yüzden HER ZAMAN yerel okuma yapıyoruz.

                val content = localServiceHelper.readFile(note.id)
                val color = colorStorage.getColor(note.id)
                val colorHex = if (color != null) String.format("#%06X", (0xFFFFFF and color)) else null

                withContext(Dispatchers.Main) {
                    val intent = Intent(this@MainActivity, EditorActivity::class.java).apply {
                        putExtra("NOTE_ID", note.id) // Artık bu bir dosya yolu
                        putExtra("NOTE_TITLE", note.name)
                        putExtra("NOTE_CONTENT", content)
                        if (colorHex != null) {
                            putExtra("NOTE_COLOR", colorHex)
                        }
                    }
                    editorLauncher.launch(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error opening note: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun deleteSingleFile(id: String) {
        // Bilgileri önceden al
        val localFile = java.io.File(id)
        val fileName = localFile.name
        val parentName = localFile.parentFile?.name ?: "HeyNotes"
        val nameWithoutExt = localFile.nameWithoutExtension

        lifecycleScope.launch(Dispatchers.IO) {
            // 1. Yerel Silme
            localServiceHelper.deleteFile(id)

            removeFromSyncedHistory(fileName)

            // Ses dosyasını yerelden sil
            val audioFile = java.io.File(localFile.parent, "$nameWithoutExt.m4a")
            if (audioFile.exists()) audioFile.delete()

            // 2. UI Güncelle (Drive'ı bekleme!)
            withContext(Dispatchers.Main) {
                loadContent()
            }

            // 3. Drive Silme (Arka Planda)
            if (isDriveMode && driveServiceHelper != null && rootDriveId != null) {
                try {
                    val targetFolderId = getTargetDriveFolder(parentName)

                    // Notu sil
                    val driveFileId = driveServiceHelper?.findFileId(targetFolderId, fileName)
                    if (driveFileId != null) driveServiceHelper?.deleteFile(driveFileId)

                    // Sesi sil
                    val audioName = "$nameWithoutExt.m4a"
                    val driveAudioId = driveServiceHelper?.findFileId(targetFolderId, audioName)
                    if (driveAudioId != null) driveServiceHelper?.deleteFile(driveAudioId)

                } catch (e: Exception) {
                    e.printStackTrace()
                    // Bağlantı hatası olursa (UnknownHostException) sadece loga yazar, uygulama donmaz.
                }
            }
        }
    }

    private fun saveNote(title: String, content: String, originalId: String?, colorHex: String?) {
        lifecycleScope.launch(Dispatchers.IO) {

            // --- 1. DOSYA YOLUNU HESAPLA ---
            val targetLocalDir = if (originalId != null) java.io.File(originalId).parentFile else currentLocalDir

            // --- [KRİTİK DÜZELTME] İSMİ TEMİZLE ---
            // 1. Önce ".md" uzantısı varsa kaldırıp ham ismi al
            val rawName = title.removeSuffix(".md")
            // 2. Yasaklı karakterleri temizle (?, :, / vb.)
            val safeName = getSafeFileName(rawName)
            // 3. Uzantıyı geri ekle (Eğer boş kaldıysa Untitled yap)
            val finalFileName = if (safeName.isNotEmpty()) "$safeName.md" else "Untitled.md"

            val finalFile = java.io.File(targetLocalDir, finalFileName)

            // --- 2. RENK KAYDI ---
            // Dosya henüz oluşmasa bile rengi path üzerine rezerve ediyoruz.
            if (colorHex != null) {
                colorStorage.saveColor(finalFile.absolutePath, colorHex)
            }

            titleStorage.saveTitle(finalFile.absolutePath, title.removeSuffix(".md"))

            // --- 3. DEĞİŞİKLİK KONTROLÜ (Zıplamayı Önler) ---
            if (originalId != null) {
                val oldFile = java.io.File(originalId)
                if (oldFile.exists()) {
                    val oldContent = oldFile.readText()
                    // Eski başlığı da uzantısız alıp karşılaştıralım
                    val oldTitle = oldFile.nameWithoutExtension

                    // Eğer başlık (temizlenmiş haliyle) ve içerik BİREBİR AYNIYSA işlem yapma
                    if (oldContent == content && oldTitle == safeName) {
                        withContext(Dispatchers.Main) { loadContent() }
                        return@launch
                    }
                }
            }

            // --- 4. YERELE KAYDET ---
            // Helper'a TEMİZLENMİŞ İSMİ (safeName) gönderiyoruz
            if (originalId != null) {
                // Güncelleme
                localServiceHelper.updateNote(originalId, safeName, content)
            } else {
                // Yeni Kayıt
                localServiceHelper.saveNote(currentLocalDir!!, safeName, content)
            }

            // --- 5. UI GÜNCELLE ---
            withContext(Dispatchers.Main) { loadContent() }

            // --- 6. DRIVE SENKRONİZASYONU (Arka Plan) ---
            if (isDriveMode && driveServiceHelper != null && rootDriveId != null) {
                try {
                    // A. Hedef Klasörü Belirle
                    val targetFolderName = targetLocalDir?.name ?: "HeyNotes"
                    val targetDriveFolderId = getTargetDriveFolder(targetFolderName)

                    // B. Metin Dosyasını (.md) Yükle/Güncelle
                    // finalFileName zaten yukarıda temizlenmişti
                    val existingFileId = driveServiceHelper?.findFileId(targetDriveFolderId, finalFileName)

                    if (existingFileId != null) {
                        driveServiceHelper?.updateFile(existingFileId, finalFileName, content)
                    } else {
                        driveServiceHelper?.createNote(targetDriveFolderId, finalFileName, content)
                    }

                    // C. Ses Dosyasını (.m4a) Kontrol Et ve Yükle
                    val audioFileName = finalFileName.replace(".md", ".m4a")
                    val localAudioFile = java.io.File(targetLocalDir, audioFileName)

                    if (localAudioFile.exists()) {
                        val existingAudioId = driveServiceHelper?.findFileId(targetDriveFolderId, audioFileName)
                        // Drive'da yoksa yükle
                        if (existingAudioId == null) {
                            driveServiceHelper?.uploadFile(targetDriveFolderId, localAudioFile, "audio/mp4")
                        }
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }



    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {

                // 1. Arama Modundaysak -> Aramayı Kapat
                if (isSearching) {
                    exitSearchMode()
                    return
                }

                // 2. Seçim Modundaysak -> Çık
                if (isSelectionMode) {
                    // ... (eski kodun aynısı)
                    isSelectionMode = false
                    currentNotes.forEach { it.isSelected = false }
                    notesAdapter.notifyDataSetChanged()
                    updateSelectionModeUI()
                    return
                }

                // 1. Seçim Modundaysak Çık
                if (isSelectionMode) {
                    isSelectionMode = false
                    currentNotes.forEach { it.isSelected = false }
                    notesAdapter.notifyDataSetChanged()
                    updateSelectionModeUI()
                    return
                }

                // 2. Navigasyon Yönetimi (DÜZELTİLDİ)
                // Drive modu olsun olmasın, referansımız artık yerel klasör (currentLocalDir)

                val rootPath = localServiceHelper.getRootFolder().absolutePath
                val currentPath = currentLocalDir?.absolutePath ?: rootPath

                if (currentPath != rootPath) {
                    // Ana dizinde değilsek bir yukarı çık
                    currentLocalDir = currentLocalDir?.parentFile

                    // Eğer yukarı çıkarken "files" gibi sistem klasörlerine taşarsa engelle
                    if (currentLocalDir == null || currentLocalDir!!.name == "files" || currentLocalDir!!.name == "0") {
                        currentLocalDir = localServiceHelper.getRootFolder()
                    }

                    if (isDriveMode && driveBreadcrumbs.isNotEmpty()) {
                        driveBreadcrumbs.removeAt(driveBreadcrumbs.lastIndex)
                    }

                    loadContent()
                } else {
                    // Ana dizindeysek uygulamadan çık veya arka plana at
                    finish()
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
        val selectedNotes = currentNotes.filter { it.isSelected }
        if (selectedNotes.isEmpty()) return

        // --- KLASÖR LİSTESİ HAZIRLIĞI (AYNI KALIYOR) ---
        val availableFolders = mutableListOf<NoteItem>()
        availableFolders.add(NoteItem("Main", true, "ROOT"))

        val rootFolders = if (isDriveMode) {
            currentFolders.filter { it.id != "ROOT" && it.id != "PRIVATE_ROOT" && !it.name.contains("Voice Note") }
        } else {
            currentFolders.filter { it.id != "ROOT" && it.isFolder }
        }

        val finalFolderList = mutableListOf<NoteItem>()
        finalFolderList.addAll(availableFolders)
        finalFolderList.addAll(rootFolders.filter { folder ->
            selectedNotes.none { note ->
                val noteFile = java.io.File(note.id)
                noteFile.parentFile?.name == folder.name
            }
        })

        // --- UI KISMI ---
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_folder_selection, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.rvFolderList)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvTitle)

        tvTitle.text = "Move ${selectedNotes.size} items to..."

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        recyclerView.layoutManager = LinearLayoutManager(this)

        // --- DEĞİŞEN KISIM SADECE BURASI ---
        // Artık uzun uzun adapter yazmak yok, yukarıdaki sınıfı çağırıyoruz:
        recyclerView.adapter = FolderSelectionAdapter(finalFolderList) { targetFolder ->
            dialog.dismiss()
            moveSelectedItemsFast(selectedNotes, targetFolder)
        }
        // ------------------------------------

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

        // --- 1. HEDEF KLASÖRLERİ HAZIRLA ---
        val availableFolders = mutableListOf<NoteItem>()
        availableFolders.add(NoteItem("Main", true, "ROOT")) // Ana Dizin

        // Mevcut klasörleri listeye ekle
        val rootFolders = if (isDriveMode) {
            currentFolders.filter {
                it.id != "ROOT" &&
                        it.id != "PRIVATE_ROOT"
                // Voice Note filtresi kaldırıldı ✅
            }
        } else {
            val rootDir = localServiceHelper.getRootFolder()
            rootDir.listFiles()
                // Voice Notes filtresi kaldırıldı ✅ (Sadece Private Notes gizli kalsın)
                ?.filter { it.isDirectory && it.name != "Private Notes" }
                ?.map { NoteItem(it.name, true, it.absolutePath) }
                ?: emptyList()
        }

        // Kendisini kendi içine taşıyamayacağı için filtrele
        val finalFolderList = mutableListOf<NoteItem>()
        finalFolderList.addAll(availableFolders)
        finalFolderList.addAll(rootFolders.filter { folder ->
            selectedNotes.none { it.id == folder.id }
        })

        // --- 2. DIALOG TASARIMI ---
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_folder_selection, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.rvFolderList)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvTitle)

        // Başlık
        tvTitle.text = "Move ${selectedNotes.size} items to..."

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        recyclerView.layoutManager = LinearLayoutManager(this)

        // --- 3. ADAPTER (İŞTE BURASI DEĞİŞTİ) ---
        // O uzun "object : RecyclerView..." kodu gitti.
        // Yerine renkli ve modern Adapter sınıfımızı çağırıyoruz:
        recyclerView.adapter = FolderSelectionAdapter(finalFolderList) { targetFolder ->
            dialog.dismiss()
            moveSelectedItemsFast(selectedNotes, targetFolder)
        }

        dialog.show()
    }

    private fun moveSelectedItemsFast(items: List<NoteItem>, targetFolder: NoteItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            // Drive işlemi için gerekli bilgileri (Eski yer, Yeni yer, İsim) hafızaya alıyoruz
            val itemsToSync = mutableListOf<Triple<String, String, String>>()
            var movedCount = 0

            // 1. Hedef Klasörü Belirle (Yerel)
            val rootDir = localServiceHelper.getRootFolder()
            val targetDirName = targetFolder.name
            val targetDirFile = if (targetFolder.id == "ROOT" || targetFolder.name == "Main") rootDir else java.io.File(rootDir, targetDirName)

            if (!targetDirFile.exists()) targetDirFile.mkdirs()

            // --- A. YEREL TAŞIMA (ANINDA) ---
            items.forEach { item ->
                val sourceFile = java.io.File(item.id)
                val fileName = sourceFile.name
                val oldParentName = sourceFile.parentFile?.name ?: "HeyNotes"

                // Hedef ile kaynak aynıysa atla
                if (sourceFile.parentFile?.absolutePath == targetDirFile.absolutePath) return@forEach

                val destFile = java.io.File(targetDirFile, fileName)

                // Dosya ismini çakışmaya karşı ayarla (Örn: Not.md -> Not (1).md)
                var finalDestFile = destFile
                var counter = 1
                while (finalDestFile.exists()) {
                    val nameNoExt = fileName.substringBeforeLast(".")
                    val ext = fileName.substringAfterLast(".", "")
                    val newName = "$nameNoExt ($counter).$ext"
                    finalDestFile = java.io.File(targetDirFile, newName)
                    counter++
                }

                // --- [YENİ] 1. TAŞIMADAN ÖNCE RENGİ AL ---
                val oldColorInt = colorStorage.getColor(sourceFile.absolutePath)

                if (sourceFile.renameTo(finalDestFile)) {
                    movedCount++

                    // --- [YENİ] 2. RENGİ YENİ ADRESE KAYDET ---
                    if (oldColorInt != null) {
                        val hexColor = String.format("#%06X", (0xFFFFFF and oldColorInt))
                        colorStorage.saveColor(finalDestFile.absolutePath, hexColor)
                    }

                    // Listeye ekle (Drive için)
                    itemsToSync.add(Triple(fileName, oldParentName, targetDirName))

                    // Varsa Ses Dosyasını da Taşı
                    if (!item.isFolder) {
                        val audioName = sourceFile.nameWithoutExtension + ".m4a"
                        val sourceAudio = java.io.File(sourceFile.parent, audioName)
                        if (sourceAudio.exists()) {
                            val destAudioName = finalDestFile.nameWithoutExtension + ".m4a"
                            val destAudio = java.io.File(targetDirFile, destAudioName)
                            sourceAudio.renameTo(destAudio)
                        }
                    }
                }
            }

            // --- B. EKRANI GÜNCELLE (KULLANICIYI BEKLETME) ---
            withContext(Dispatchers.Main) {
                isSelectionMode = false
                updateSelectionModeUI()
                loadContent() // Liste anında güncellenir
                if (movedCount > 0) {
                    Toast.makeText(this@MainActivity, "Moved $movedCount items", Toast.LENGTH_SHORT).show()
                }
            }

            // --- C. DRIVE İŞLEMİ (ARKA PLAN - SESSİZ) ---
            if (isDriveMode && driveServiceHelper != null && rootDriveId != null) {
                itemsToSync.forEach { (fileName, oldParentName, newParentName) ->
                    try {
                        val oldParentId = getTargetDriveFolder(oldParentName)
                        val newParentId = getTargetDriveFolder(newParentName)

                        val fileId = driveServiceHelper?.findFileId(oldParentId, fileName)

                        if (fileId != null) {
                            driveServiceHelper?.moveFile(fileId, newParentId)
                        }

                        if (fileName.endsWith(".md")) {
                            val audioName = fileName.replace(".md", ".m4a")
                            val audioId = driveServiceHelper?.findFileId(oldParentId, audioName)
                            if (audioId != null) {
                                driveServiceHelper?.moveFile(audioId, newParentId)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
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
        // 1. ROOT (Ana Sayfaya Dönüş)
        if (item.id == "ROOT") {
            currentLocalDir = localServiceHelper.getRootFolder()

            // Drive modundaysak breadcrumb'ı (üstteki yol haritasını) sıfırla
            if (isDriveMode) {
                driveBreadcrumbs.clear()
                driveBreadcrumbs.add("Main")
            }

            loadContent()
            return
        }

        if (item.isActive) return

        // 2. PRIVATE FOLDER KONTROLÜ
        if (item.id == "PRIVATE_ROOT") {
            val masterPin = getMasterPin()
            if (masterPin == null) {
                showSetMasterPinDialog { openPrivateFolder() }
            } else {
                showUnlockDialog { openPrivateFolder() }
            }
            return
        }

        // 3. KİLİTLİ KLASÖR KONTROLÜ (Eski sistem)
        if (item.isLocked && !forceUnlock) {
            // NoteItem parametresi alan showUnlockDialog versiyonunuz varsa: showUnlockDialog(item)
            // Yoksa genel olanı kullanıp başarı durumunda handleNavigation(item, true) çağırabilirsiniz.
            // Şimdilik basitçe kilitliyse açtırmıyoruz (pin dialog kodunuzun yapısına göre)
            Toast.makeText(this, "Locked Folder", Toast.LENGTH_SHORT).show()
            return
        }

        // 4. NORMAL YÖNLENDİRME (DÜZELTİLDİ)
        // Hata buradaydı: isDriveMode olsa bile artık yerel yolu (path) takip etmeliyiz.
        // Çünkü listeyi yerelden oluşturuyoruz.

        currentLocalDir = java.io.File(item.id)

        if (isDriveMode) {
            driveBreadcrumbs.add(item.name)
        }

        loadContent()
    }

    private fun loadContent() {
        isSelectionMode = false
        updateSelectionModeUI()

        // --- 1. RENK TANIMLAMALARI ---
        // Varsayılan rengi button_color yapıyoruz (Dark/Light mod uyumlu olsun diye)
        val defaultButtonColor = getColor(R.color.button_color)

        var currentFolderColor = defaultButtonColor
        var currentFolderName = "Main"
        val isRoot = currentLocalDir?.name == "HeyNotes" // Ana dizinde miyiz?

        if (!isRoot) {
            currentFolderName = currentLocalDir?.name ?: "Folder"
            val isPrivate = !isDriveMode && currentLocalDir?.absolutePath?.contains("Private Notes") == true

            currentFolderColor = if (currentFolderName.contains("Voice Note")) {
                Color.parseColor("#FF4B4B")
            } else if (isPrivate) {
                // Private için de istersen button_color kullanabilirsin, şimdilik Siyah bıraktım
                Color.BLACK
            } else {
                colorStorage.getColor(currentLocalDir!!.absolutePath) ?: Color.parseColor("#616161")
            }
        }

        tvAppTitle.text = "Hey, $userName"

        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        tvGreeting.text = when {
            hour < 12 -> "Good morning."
            hour < 17 -> "Good afternoon."
            hour < 21 -> "Good evening."
            else -> "Good night."
        }

        tvSubtitle.text = currentFolderName

        // --- 2. BUTON RENKLERİNİ UYGULA ---

        // Folder Selector (Soldaki) -> Klasöre göre renk değiştirir
        layoutFolderSelector.backgroundTintList = android.content.res.ColorStateList.valueOf(currentFolderColor)

        // Search Pill (Sağdaki) -> HER ZAMAN button_color olsun (Main ile uyumlu)
        // (Eğer bunun da klasör rengini almasını istersen parantez içini currentFolderColor yap)
        searchContainer.backgroundTintList = android.content.res.ColorStateList.valueOf(defaultButtonColor)


        lifecycleScope.launch(Dispatchers.IO) {
            // 1. KLASÖR MENÜSÜ İÇİN: Her zaman ROOT klasörünü tara
            val rootDir = localServiceHelper.getRootFolder()
            val allRootItems = localServiceHelper.listItems(rootDir)
            val rootFoldersRaw = allRootItems.filter { it.isFolder }

            // 2. NOT LİSTESİ İÇİN: Şu anki klasörü tara
            if (currentLocalDir == null || !currentLocalDir!!.exists()) {
                currentLocalDir = rootDir
            }
            val currentViewItems = localServiceHelper.listItems(currentLocalDir!!)
            val notesInCurrentView = currentViewItems.filter { !it.isFolder }

            withContext(Dispatchers.Main) {
                // --- KLASÖR LİSTESİNİ OLUŞTUR (Menü İçin) ---
                val tempFolderList = mutableListOf<NoteItem>()

                // A. Sabit Klasörler
                // DEĞİŞİKLİK: Main klasörünün rengini de button_color yaptık
                tempFolderList.add(NoteItem(
                    name = "Main",
                    isFolder = true,
                    id = "ROOT",
                    color = defaultButtonColor, // <-- BURASI GÜNCELLENDİ
                    isActive = isRoot
                ))

                val privateDir = java.io.File(getFilesDir(), "Private Notes")
                val isPrivateActive = currentLocalDir?.absolutePath == privateDir.absolutePath
                tempFolderList.add(NoteItem("Private", true, "PRIVATE_ROOT", color = Color.BLACK, isActive = isPrivateActive, isLocked = true))

                // B. Voice Notes
                val localVoiceDir = java.io.File(rootDir, "Voice Notes")
                if (!localVoiceDir.exists()) localVoiceDir.mkdirs()
                val isVoiceActive = currentLocalDir?.absolutePath == localVoiceDir.absolutePath

                tempFolderList.add(NoteItem(
                    name = "Voice Notes",
                    isFolder = true,
                    id = localVoiceDir.absolutePath,
                    color = Color.parseColor("#FF4B4B"),
                    isActive = isVoiceActive
                ))

                // C. Diğer Klasörler
                val processedFolders = rootFoldersRaw.map { folder ->
                    val isThisFolderActive = currentLocalDir?.absolutePath == folder.id
                    val isLocked = securityStorage.isLocked(folder.id)
                    folder.copy(
                        name = folder.name.replace("!!", ""),
                        color = colorStorage.getColor(folder.id) ?: Color.parseColor("#616161"),
                        isActive = isThisFolderActive,
                        isLocked = isLocked
                    )
                }

                tempFolderList.addAll(processedFolders.filter {
                    !it.name.contains("Voice Note") && it.name != "Private Notes"
                }.sortedBy { it.name })

                // --- NOT LİSTESİ ---
                // --- NOT LİSTESİ (DÜZELTİLDİ: Gerçek Başlıklar) ---
                val notesWithColors = notesInCurrentView.map { note ->
                    // 1. Hafızadan gerçek başlığı (Soru işaretli olanı) çek
                    val realTitle = titleStorage.getTitle(note.id) ?: note.name

                    // 2. NoteItem'ı güncelle (name artık gerçek başlık olacak)
                    note.copy(
                        name = realTitle,
                        color = colorStorage.getColor(note.id)
                    )
                }

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

    private fun checkAndSyncBackground() {
        lifecycleScope.launch(Dispatchers.IO) {
            uploadLocalNotes()
            downloadMissingFilesFromDrive()
        }
    }

    private suspend fun uploadLocalNotes() {
        if (!isDriveMode || driveServiceHelper == null || rootDriveId == null) return

        withContext(Dispatchers.IO) {
            try {
                // 1. Drive'daki Tüm Dosyaların Listesini Çek
                val driveFilesList = driveServiceHelper?.listFiles(rootDriveId!!) ?: emptyList()
                val driveFileNames = driveFilesList.map { it.second }.toSet()

                val rootLocalDir = localServiceHelper.getRootFolder()
                val localFiles = rootLocalDir.listFiles()?.filter {
                    it.isFile && (it.name.endsWith(".md") || it.name.endsWith(".m4a"))
                } ?: emptyList()

                // Hafızayı çağır
                val syncedHistory = getSyncedFiles()

                localFiles.forEach { localFile ->
                    val fileName = localFile.name

                    if (driveFileNames.contains(fileName)) {
                        // --- DURUM A: Hem Yerde Var, Hem Drive'da Var ---
                        if (fileName.endsWith(".md")) {
                            val driveId = driveFilesList.find { it.second == fileName }!!.first
                            val localContent = localFile.readText()
                            driveServiceHelper?.updateFile(driveId, fileName, localContent)
                        }
                        addToSyncedHistory(fileName)
                    }
                    else {
                        // --- DURUM B: Yerde Var, Ama Drive'da YOK ---
                        if (syncedHistory.contains(fileName)) {
                            // Zombi Notu Öldür (Drive'dan silinmiş, bizden de silinsin)
                            localFile.delete()
                            removeFromSyncedHistory(fileName)

                            if (fileName.endsWith(".md")) {
                                val audioName = fileName.replace(".md", ".m4a")
                                val audioFile = java.io.File(localFile.parent, audioName)
                                if (audioFile.exists()) {
                                    audioFile.delete()
                                    removeFromSyncedHistory(audioName)
                                }
                            }
                            android.util.Log.d("HeyNotesSync", "Remote delete detected for: $fileName")
                        }
                        else {
                            // Yeni Not -> Yükle
                            if (fileName.endsWith(".md")) {
                                val content = localFile.readText()
                                driveServiceHelper?.createNote(rootDriveId!!, fileName, content)
                            } else if (fileName.endsWith(".m4a")) {
                                driveServiceHelper?.uploadFile(rootDriveId!!, localFile, "audio/mp4")
                            }
                            addToSyncedHistory(fileName)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Drive'da doğru klasörü bulan veya oluşturan akıllı fonksiyon
    private suspend fun getTargetDriveFolder(localDirName: String): String {
        // 1. Eğer ana dizindeysek Root ID dön
        if (localDirName == "HeyNotes" || localDirName == "files" || localDirName == "0") {
            return rootDriveId!!
        }

        // 2. Alt klasördeysek Drive'da ara
        val foundId = driveServiceHelper?.findFileId(rootDriveId!!, localDirName)

        // 3. Varsa ID'yi dön, yoksa oluşturup dön
        return foundId ?: driveServiceHelper?.createFolder(rootDriveId!!, localDirName) ?: rootDriveId!!
    }

    private fun showMoveSelectionDialog() {
        val selectedItems = currentNotes.filter { it.isSelected } + currentFolders.filter { it.isSelected }
        if (selectedItems.isEmpty()) return

        // Gidilebilecek Klasörleri Listele
        val rootDir = localServiceHelper.getRootFolder()
        val allDirs = rootDir.listFiles { file -> file.isDirectory && file.name != "Private Notes" }?.toList() ?: emptyList()

        // Klasör isimlerini al (En başa "Main" ekle)
        val folderNames = mutableListOf("Main")
        allDirs.forEach { folderNames.add(it.name) }

        AlertDialog.Builder(this)
            .setTitle("Move to...")
            .setItems(folderNames.toTypedArray()) { _, which ->
                val targetFolderName = folderNames[which]
                moveSelectedItems(selectedItems, targetFolderName)
            }
            .show()
    }

    private fun moveSelectedItems(items: List<NoteItem>, targetFolderName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            // 1. Hedef Klasörü Hazırla
            val rootDir = localServiceHelper.getRootFolder()
            val targetDir = if (targetFolderName == "Main") rootDir else java.io.File(rootDir, targetFolderName)
            if (!targetDir.exists()) targetDir.mkdirs()

            val itemsToSyncWithDrive = mutableListOf<Triple<String, String, String>>()
            var movedCount = 0

            // --- A. YEREL TAŞIMA (HIZLI) ---
            items.forEach { item ->
                val sourceFile = java.io.File(item.id)
                val fileName = sourceFile.name
                val oldParentName = sourceFile.parentFile?.name ?: "HeyNotes"

                // Kendi içine veya aynı yere taşımayı engelle
                if (sourceFile.parentFile?.absolutePath == targetDir.absolutePath) return@forEach
                if (item.isFolder && item.name == targetFolderName) return@forEach

                val destFile = java.io.File(targetDir, fileName)

                // --- [YENİ] 1. TAŞIMADAN ÖNCE RENGİ AL ---
                val oldColorInt = colorStorage.getColor(sourceFile.absolutePath)

                if (sourceFile.renameTo(destFile)) {
                    movedCount++

                    // --- [YENİ] 2. RENGİ YENİ ADRESE KAYDET ---
                    if (oldColorInt != null) {
                        val hexColor = String.format("#%06X", (0xFFFFFF and oldColorInt))
                        colorStorage.saveColor(destFile.absolutePath, hexColor)
                    }

                    // Bilgileri kaydet (Drive için lazım olacak)
                    itemsToSyncWithDrive.add(Triple(fileName, oldParentName, targetFolderName))

                    // Ses Dosyasını da Taşı (.m4a)
                    if (!item.isFolder) {
                        val audioName = sourceFile.nameWithoutExtension + ".m4a"
                        val sourceAudio = java.io.File(sourceFile.parent, audioName)
                        if (sourceAudio.exists()) {
                            val destAudio = java.io.File(targetDir, audioName)
                            sourceAudio.renameTo(destAudio)
                        }
                    }
                }
            }

            // --- B. UI GÜNCELLEME (ANINDA) ---
            withContext(Dispatchers.Main) {
                isSelectionMode = false
                updateSelectionModeUI()
                loadContent()

                if (movedCount > 0) {
                    Toast.makeText(this@MainActivity, "Moved $movedCount items", Toast.LENGTH_SHORT).show()
                }
            }

            // --- C. DRIVE TAŞIMA (ARKA PLAN - SESSİZ) ---
            if (isDriveMode && driveServiceHelper != null && rootDriveId != null) {
                itemsToSyncWithDrive.forEach { (fileName, oldParentName, newParentName) ->
                    try {
                        val oldParentId = getTargetDriveFolder(oldParentName)
                        val newParentId = getTargetDriveFolder(newParentName)

                        val fileId = driveServiceHelper?.findFileId(oldParentId, fileName)

                        if (fileId != null) {
                            driveServiceHelper?.moveFile(fileId, newParentId)
                        }

                        if (fileName.endsWith(".md")) {
                            val audioName = fileName.replace(".md", ".m4a")
                            val audioId = driveServiceHelper?.findFileId(oldParentId, audioName)
                            if (audioId != null) {
                                driveServiceHelper?.moveFile(audioId, newParentId)
                            }
                        }

                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }
    // --- SENKRONİZASYON HAFIZASI (Zombi Notları Engellemek İçin) ---
    // Bu fonksiyonlar sınıfın doğrudan içinde olmalı, başka fonksiyonun içinde değil!

    private fun getSyncedFiles(): MutableSet<String> {
        val prefs = getSharedPreferences("sync_history", android.content.Context.MODE_PRIVATE)
        return prefs.getStringSet("files", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
    }

    private fun addToSyncedHistory(fileName: String) {
        val history = getSyncedFiles()
        history.add(fileName)
        getSharedPreferences("sync_history", android.content.Context.MODE_PRIVATE)
            .edit().putStringSet("files", history).apply()
    }

    private fun removeFromSyncedHistory(fileName: String) {
        val history = getSyncedFiles()
        history.remove(fileName)
        getSharedPreferences("sync_history", android.content.Context.MODE_PRIVATE)
            .edit().putStringSet("files", history).apply()
    }

    private suspend fun downloadMissingFilesFromDrive() {
        if (!isDriveMode || driveServiceHelper == null || rootDriveId == null) return

        withContext(Dispatchers.IO) {
            try {
                val driveFiles = driveServiceHelper?.listFiles(rootDriveId!!) ?: emptyList()
                val rootLocalDir = localServiceHelper.getRootFolder()

                driveFiles.forEach { (driveId, driveName) ->
                    val localFile = java.io.File(rootLocalDir, driveName)

                    if (!localFile.exists()) {
                        // --- İNDİRME İŞLEMİ ---
                        var isDownloaded = false

                        if (driveName.endsWith(".md")) {
                            val content = driveServiceHelper?.readFileContent(driveId)
                            if (content != null) {
                                localServiceHelper.saveNote(rootLocalDir, driveName.removeSuffix(".md"), content)
                                isDownloaded = true
                            }
                        }
                        else if (driveName.endsWith(".m4a")) {
                            driveServiceHelper?.downloadFile(driveId, localFile)
                            isDownloaded = true
                        }

                        // İndirme başarılıysa HAFIZAYA KAYDET
                        if (isDownloaded) {
                            addToSyncedHistory(driveName)
                        }
                    }
                    else {
                        // Zaten varsa da hafızada olduğundan emin ol
                        addToSyncedHistory(driveName)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        withContext(Dispatchers.Main) { loadContent() }
    }



    // MainActivity'nin içinde, en alta (inner class olarak) ekleyin.
    inner class FolderSelectionAdapter(
        private val folders: List<NoteItem>,
        private val onFolderSelected: (NoteItem) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_move_folder, parent, false)
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val folder = folders[position]

            val cardView = holder.itemView as com.google.android.material.card.MaterialCardView
            val tvName = holder.itemView.findViewById<TextView>(R.id.tvFolderName)
            val ivIcon = holder.itemView.findViewById<ImageView>(R.id.ivFolderIcon)

            tvName.text = folder.name

            // --- RENK MANTIĞI ---
            // Klasörün rengini ana listeden (currentFolders) bul
            // folder nesnesi geçici kopya olabilir, bu yüzden ID ile ana listeden gerçeğini ve rengini çekiyoruz.
            val originalFolder = currentFolders.find { it.id == folder.id || (folder.id == "ROOT" && it.id == "ROOT") }
            val folderColor = originalFolder?.color ?: Color.parseColor("#F5F5F5")

            // 1. Arka Plan: Rengin %20 opak hali (Pastel)
            val pastelColor = androidx.core.graphics.ColorUtils.setAlphaComponent(folderColor, 50)
            cardView.setCardBackgroundColor(pastelColor)

            // 2. İkon Rengi: Orijinal renk
            ivIcon.setColorFilter(folderColor)

            // 3. Yazı Rengi: Koyu
            tvName.setTextColor(getColor(R.color.text_color))

            // İkon Seçimi
            val iconRes = when {
                folder.id == "PRIVATE_ROOT" -> R.drawable.ic_lock_icon
                folder.name.contains("Voice Note") -> R.drawable.ic_voice_icon
                folder.id == "ROOT" -> R.drawable.ic_home_icon
                else -> R.drawable.ic_folder_icon
            }
            ivIcon.setImageResource(iconRes)

            // Tıklama
            holder.itemView.setOnClickListener {
                onFolderSelected(folder)
            }
        }

        override fun getItemCount() = folders.size
    }


    // Sadece Klasör Değiştirmek (Navigasyon) İçin Kullanılacak Fonksiyon
    private fun showNavigationDialog() {
        // 1. Listeyi Al (loadContent içinde hazırladığımız currentFolders listesi tam da bu iş için)
        // Main, Private, Voice Notes ve diğerleri zaten burada var.
        val navigationList = ArrayList(currentFolders)

        // 2. Dialog Tasarımı
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_folder_selection, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.rvFolderList)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvTitle)

        tvTitle.text = "Go to..." // Başlığı "Go to..." yapıyoruz

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        recyclerView.layoutManager = LinearLayoutManager(this)

        // 3. Adapter (Renkli Adapter'ımızı burada da kullanıyoruz)
        recyclerView.adapter = FolderSelectionAdapter(navigationList) { targetFolder ->
            dialog.dismiss()
            // Tıklanınca Taşıma (Move) DEĞİL, Gitme (Navigate) işlemi yap:
            handleNavigation(targetFolder)
        }

        dialog.show()
    }


    private fun setupSearchLogic() {
        // 1. Pill'e (Kapsayıcıya) Tıklayınca -> Arama Modunu Aç
        searchContainer.setOnClickListener {
            if (!isSearching) {
                isSearching = true

                // Görünüm Değişikliği: Label GİTSİN, Input ve X GELSİN
                tvSearchLabel.visibility = View.GONE
                etSearch.visibility = View.VISIBLE
                ivCloseSearch.visibility = View.VISIBLE

                etSearch.requestFocus()
                // Klavyeyi aç
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(etSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }

        // 2. Kapat (X) Tıklayınca -> Arama Modundan Çık
        ivCloseSearch.setOnClickListener {
            exitSearchMode()
        }

        // 3. Yazı Yazıldıkça -> ARAMA YAP (Burası aynı)
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString().trim()
                if (query.length >= 2) {
                    performGlobalSearch(query)
                } else if (query.isEmpty()) {
                    loadContent()
                }
            }
        })
    }

    private fun exitSearchMode() {
        isSearching = false
        etSearch.setText("") // Yazıyı temizle

        // Görünüm Değişikliği: Input ve X GİTSİN, Label GELSİN
        etSearch.visibility = View.GONE
        ivCloseSearch.visibility = View.GONE
        tvSearchLabel.visibility = View.VISIBLE

        // Klavyeyi kapat
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(etSearch.windowToken, 0)

        // Listeyi normale döndür
        loadContent()
    }

    private fun performGlobalSearch(query: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val searchResults = mutableListOf<NoteItem>()
            val lowerQuery = query.lowercase()

            // 1. Ana Dizini Tara (Local)
            val rootDir = localServiceHelper.getRootFolder()

            // Tüm dosyaları ve klasörleri al (Recursive/Derinlemesine arama yapmayacağız, sadece 1 seviye alt klasörlere bakacağız)
            // Eğer "Tüm iç içe klasörler" olsun dersen walk() kullanabiliriz ama şimdilik yapın 1 seviye klasör destekliyor.

            val allFiles = rootDir.listFiles() ?: emptyArray()

            allFiles.forEach { file ->
                if (file.isDirectory) {
                    // --- KLASÖR İÇİ TARAMA ---
                    // Private Notes hariç, diğer klasörlerin içine bak
                    if (file.name != "Private Notes") {
                        file.listFiles()?.forEach { subFile ->
                            if (checkFileMatches(subFile, lowerQuery)) {
                                searchResults.add(createNoteItemFromFile(subFile, fromFolder = file.name))
                            }
                        }
                    }
                } else {
                    // --- ANA DİZİN DOSYALARI ---
                    if (checkFileMatches(file, lowerQuery)) {
                        searchResults.add(createNoteItemFromFile(file, fromFolder = "Main"))
                    }
                }
            }

            withContext(Dispatchers.Main) {
                // Başlığı güncelle ki kullanıcı arama yaptığını anlasın
                tvSubtitle.text = "Results:"
                // Listeyi güncelle
                currentNotes = searchResults
                notesAdapter.submitList(searchResults)
            }
        }
    }

    // Arama Yardımcısı: Dosya ismi veya içeriği eşleşiyor mu?
    private fun checkFileMatches(file: File, query: String): Boolean {
        if (!file.isFile) return false
        if (!file.name.endsWith(".md")) return false // Sadece notları ara

        // 1. İsimde Ara
        val nameMatch = file.name.lowercase().contains(query)
        if (nameMatch) return true

        // 2. İçerikte Ara (Dosyayı okuyup bakacağız - Dikkat: Çok büyük dosyalarda yavaş olabilir)
        try {
            val content = file.readText().lowercase()
            if (content.contains(query)) return true
        } catch (e: Exception) {
            return false
        }

        return false
    }

    // Arama Yardımcısı: Dosyadan NoteItem oluştur (Görselleştirmek için)
    private fun createNoteItemFromFile(file: File, fromFolder: String): NoteItem {
        // Dosyanın içeriğini burada okuyoruz (Önizleme için)
        var previewText = ""
        try {
            // İlk 150 karakteri al, satır sonlarını boşlukla değiştir
            previewText = file.readText().take(150).replace("\n", " ")
        } catch (e: Exception) {
            previewText = "No preview available"
        }

        // Gerçek başlığı hafızadan al, yoksa dosya adını kullan
        val realName = titleStorage.getTitle(file.absolutePath) ?: file.name

        return NoteItem(
            name = realName, // Dosya adı yerine gerçek ismi basıyoruz
            id = file.absolutePath,
            isFolder = false,
            color = colorStorage.getColor(file.absolutePath),
            searchPreview = previewText
        )
    }
}

class TitleStorage(context: Context) {
    private val prefs = context.getSharedPreferences("note_titles", Context.MODE_PRIVATE)

    fun saveTitle(path: String, title: String) {
        prefs.edit().putString(path, title).apply()
    }

    fun getTitle(path: String): String? {
        return prefs.getString(path, null)
    }

    fun removeTitle(path: String) {
        prefs.edit().remove(path).apply()
    }
}
