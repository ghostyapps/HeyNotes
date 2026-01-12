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
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope

// Markwon Core & Plugins
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin

import java.io.File

// Gemini ve Coroutines Importları
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class EditorActivity : AppCompatActivity() {

    // UI Components
    private lateinit var etTitle: EditText
    private lateinit var etContent: EditText
    private lateinit var tvReader: TextView
    private lateinit var svReader: ScrollView

    private lateinit var bottomBar: View
    private lateinit var cardContent: View

    private lateinit var ivNoteColor: ImageView

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
    private var selectedColorHex: String? = null

    private lateinit var fabOverlay: View
    private lateinit var geminiLoadingContainer: LinearLayout
    private lateinit var lottieGemini: com.airbnb.lottie.LottieAnimationView
    private lateinit var tvGeminiStatus: TextView
    private lateinit var tvGeminiAction: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-Edge
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        val windowInsetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        val isNightMode = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        windowInsetsController.isAppearanceLightStatusBars = !isNightMode
        windowInsetsController.isAppearanceLightNavigationBars = !isNightMode

        setContentView(R.layout.activity_editor)

        // Keyboard Fix
        val rootView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(0, 0, 0, if (ime.bottom > 0) ime.bottom else 0)
            WindowInsetsCompat.CONSUMED
        }

        val tvEditorDate = findViewById<TextView>(R.id.tvEditorDate)

        // Tarih Formatı
        val timestamp = intent.getLongExtra("NOTE_TIMESTAMP", 0L)
        val fullFormat = java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.getDefault())

        if (timestamp > 0) {
            tvEditorDate.text = "Last edited: " + fullFormat.format(java.util.Date(timestamp))
        } else {
            tvEditorDate.text = fullFormat.format(java.util.Date())
        }

        // Init Components
        colorStorage = ColorStorage(this)
        etTitle = findViewById(R.id.etTitle)
        etContent = findViewById(R.id.etContent)

        // Klavye Ayarı
        etContent.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

        cardContent = findViewById(R.id.cardContent)

        tvReader = findViewById(R.id.tvReader)
        svReader = findViewById(R.id.scrollViewReader)
        bottomBar = findViewById(R.id.cardBottomToolbar)
        ivNoteColor = findViewById(R.id.ivNoteColor)

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

        // --- MARKWON BUILDER (Standart) ---
        markwon = Markwon.builder(this)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TaskListPlugin.create(this))
            .build()

        // Load Data
        originalNoteId = intent.getStringExtra("NOTE_ID")
        val incomingTitle = intent.getStringExtra("NOTE_TITLE")
        val incomingContent = intent.getStringExtra("NOTE_CONTENT")

        // Load Color
        if (originalNoteId != null) {
            val savedColor = colorStorage.getColor(originalNoteId!!)
            if (savedColor != null) {
                ivNoteColor.setColorFilter(savedColor)
                selectedColorHex = String.format("#%06X", (0xFFFFFF and savedColor))
            }
        }

        // Audio Note Search
        var audioFileToPlay: File? = null
        if (incomingContent != null && incomingContent.contains("Audio Note:")) {
            try {
                val audioName = incomingContent.substringAfter("Audio Note: ").substringBefore("\n").trim()
                val candidate = File(filesDir, "voice_notes/$audioName")
                if (candidate.exists()) audioFileToPlay = candidate
            } catch (e: Exception) { }
        }

        if (audioFileToPlay == null && originalNoteId != null) {
            val noteFile = File(originalNoteId!!)
            val candidate = File(noteFile.parentFile, "${noteFile.nameWithoutExtension}.m4a")
            if (candidate.exists()) audioFileToPlay = candidate
        }

        if (audioFileToPlay != null) {
            playerContainer.visibility = View.VISIBLE
            setupAudioPlayer(audioFileToPlay!!)
            if (btnTranscribe != null) {
                btnTranscribe.visibility = View.VISIBLE
                btnTranscribe.setOnClickListener {
                    showTranscribeConfirmationDialog(audioFileToPlay!!)
                }
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

        // --- TIKLAMA İLE DÜZENLEME MODU ---
        tvReader.setOnClickListener { switchToEditorMode() }
        svReader.isFillViewport = true
        svReader.setOnClickListener { switchToEditorMode() }
        cardContent.setOnClickListener { switchToEditorMode() }
        ivNoteColor.setOnClickListener { showColorPopup(ivNoteColor) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                saveAndExit()
            }
        })
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

    private fun showColorPopup(anchorView: View) {
        val inflater = LayoutInflater.from(this)
        val popupView = inflater.inflate(R.layout.popup_color_picker, null)
        val container = popupView.findViewById<LinearLayout>(R.id.colorContainer)
        val colors = listOf("#BDBDBD", "#616161", "#EF5350", "#FFA726", "#FFEE58", "#66BB6A", "#42A5F5", "#AB47BC", "#EC407A")
        val popupWindow = PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popupWindow.elevation = 10f
        for (colorHex in colors) {
            val dot = View(this)
            val size = (24 * resources.displayMetrics.density).toInt()
            val margin = (6 * resources.displayMetrics.density).toInt()
            val params = LinearLayout.LayoutParams(size, size).apply { setMargins(margin, 0, margin, 0) }
            dot.layoutParams = params
            val bg = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.shape_circle)?.mutate()
            bg?.setTint(Color.parseColor(colorHex))
            dot.background = bg
            dot.setOnClickListener {
                ivNoteColor.setColorFilter(Color.parseColor(colorHex))
                selectedColorHex = colorHex
                popupWindow.dismiss()
            }
            container.addView(dot)
        }
        popupWindow.showAsDropDown(anchorView, 0, -20)
    }

    // --- DEĞİŞTİRİLEN KISIM: READER MODE ---
    private fun switchToReaderMode() {
        isEditMode = false
        val rawText = etContent.text.toString()
        markwon.setMarkdown(tvReader, rawText)

        etContent.visibility = View.GONE
        bottomBar.visibility = View.GONE
        svReader.visibility = View.VISIBLE

        // --- YENİ EKLENEN KISIM ---
        // Araç çubuğu gidince kart aşağı yapışmasın diye boşluğu artırıyoruz (50dp)
        updateCardBottomMargin(30)
        // --------------------------
    }
    private fun switchToEditorMode() {
        isEditMode = true
        svReader.visibility = View.GONE
        etContent.visibility = View.VISIBLE

        // Düzenleme modunda araç çubuğunu geri getir
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

        // --- DEĞİŞTİRİLEN KISIM: Done Butonu ---
        findViewById<TextView>(R.id.btnSave).setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etContent.windowToken, 0)

            // saveAndExit() YERİNE switchToReaderMode()
            // Böylece çıkış yapmaz, okuma moduna geçer.
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

    // --- SMART KEYBOARD (SADECE LİSTE YÖNETİMİ) ---
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

                // ÖZELLİK 1: BACKSPACE İLE MADDE SİLME (Listenin başındayken silince maddeyi kaldırır)
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

                // ÖZELLİK 2: ENTER İLE OTOMATİK LİSTE (Alt satıra geçince madde ekler)
                if (lastChar == '\n') {
                    val textStr = s.toString()
                    val currentLineEnd = cursorPosition - 1
                    var prevLineStart = textStr.lastIndexOf('\n', currentLineEnd - 1) + 1
                    if (prevLineStart < 0) prevLineStart = 0
                    val prevLineContent = textStr.substring(prevLineStart, currentLineEnd)
                    val trimmed = prevLineContent.trim()

                    // Eğer satırda sadece tire varsa ve enter'a basıldıysa listeyi bitir
                    if (trimmed == "-" || trimmed == "*") {
                        isFormatting = true
                        s.delete(prevLineStart, cursorPosition)
                        isFormatting = false
                        return
                    }
                    // Eğer dolu bir madde ise alt satıra tire ekle
                    else if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                        isFormatting = true
                        s.insert(cursorPosition, "- ")
                        isFormatting = false
                        return
                    }
                }

                // ÖZELLİK 3 (Space ile dışarı atma) TAMAMEN KALDIRILDI.
                // Artık **kalın yazı yazarken** boşluğa basınca dışarı atmayacak.
            }
        })
    }}