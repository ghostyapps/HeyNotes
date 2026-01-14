package com.ghostyapps.heynotes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

class SettingsActivity : AppCompatActivity() {

    private lateinit var tvGeminiInstructions: TextView

    // PIN doğrulama durumunu takip etmek için
    private var isVerifyingOldPin = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // --- STATUS BAR AYARLARI ---
        window.statusBarColor = ContextCompat.getColor(this, R.color.background_color)
        val isNightMode = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (!isNightMode) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        // ---------------------------

        setContentView(R.layout.activity_settings)

        // --- DİNAMİK EKRAN AYARI (YENİ KOD) ---
        val headerContainer = findViewById<android.widget.LinearLayout>(R.id.headerContainer)
        val rootLayout = findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.settingsRoot)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())

            // Sadece HEADER'ın üstüne padding veriyoruz (Tıpkı XML'de 68dp verdiğimiz gibi)
            // Ama bu sefer sistem ne kadar diyorsa o kadar veriyoruz (örn: 50dp + ekstra 10dp)
            headerContainer.setPadding(
                headerContainer.paddingLeft,
                bars.top + 20, // +20 biraz nefes payı bırakır, XML'deki gibi
                headerContainer.paddingRight,
                headerContainer.paddingBottom
            )

            // Alt bar (Navigasyon) çubuğunun altında içerik kalmasın diye root'a alt padding ver
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bars.bottom)

            insets
        }
        // --------------------------------------

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        tvGeminiInstructions = findViewById(R.id.tvGeminiInstructions)

        setupNameSettings()
        setupGeminiSettings()
        setupPinSettings() // PIN ayarları buraya eklendi
        setupInstructionsText()
    }

    // --- PIN AYARLARI BAŞLANGIÇ ---

    private fun setupPinSettings() {
        // activity_settings.xml içinde bu ID'ye sahip bir view (TextView/Button) olmalı
        val btnChangePin = findViewById<View>(R.id.btnChangePin)

        btnChangePin.setOnClickListener {
            val savedPin = getSavedPin()

            if (savedPin.isEmpty()) {
                // Hiç PIN yoksa direkt yeni oluşturma moduna geç
                isVerifyingOldPin = false
                showPinDialog(title = "Set New PIN")
            } else {
                // PIN varsa önce doğrulama iste
                isVerifyingOldPin = true
                showPinDialog(title = "Enter Old PIN")
            }
        }
    }

    private fun showPinDialog(title: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_pin_entry, null)

        val etPin = dialogView.findViewById<EditText>(R.id.etPin)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvTitle)
        val btnConfirm = dialogView.findViewById<View>(R.id.btnConfirm)
        // 1. EKLENEN KISIM: Cancel butonunu bul
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        tvTitle?.text = title

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // 2. EKLENEN KISIM: Tıklanınca dialog'u kapat
        btnCancel?.setOnClickListener {
            dialog.dismiss()
        }

        btnConfirm?.setOnClickListener {
            val enteredPin = etPin.text.toString()
            if (enteredPin.length < 4) {
                Toast.makeText(this, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show()
            } else {
                handlePinInput(enteredPin, dialog)
            }
        }

        dialog.show()
    }
    private fun handlePinInput(enteredPin: String, dialog: AlertDialog) {
        val currentSavedPin = getSavedPin()

        if (isVerifyingOldPin) {
            // AŞAMA 1: Eski PIN kontrolü
            if (enteredPin == currentSavedPin) {
                dialog.dismiss()
                isVerifyingOldPin = false

                // Kullanıcı anlasın diye hafif gecikme ile yeni ekranı aç
                android.os.Handler(Looper.getMainLooper()).postDelayed({
                    showPinDialog(title = "Enter New PIN")
                }, 200)

            } else {
                Toast.makeText(this, "Old PIN is incorrect", Toast.LENGTH_SHORT).show()
                // İstersen EditText'i burada temizleyebilirsin
            }
        } else {
            // AŞAMA 2: Yeni PIN kaydı
            saveNewPin(enteredPin)
            dialog.dismiss()
            Toast.makeText(this, "PIN changed successfully", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getSavedPin(): String {
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        // MainActivity'de 'master_pin' olarak kaydettiğin için burada da aynısını kullanmalıyız.
        return prefs.getString("master_pin", "") ?: ""
    }

    private fun saveNewPin(pin: String) {
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("master_pin", pin).apply()
    }

    // --- PIN AYARLARI BİTİŞ ---

    private fun setupNameSettings() {
        val etUserName = findViewById<EditText>(R.id.etUserName)
        val btnSaveName = findViewById<TextView>(R.id.btnSaveName)
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)

        etUserName.setText(prefs.getString("user_name", ""))

        btnSaveName.setOnClickListener {
            val name = etUserName.text.toString().trim()
            if (name.isNotEmpty()) {
                prefs.edit().putString("user_name", name).apply()
                Toast.makeText(this, "Name Saved", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please enter a name", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupGeminiSettings() {
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)

        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        val btnSave = findViewById<TextView>(R.id.btnSaveApiKey)
        val btnGetApiKey = findViewById<TextView>(R.id.btnGetApiKey)

        val switchGemini = findViewById<SwitchCompat>(R.id.switchGemini)
        val inputsContainer = findViewById<LinearLayout>(R.id.llGeminiInputs)

        val currentKey = prefs.getString("gemini_api_key", "")
        etApiKey.setText(currentKey)

        val isEnabled = prefs.getBoolean("gemini_enabled", false)
        switchGemini.isChecked = isEnabled
        updateGeminiUIState(isEnabled, inputsContainer, etApiKey, btnSave, btnGetApiKey)

        switchGemini.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("gemini_enabled", isChecked).apply()
            updateGeminiUIState(isChecked, inputsContainer, etApiKey, btnSave, btnGetApiKey)
        }

        btnSave.setOnClickListener {
            val key = etApiKey.text.toString().trim()
            if (key.isNotEmpty()) {
                prefs.edit().putString("gemini_api_key", key).apply()
                Toast.makeText(this, "API Key Saved", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please enter a key", Toast.LENGTH_SHORT).show()
            }
        }

        btnGetApiKey.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/app/apikey"))
            startActivity(intent)
        }
    }

    private fun updateGeminiUIState(
        isEnabled: Boolean,
        container: LinearLayout,
        et: EditText,
        btnSave: TextView,
        btnGet: TextView
    ) {
        if (isEnabled) {
            container.alpha = 1.0f
            et.isEnabled = true
            btnSave.isEnabled = true
            btnGet.isEnabled = true
            tvGeminiInstructions.isEnabled = true
        } else {
            container.alpha = 0.4f
            et.isEnabled = false
            btnSave.isEnabled = false
            btnGet.isEnabled = false
            tvGeminiInstructions.isEnabled = false
        }
    }

    private fun setupInstructionsText() {
        try {
            val htmlContent = getString(R.string.how_to_get_key_desc)
            val formattedText = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                Html.fromHtml(htmlContent, Html.FROM_HTML_MODE_COMPACT)
            } else {
                Html.fromHtml(htmlContent)
            }
            tvGeminiInstructions.text = formattedText
            tvGeminiInstructions.movementMethod = LinkMovementMethod.getInstance()
        } catch (e: Exception) {
            tvGeminiInstructions.text = "To use automatic transcription, please provide a Google Gemini API Key."
        }
    }
}