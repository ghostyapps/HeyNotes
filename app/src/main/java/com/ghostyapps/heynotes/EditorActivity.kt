package com.ghostyapps.heynotes

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.airbnb.lottie.LottieAnimationView
import com.google.android.material.card.MaterialCardView
import io.noties.markwon.Markwon
import kotlinx.coroutines.launch
import java.io.File

class EditorActivity : AppCompatActivity() {

    private lateinit var localServiceHelper: LocalServiceHelper

    // UI Components
    private lateinit var etTitle: EditText
    private lateinit var etContent: EditText
    private lateinit var tvReader: TextView
    private lateinit var svReader: ScrollView
    private lateinit var tvEditorDate: TextView // Yeni eklendi (Header rengi için lazım)

    private lateinit var bottomBar: View
    private lateinit var cardContent: View
    private lateinit var cardHeader: MaterialCardView // Yeni eklendi (Header rengi için lazım)

    private lateinit var ivNoteColor: ImageView

    private lateinit var ivShare: ImageView

    // Player Components
    private lateinit var playerContainer: LinearLayout
    private lateinit var btnPlayPause: ImageView
    private lateinit var seekBar: SeekBar
    private lateinit var tvDuration: TextView

    // Helpers
    private lateinit var markwon: Markwon
    private lateinit var colorStorage: ColorStorage
    private var mediaPlayer: android.media.MediaPlayer? = null

    // State
    private var isEditMode = true
    private var originalNoteId: String? = null
    private var selectedColorHex: String? = null // Renk değişkenimiz bu

    private lateinit var fabOverlay: View
    private lateinit var geminiLoadingContainer: LinearLayout
    private lateinit var lottieGemini: LottieAnimationView
    private lateinit var tvGeminiStatus: TextView
    private lateinit var tvGeminiAction: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-Edge
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        // Window Ayarları
        val windowInsetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        val isNightMode = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        windowInsetsController.isAppearanceLightStatusBars = !isNightMode
        windowInsetsController.isAppearanceLightNavigationBars = !isNightMode

        setContentView(R.layout.activity_editor)

        // Init Helpers
        localServiceHelper = LocalServiceHelper(this)
        colorStorage = ColorStorage(this)

        // --- Init Components ---
        cardHeader = findViewById(R.id.cardHeader) // Header Kartı
        etTitle = findViewById(R.id.etTitle)
        etContent = findViewById(R.id.etContent)
        tvEditorDate = findViewById(R.id.tvEditorDate)

        cardContent = findViewById(R.id.cardContent)
        tvReader = findViewById(R.id.tvReader)
        svReader = findViewById(R.id.scrollViewReader)
        bottomBar = findViewById(R.id.cardBottomToolbar)
        ivNoteColor = findViewById(R.id.ivNoteColor)
        ivShare = findViewById(R.id.ivShare)

        ivShare.setOnClickListener {
            shareNoteText()
        }

