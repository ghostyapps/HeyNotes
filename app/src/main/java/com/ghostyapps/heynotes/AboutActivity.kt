package com.ghostyapps.heynotes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView // ImageView import edildi
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat // ContextCompat eklendi

class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val themeMode = prefs.getInt("theme_mode", androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(themeMode)

        super.onCreate(savedInstanceState)

        // Status Bar Rengini (Settings ile aynı mantıkta) ayarla
        window.statusBarColor = ContextCompat.getColor(this, R.color.background_color)

        val isNightMode = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (!isNightMode) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }

        setContentView(R.layout.activity_about)

        // --- BACK BUTTON İŞLEVİ (EKLENDİ) ---
        // XML'deki id'si btnBack olan ImageView'ı bulup tıklanınca finish() çağırıyoruz.
        findViewById<ImageView>(R.id.btnBack).setOnClickListener {
            finish()
        }

        // --- GITHUB LINK ISLEVI ---
        val tvGithubLink = findViewById<TextView>(R.id.tvGithubLink)
        tvGithubLink.setOnClickListener {
            val url = getString(R.string.app_github_link)
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(url)
            startActivity(intent)
        }
    }
}