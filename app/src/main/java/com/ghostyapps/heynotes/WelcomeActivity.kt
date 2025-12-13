package com.ghostyapps.heynotes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class WelcomeActivity : AppCompatActivity() {

    private var isEditMode = false // Track if we are editing

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
        setContentView(R.layout.activity_welcome)

        val etName = findViewById<EditText>(R.id.etName)
        val btnGetStarted = findViewById<TextView>(R.id.btnGetStarted)
        val tvFooter = findViewById<TextView>(R.id.tvFooter)
        val tvTitle = findViewById<TextView>(R.id.tvTitle)
        val tvDescription = findViewById<TextView>(R.id.tvDescription)

        // 1. Check for Edit Mode Flag
        isEditMode = intent.getBooleanExtra("IS_EDIT_MODE", false)

        if (isEditMode) {
            // --- CUSTOMIZE FOR EDITING ---
            tvTitle.text = "Change Name"
            tvDescription.text = "Hey, you wanna change your name?"
            btnGetStarted.text = "Update"

            // Pre-fill current name
            val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            val currentName = prefs.getString("user_name", "")
            etName.setText(currentName)
        }

        // 2. Button Logic
        btnGetStarted.setOnClickListener {
            val name = etName.text.toString().trim()
            if (name.isNotEmpty()) {
                saveUserAndProceed(name)
            } else {
                Toast.makeText(this, "Please enter your name", Toast.LENGTH_SHORT).show()
            }
        }

        // 3. GitHub Link Logic
        tvFooter.setOnClickListener {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/GhostyApps"))
            startActivity(browserIntent)
        }
    }

    private fun saveUserAndProceed(name: String) {
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("user_name", name).apply()

        if (isEditMode) {
            // If editing, just close this screen to go back to Main
            finish()
        } else {
            // If fresh install, launch Main
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
        }
    }
}