        // --- DİNAMİK EKRAN VE KLAVYE AYARI ---
        val rootLayout = findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.editorRoot)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())

            val extraSpaceDp = 20
            val extraSpacePx = (extraSpaceDp * resources.displayMetrics.density).toInt()

            val params = cardHeader.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            params.topMargin = systemBars.top + extraSpacePx
            cardHeader.layoutParams = params

            val bottomPadding = if (ime.bottom > 0) ime.bottom else systemBars.bottom
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottomPadding)

            insets
        }

        // Tarih Formatı
        val timestamp = intent.getLongExtra("NOTE_TIMESTAMP", 0L)
        val fullFormat = java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.getDefault())

        if (timestamp > 0) {
            tvEditorDate.text = "Last edited: " + fullFormat.format(java.util.Date(timestamp))
        } else {
            tvEditorDate.text = fullFormat.format(java.util.Date())
        }

        // Klavye Ayarı
        etContent.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

        // Player Views Init
        playerContainer = findViewById(R.id.playerContainer)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        seekBar = findViewById(R.id.seekBar)
        tvDuration = findViewById(R.id.tvDuration)

        fabOverlay = findViewById(R.id.fabOverlay)
        geminiLoadingContainer = findViewById(R.id.geminiLoadingContainer)
        lottieGemini = findViewById(R.id.lottieGemini)
        tvGeminiStatus = findViewById(R.id.tvGeminiStatus)
        tvGeminiAction = findViewById(R.id.tvGeminiAction)

        val btnTranscribe = findViewById<View>(R.id.btnTranscribe)

        // --- MARKWON BUILDER ---
        markwon = io.noties.markwon.Markwon.builder(this)
            .usePlugin(io.noties.markwon.ext.strikethrough.StrikethroughPlugin.create())
            .usePlugin(io.noties.markwon.ext.tasklist.TaskListPlugin.create(this))
            .build()

        // Load Data
        originalNoteId = intent.getStringExtra("NOTE_ID")
        val incomingTitle = intent.getStringExtra("NOTE_TITLE")
        val incomingContent = intent.getStringExtra("NOTE_CONTENT")
        val colorHex = intent.getStringExtra("NOTE_COLOR") // MainActivity'den gelen renk

        // --- RENK YÖNETİMİ ---
        if (colorHex != null) {
            selectedColorHex = colorHex
            applyColorToHeader(colorHex)
        } else if (originalNoteId != null) {
            // Var olan not ama Intent'te renk yoksa storage'dan çek
            val savedColor = colorStorage.getColor(originalNoteId!!)
            if (savedColor != null) {
                val hex = String.format("#%06X", (0xFFFFFF and savedColor))
                selectedColorHex = hex
                applyColorToHeader(hex)
            } else {
                applyColorToHeader("#FFFFFF")
            }
        } else {
            // Yeni not
            val randomColor = ColorStorage.colors.random()
            selectedColorHex = randomColor
            applyColorToHeader(randomColor)
        }

        // Ses Dosyası Arama
        var audioFileToPlay: File? = null
        if (incomingContent != null && incomingContent.contains("Audio Note:")) {
            try {
                val audioName = incomingContent.substringAfter("Audio Note: ").substringBefore("\n").trim()
                val rootFolder = localServiceHelper.getRootFolder()
                val voiceFolder = java.io.File(rootFolder, "Voice Notes")
                val candidate = java.io.File(voiceFolder, audioName)
                if (candidate.exists()) audioFileToPlay = candidate
            } catch (e: Exception) { e.printStackTrace() }
        }

        if (audioFileToPlay == null && originalNoteId != null) {
            val noteFile = java.io.File(originalNoteId!!)
            val candidate = java.io.File(noteFile.parentFile, "${noteFile.nameWithoutExtension}.m4a")
            if (candidate.exists()) audioFileToPlay = candidate
        }

        if (audioFileToPlay != null) {
            playerContainer.visibility = View.VISIBLE
            setupAudioPlayer(audioFileToPlay!!)
            btnTranscribe?.visibility = View.VISIBLE
            btnTranscribe?.setOnClickListener {
                showTranscribeConfirmationDialog(audioFileToPlay!!)
            }
        } else {
            playerContainer.visibility = View.GONE
            btnTranscribe?.visibility = View.GONE
        }

        if (incomingTitle != null) {
            etTitle.setText(incomingTitle.removeSuffix(".md"))
            etContent.setText(incomingContent)
            switchToReaderMode()
        } else {
            if (incomingContent != null) etContent.setText(incomingContent)
            switchToEditorMode()
        }

        setupButtons()
        setupSmartKeyboard()

        tvReader.setOnClickListener { switchToEditorMode() }
        svReader.isFillViewport = true
        svReader.setOnClickListener { switchToEditorMode() }
        cardContent.setOnClickListener { switchToEditorMode() }
        ivNoteColor.setOnClickListener { showColorPopup(ivNoteColor) }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                saveAndExit()
            }
        })
    }

    // --- RENK UYGULAMA FONKSİYONU ---
    private fun applyColorToHeader(hexColor: String) {
        try {
            val colorInt = Color.parseColor(hexColor)

            // 1. Header Kartını (Arkayı) Boya
            cardHeader.setCardBackgroundColor(colorInt)

            // 2. İKON RENGİ (Zıtlık Ayarı)
            // Eğer kart rengi koyuysa ikon BEYAZ, açıksa SİYAH olsun.
            val isDark = androidx.core.graphics.ColorUtils.calculateLuminance(colorInt) < 0.5

            val contentColor = if (isDark) Color.WHITE else Color.BLACK
            val hintColor = if (isDark) Color.LTGRAY else Color.GRAY

            // Sadece senin ikonunu (src) boyuyoruz.
            // Arka plana dokunmuyoruz çünkü XML'de background'u kaldırdık.
            ivNoteColor.setColorFilter(contentColor)
            // 2. PAYLAŞ İKONUNU DA BOYA (YENİ)
            ivShare.setColorFilter(contentColor)

            // Başlık ve Tarih renkleri
            etTitle.setTextColor(contentColor)
            etTitle.setHintTextColor(hintColor)
            tvEditorDate.setTextColor(contentColor)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    private fun showColorPopup(anchorView: View) {
        val inflater = LayoutInflater.from(this)
        val popupView = inflater.inflate(R.layout.popup_color_picker, null)
        val container = popupView.findViewById<LinearLayout>(R.id.colorContainer)

        val colors = ColorStorage.colors

        val popupWindow = PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popupWindow.elevation = 10f
        popupWindow.isOutsideTouchable = true

        for (colorHex in colors) {
            val dot = View(this)
            val size = (32 * resources.displayMetrics.density).toInt()
            val margin = (6 * resources.displayMetrics.density).toInt()
            val params = LinearLayout.LayoutParams(size, size).apply { setMargins(margin, 0, margin, 0) }
            dot.layoutParams = params

            val bg = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.shape_circle)?.mutate() as? android.graphics.drawable.GradientDrawable

            try {
                val colorInt = Color.parseColor(colorHex)
                bg?.setColor(colorInt)

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

            dot.setOnClickListener {
                selectedColorHex = colorHex
                applyColorToHeader(colorHex) // Rengi anında uygula
                popupWindow.dismiss()
            }
            container.addView(dot)
        }

        popupWindow.showAsDropDown(anchorView, 0, -20)
    }

    private fun performManualTranscribe(audioFile: File) {
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("gemini_api_key", null)

        if (apiKey.isNullOrEmpty()) {
            Toast.makeText(this, "Please set API Key in Settings first", Toast.LENGTH_LONG).show()
            return
        }

        fabOverlay.setOnClickListener(null)
        showGeminiLoading("Transcribing audio...")

        lifecycleScope.launch {
            val result = GeminiHelper.transcribeAudio(apiKey, audioFile)

            if (result != null && !result.startsWith("Error:")) {
                hideGeminiLoading()

                var currentAudioFile = audioFile
                var currentAudioName = audioFile.name

                var parsedTitle = ""
                var parsedBody = result

                val regex = Regex("TITLE:\\s*(.*?)\\s*BODY:\\s*(.*)", RegexOption.DOT_MATCHES_ALL)
                val match = regex.find(result)

                if (match != null) {
                    parsedTitle = match.groupValues[1].trim()
                    parsedBody = match.groupValues[2].trim()
                } else {
                    parsedBody = result
                    parsedTitle = result.take(35).substringBefore("\n").replace(Regex("[\\\\/:*?\"<>|#]"), "").trim()
                }

                val currentTitle = etTitle.text.toString().trim()
                val isGenericTitle = currentTitle.isEmpty() ||
                        currentTitle.equals("Note", ignoreCase = true) ||
                        currentTitle.startsWith("Voice Note", ignoreCase = true) ||
                        currentTitle.startsWith("Audio Note", ignoreCase = true)

                if (isGenericTitle && parsedTitle.isNotEmpty()) {
                    etTitle.setText(parsedTitle)
                    val newFileName = "$parsedTitle.m4a"
                    val newFile = File(currentAudioFile.parent, newFileName)
                    if (newFileName != currentAudioName && !newFile.exists()) {
                        mediaPlayer?.release()
                        mediaPlayer = null
                        if (currentAudioFile.renameTo(newFile)) {
                            currentAudioFile = newFile
                            currentAudioName = newFileName
                            setupAudioPlayer(currentAudioFile)
                        } else {
                            setupAudioPlayer(currentAudioFile)
                        }
                    }
                }

                val newContent = "$parsedBody\n\nAudio Note: $currentAudioName"
                etContent.setText(newContent)
                etContent.setSelection(0)
                Toast.makeText(this@EditorActivity, "Transcription updated!", Toast.LENGTH_SHORT).show()
            } else {
                val errorMsg = result ?: "Unknown error"
                showGeminiError(errorMsg)
            }
        }
    }

    private fun setupAudioPlayer(file: File) {
        mediaPlayer = android.media.MediaPlayer()
        try {
            mediaPlayer?.setDataSource(file.absolutePath)
            mediaPlayer?.prepare()
            val duration = mediaPlayer?.duration ?: 0
            val min = duration / 1000 / 60
            val sec = (duration / 1000) % 60
            tvDuration.text = String.format("%02d:%02d", min, sec)
            seekBar.max = duration
        } catch (e: Exception) {
            playerContainer.visibility = View.GONE
            return
        }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val updater = object : Runnable {
            override fun run() {
                mediaPlayer?.let { mp ->
                    if (mp.isPlaying) {
                        seekBar.progress = mp.currentPosition
                        handler.postDelayed(this, 100)
                    }
                }
            }
        }
        btnPlayPause.setOnClickListener {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    mp.pause()
                    btnPlayPause.setImageResource(R.drawable.ic_play_circle)
                } else {
                    mp.start()
                    btnPlayPause.setImageResource(R.drawable.ic_pause_circle)
                    handler.post(updater)
                }
            }
        }
        mediaPlayer?.setOnCompletionListener {
            btnPlayPause.setImageResource(R.drawable.ic_play_circle)
            seekBar.progress = 0
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) mediaPlayer?.seekTo(p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun showGeminiLoading(message: String) {
        tvGeminiStatus.text = message
        fabOverlay.visibility = View.VISIBLE
        geminiLoadingContainer.visibility = View.VISIBLE
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
        lottieGemini.playAnimation()
    }

    private fun hideGeminiLoading() {
        fabOverlay.animate().alpha(0f).setDuration(300).withEndAction {
            fabOverlay.visibility = View.GONE
            fabOverlay.alpha = 0.95f
            fabOverlay.setOnClickListener(null)
        }.start()
        geminiLoadingContainer.animate().alpha(0f).setDuration(300).withEndAction {
            geminiLoadingContainer.visibility = View.GONE
            geminiLoadingContainer.alpha = 1f
            lottieGemini.cancelAnimation()
            lottieGemini.visibility = View.VISIBLE
            tvGeminiStatus.setTextColor(getColor(R.color.text_color))
            tvGeminiAction.visibility = View.GONE
            geminiLoadingContainer.setOnClickListener(null)
        }.start()
    }

    private fun showGeminiError(errorMsg: String) {
        lottieGemini.pauseAnimation()
        tvGeminiStatus.text = errorMsg
        tvGeminiStatus.setTextColor(android.graphics.Color.parseColor("#D32F2F"))
        tvGeminiAction.visibility = View.VISIBLE
        tvGeminiAction.text = "Tap anywhere to close"
        val closeAction = View.OnClickListener { hideGeminiLoading() }
        fabOverlay.setOnClickListener(closeAction)
        geminiLoadingContainer.setOnClickListener(closeAction)
    }

    private fun showTranscribeConfirmationDialog(audioFile: File) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_transcribe_confirm, null)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btnCancel)
        val btnConfirm = dialogView.findViewById<TextView>(R.id.btnConfirm)
        val dialog = android.app.AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            dialog.dismiss()
            performManualTranscribe(audioFile)
        }
        dialog.show()
    }

    private fun switchToReaderMode() {
        isEditMode = false
        val rawText = etContent.text.toString()
        markwon.setMarkdown(tvReader, rawText)

        etContent.visibility = View.GONE
        bottomBar.visibility = View.GONE
        svReader.visibility = View.VISIBLE
        updateCardBottomMargin(30)
    }

    private fun switchToEditorMode() {
        isEditMode = true
        svReader.visibility = View.GONE
        etContent.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        etContent.requestFocus()
        updateCardBottomMargin(12)
    }

    private fun updateCardBottomMargin(dp: Int) {
        val params = cardContent.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        val density = resources.displayMetrics.density
        params.bottomMargin = (dp * density).toInt()
        cardContent.layoutParams = params
    }

    private fun saveAndExit() {
        var title = etTitle.text.toString().trim()
        val content = etContent.text.toString().trim()
        if (title.isEmpty() && content.isEmpty()) {
            if (originalNoteId == null) {
                setResult(Activity.RESULT_CANCELED)
            } else {
                val resultIntent = Intent()
                resultIntent.putExtra("NOTE_ID", originalNoteId)
                resultIntent.putExtra("REQUEST_DELETE", true)
                setResult(Activity.RESULT_OK, resultIntent)
            }
            finish()
            return
        }
        if (title.isEmpty()) {
            val maxLength = 30
            val endIndex = if (content.length < maxLength) content.length else maxLength
            var generatedTitle = content.substring(0, endIndex)
            if (generatedTitle.contains("\n")) generatedTitle = generatedTitle.substringBefore("\n")
            generatedTitle = generatedTitle.replace(Regex("[\\\\/:*?\"<>|#]"), "")
            title = generatedTitle.trim()
            if (title.isEmpty()) title = "Note"
        }
        val resultIntent = Intent()
        resultIntent.putExtra("NOTE_TITLE", title)
        resultIntent.putExtra("NOTE_CONTENT", content)
        if (originalNoteId != null) resultIntent.putExtra("NOTE_ID", originalNoteId)
        if (selectedColorHex != null) resultIntent.putExtra("NOTE_COLOR", selectedColorHex)
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun setupButtons() {
        findViewById<TextView>(R.id.btnBold).setOnClickListener { insertFormatting("**", "**") }
        findViewById<TextView>(R.id.btnHeader).setOnClickListener { insertFormatting("# ", "") }
        findViewById<TextView>(R.id.btnList).setOnClickListener { insertFormatting("- ", "") }

        findViewById<TextView>(R.id.btnSave).setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etContent.windowToken, 0)
            switchToReaderMode()
        }
    }

    private fun insertFormatting(prefix: String, suffix: String) {
        val start = etContent.selectionStart
        val end = etContent.selectionEnd
        val text = etContent.text
        if (start != end) {
            text.insert(start, prefix)
            text.insert(end + prefix.length, suffix)
        } else {
            text.insert(start, prefix + suffix)
            etContent.setSelection(start + prefix.length)
        }
    }

    private fun setupSmartKeyboard() {
        etContent.addTextChangedListener(object : TextWatcher {
            private var isFormatting = false
            private var isDeletion = false

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                isDeletion = (count == 0 && before > 0)
            }

            override fun afterTextChanged(s: Editable?) {
                if (isFormatting || s == null) return

                val cursorPosition = etContent.selectionEnd
                if (cursorPosition == 0) return

                val lastChar = s[cursorPosition - 1]

                if (isDeletion) {
                    val textStr = s.toString()
                    var lineStart = textStr.lastIndexOf('\n', cursorPosition - 1) + 1
                    if (lineStart < 0) lineStart = 0
                    if (cursorPosition > lineStart) {
                        val currentLineContent = textStr.substring(lineStart, cursorPosition)
                        if (currentLineContent == "-" || currentLineContent == "*") {
                            isFormatting = true
                            s.delete(lineStart, cursorPosition)
                            isFormatting = false
                            return
                        }
                    }
                }

                if (lastChar == '\n') {
                    val textStr = s.toString()
                    val currentLineEnd = cursorPosition - 1
                    var prevLineStart = textStr.lastIndexOf('\n', currentLineEnd - 1) + 1
                    if (prevLineStart < 0) prevLineStart = 0
                    val prevLineContent = textStr.substring(prevLineStart, currentLineEnd)
                    val trimmed = prevLineContent.trim()

                    if (trimmed == "-" || trimmed == "*") {
                        isFormatting = true
                        s.delete(prevLineStart, cursorPosition)
                        isFormatting = false
                        return
                    }
                    else if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                        isFormatting = true
                        s.insert(cursorPosition, "- ")
                        isFormatting = false
                        return
                    }
                }
            }
        })

    }

    private fun shareNoteText() {
        val title = etTitle.text.toString().trim()
        val rawContent = etContent.text.toString()

        // 1. Önce "Audio Note: ..." satırını temizle
        val contentWithoutAudio = rawContent.replace(Regex("Audio Note:.*\\.m4a"), "").trim()

        // 2. Şimdi Markdown işaretlerini temizle (Yıldızlar, Kareler gitsin)
        val cleanContent = getCleanTextFromMarkdown(contentWithoutAudio)

        // 3. Başlık ve Temiz Metni Birleştir
        val textToShare = StringBuilder()
        if (title.isNotEmpty()) textToShare.append(title).append("\n\n")
        textToShare.append(cleanContent)

        if (textToShare.isBlank()) {
            Toast.makeText(this, "Nothing to share", Toast.LENGTH_SHORT).show()
            return
        }

        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, textToShare.toString())
            type = "text/plain"
        }
        startActivity(Intent.createChooser(shareIntent, "Share Note via"))
    }


    // Markdown formatını temizleyip düz metne çeviren yardımcı fonksiyon
    private fun getCleanTextFromMarkdown(markdown: String): String {
        var text = markdown

        // 1. Başlıkları Temizle (# Başlık -> Başlık)
        // (?m) satır başlarını algılamasını sağlar
        text = text.replace(Regex("(?m)^#{1,6}\\s+"), "")

        // 2. Kalın (Bold) (**text** -> text)
        text = text.replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")
        text = text.replace(Regex("__(.*?)__"), "$1")

        // 3. İtalik (*text* -> text)
        text = text.replace(Regex("\\*(.*?)\\*"), "$1")
        text = text.replace(Regex("_(.*?)_"), "$1")

        // 4. Üstü Çizili (~~text~~ -> text)
        text = text.replace(Regex("~~(.*?)~~"), "$1")

        // 5. Linkler ([Title](url) -> Title: url)
        // Linkleri tamamen silmek yerine okunabilir hale getiriyoruz
        text = text.replace(Regex("\\[([^\\]]+)\\]\\(([^\\)]+)\\)"), "$1 ($2)")

        // 6. Kod Blokları (```) ve Satır içi Kod (`)
        text = text.replace("```", "")
        text = text.replace("`", "")

        // 7. Liste İşaretleri (* Item veya - Item -> • Item)
        // Markdown yıldızlarını, düz metinde şık duran "•" (Bullet) işaretine çeviriyoruz.
        text = text.replace(Regex("(?m)^\\s*[*\\-]\\s+"), "• ")

        // 8. Blockquote (> text -> text)
        text = text.replace(Regex("(?m)^>\\s+"), "")

        return text.trim()
    }
}