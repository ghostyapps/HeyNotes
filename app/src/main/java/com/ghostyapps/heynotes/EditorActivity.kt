package com.ghostyapps.heynotes

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin

class EditorActivity : AppCompatActivity() {

    // UI Components
    private lateinit var etTitle: EditText
    private lateinit var etContent: EditText
    private lateinit var tvReader: TextView
    private lateinit var svReader: ScrollView
    private lateinit var bottomBar: LinearLayout
    private lateinit var ivNoteColor: ImageView // <--- NEW

    // Helpers
    private lateinit var markwon: Markwon
    private lateinit var colorStorage: ColorStorage // <--- NEW

    // State
    private var isEditMode = true
    private var originalNoteId: String? = null
    private var selectedColorHex: String? = null // <--- Track Color

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // YENİ KOD (YAPIŞTIRIN)
// 1. Rengi ayarla
        window.statusBarColor = resources.getColor(R.color.header_background, theme)

// 2. Modu kontrol et (Karanlık mı Aydınlık mı?)
        val nightModeFlags = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        if (nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
            // GECE MODU: Status Bar ikonlarını Beyaz yap (Flag'i temizle)
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        } else {
            // GÜNDÜZ MODU: Status Bar ikonlarını Siyah yap
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        setContentView(R.layout.activity_editor)

        // Keyboard Fix
        val rootView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, 0, 0, if (ime.bottom > 0) ime.bottom else sys.bottom)
            WindowInsetsCompat.CONSUMED
        }

        // Init
        colorStorage = ColorStorage(this)
        etTitle = findViewById(R.id.etTitle)
        etContent = findViewById(R.id.etContent)
        tvReader = findViewById(R.id.tvReader)
        svReader = findViewById(R.id.scrollViewReader)
        bottomBar = findViewById(R.id.bottomBar)
        ivNoteColor = findViewById(R.id.ivNoteColor) // <--- Init View

        markwon = Markwon.builder(this)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TaskListPlugin.create(this))
            .build()

        // Load Data
        originalNoteId = intent.getStringExtra("NOTE_ID")
        val incomingTitle = intent.getStringExtra("NOTE_TITLE")
        val incomingContent = intent.getStringExtra("NOTE_CONTENT")

        // --- LOAD COLOR ---
        if (originalNoteId != null) {
            val savedColor = colorStorage.getColor(originalNoteId!!)
            if (savedColor != null) {
                ivNoteColor.setColorFilter(savedColor)
                // We need the Hex string to pass back later, simple convert:
                selectedColorHex = String.format("#%06X", (0xFFFFFF and savedColor))
            }
        }

        if (incomingTitle != null) {
            etTitle.setText(incomingTitle.removeSuffix(".md"))
            etContent.setText(incomingContent)
            switchToReaderMode()
        } else {
            switchToEditorMode()
        }

        setupButtons()
        setupSmartKeyboard()

        tvReader.setOnClickListener { switchToEditorMode() }
        svReader.setOnClickListener { switchToEditorMode() }

        // --- COLOR CLICK LISTENER ---
        ivNoteColor.setOnClickListener {
            showColorPopup(ivNoteColor)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                saveAndExit()
            }
        })
    }

    // --- POPUP LOGIC (Reuse) ---
    private fun showColorPopup(anchorView: View) {
        val inflater = LayoutInflater.from(this)
        val popupView = inflater.inflate(R.layout.popup_color_picker, null)
        val container = popupView.findViewById<LinearLayout>(R.id.colorContainer)

        val colors = listOf("#BDBDBD", "#616161", "#EF5350", "#FFA726", "#FFEE58", "#66BB6A", "#42A5F5", "#AB47BC", "#EC407A")

        val popupWindow = android.widget.PopupWindow(
            popupView,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popupWindow.elevation = 10f

        for (colorHex in colors) {
            val dot = View(this)
            val size = (24 * resources.displayMetrics.density).toInt()
            val margin = (6 * resources.displayMetrics.density).toInt()
            val params = LinearLayout.LayoutParams(size, size)
            params.setMargins(margin, 0, margin, 0)
            dot.layoutParams = params

            val bg = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.shape_circle)?.mutate()
            bg?.setTint(Color.parseColor(colorHex))
            dot.background = bg

            dot.setOnClickListener {
                // Update UI immediately
                ivNoteColor.setColorFilter(Color.parseColor(colorHex))
                selectedColorHex = colorHex
                popupWindow.dismiss()
            }
            container.addView(dot)
        }
        popupWindow.showAsDropDown(anchorView, 0, -20)
    }

    private fun switchToReaderMode() {
        isEditMode = false
        val rawText = etContent.text.toString()
        markwon.setMarkdown(tvReader, rawText)
        etContent.visibility = View.GONE
        bottomBar.visibility = View.GONE
        svReader.visibility = View.VISIBLE
    }

    private fun switchToEditorMode() {
        isEditMode = true
        svReader.visibility = View.GONE
        etContent.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        etContent.requestFocus()
    }

    private fun saveAndExit() {
        var title = etTitle.text.toString().trim()
        val content = etContent.text.toString().trim()

        // 1. CHECK FOR EMPTY NOTE
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

        // 2. AUTO-GENERATE TITLE (With Sanitization)
        if (title.isEmpty()) {
            val maxLength = 30 // Increased slightly to accommodate cleanup
            val endIndex = if (content.length < maxLength) content.length else maxLength

            var generatedTitle = content.substring(0, endIndex)

            // Stop at new line
            if (generatedTitle.contains("\n")) {
                generatedTitle = generatedTitle.substringBefore("\n")
            }

            // --- FIX: SANITIZE FILENAME ---
            // 1. Replace illegal characters with empty string: \ / : * ? " < > |
            // 2. Remove Markdown symbols like # (headers) to keep filename clean
            generatedTitle = generatedTitle.replace(Regex("[\\\\/:*?\"<>|#]"), "")

            title = generatedTitle.trim()

            // Fallback if the sanitization wiped everything (e.g. user typed only "*****")
            if (title.isEmpty()) {
                title = "Note"
            }
        }

        // 3. PREPARE RESULT
        val resultIntent = Intent()
        resultIntent.putExtra("NOTE_TITLE", title)
        resultIntent.putExtra("NOTE_CONTENT", content)

        if (originalNoteId != null) {
            resultIntent.putExtra("NOTE_ID", originalNoteId)
        }

        if (selectedColorHex != null) {
            resultIntent.putExtra("NOTE_COLOR", selectedColorHex)
        }

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
            saveAndExit()
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
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isFormatting || s == null) return
                val cursorPosition = etContent.selectionEnd
                if (cursorPosition > 0 && s.isNotEmpty() && s[cursorPosition - 1] == ' ') {
                    val textAfter = s.toString().substring(cursorPosition)
                    if (textAfter.startsWith("**")) {
                        isFormatting = true
                        s.delete(cursorPosition - 1, cursorPosition)
                        val newPos = cursorPosition + 1
                        if (newPos <= s.length) {
                            etContent.setSelection(newPos)
                            s.insert(newPos, " ")
                            etContent.setSelection(newPos + 1)
                        }
                        isFormatting = false
                    } else if ((textAfter.startsWith("*") && !textAfter.startsWith("**")) || textAfter.startsWith("_")) {
                        isFormatting = true
                        s.delete(cursorPosition - 1, cursorPosition)
                        val newPos = cursorPosition
                        if (newPos <= s.length) {
                            etContent.setSelection(newPos)
                            s.insert(newPos, " ")
                            etContent.setSelection(newPos + 1)
                        }
                        isFormatting = false
                    }
                }
            }
        })
    }
}