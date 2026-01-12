package com.ghostyapps.heynotes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class WelcomeActivity : AppCompatActivity() {

    private var isEditMode = false
    private var isPermissionStep = false // 2. aşama kontrolü

    // UI View Referansları
    private lateinit var ivLogo: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvDescription: TextView
    private lateinit var etName: EditText
    private lateinit var btnGetStarted: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // TEMA AYARLARI
        window.statusBarColor = resources.getColor(R.color.header_background, theme)
        val nightModeFlags = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        if (nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        setContentView(R.layout.activity_welcome)

        // View Tanımlamaları
        ivLogo = findViewById(R.id.ivLogo)
        tvTitle = findViewById(R.id.tvTitle)
        tvDescription = findViewById(R.id.tvDescription)
        etName = findViewById(R.id.etName)
        btnGetStarted = findViewById(R.id.btnGetStarted)

        // HATA DÜZELTİLDİ: <TextView> eklendi
        val tvFooter = findViewById<TextView>(R.id.tvFooter)

        // 1. Ayarlardan mı geldik kontrol et
        isEditMode = intent.getBooleanExtra("IS_EDIT_MODE", false)

        if (isEditMode) {
            setupUIForEdit()
        }

        // 2. Buton Mantığı
        btnGetStarted.setOnClickListener {
            if (isEditMode) {
                // Sadece isim değiştir ve çık
                saveNameAndExit()
            } else {
                if (!isPermissionStep) {
                    // AŞAMA 1: İsim girildi -> Kaydet ve 2. Aşamaya (İzin) Geç
                    val name = etName.text.toString().trim()
                    if (name.isNotEmpty()) {
                        saveNameLocally(name)
                        transitionToPermissionStep() // Arayüzü değiştir
                    } else {
                        Toast.makeText(this, "Please enter your name", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // AŞAMA 2: Zaten izin ekranındayız -> İzni İste
                    requestStoragePermission()
                }
            }
        }

        // GitHub Linki
        tvFooter.setOnClickListener {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/GhostyApps"))
            startActivity(browserIntent)
        }
    }

    // --- MODLARA GÖRE ARAYÜZ DEĞİŞİMLERİ ---

    private fun setupUIForEdit() {
        tvTitle.text = "Change Name"
        tvDescription.text = "Hey, you wanna change your name?"
        btnGetStarted.text = "Update"

        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val currentName = prefs.getString("user_name", "")
        etName.setText(currentName)
    }

    private fun transitionToPermissionStep() {
        isPermissionStep = true

        // 1. İsim kutusunu gizle (ConstraintLayout tasarımı bozmadan diğerlerini yukarı kaydırır)
        etName.visibility = View.GONE

        // 2. Metinleri İzin isteğine göre güncelle
        tvTitle.text = "Storage Access Needed"
        tvDescription.text = "Please allow 'All files access' because this app saves your notes directly to your local Documents folder for safety and easy backup."
        btnGetStarted.text = "Grant Access"

        // 3. İkonu klasör yap (Eğer hata verirse try-catch ile önledik)
        try {
            ivLogo.setImageResource(R.drawable.ic_folder_icon)
        } catch (e: Exception) {
            // İkon yoksa bozma, eski logo kalsın
        }
    }

    // --- KAYIT VE İZİN MANTIĞI ---

    private fun saveNameLocally(name: String) {
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("user_name", name).apply()
    }

    private fun saveNameAndExit() {
        val name = etName.text.toString().trim()
        if (name.isNotEmpty()) {
            saveNameLocally(name)
            finish()
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.addCategory("android.intent.category.DEFAULT")
                intent.data = Uri.parse(String.format("package:%s", applicationContext.packageName))
                startActivityForResult(intent, 2296)
            } catch (e: Exception) {
                val intent = Intent()
                intent.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                startActivityForResult(intent, 2296)
            }
        } else {
            // Android 11 altı için direkt bitir
            finishOnboarding()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2296) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    finishOnboarding()
                } else {
                    Toast.makeText(this, "Permission required to proceed.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun finishOnboarding() {
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        // ONBOARDING BİTTİ İŞARETİNİ BURADA KOYUYORUZ
        prefs.edit().putBoolean("is_onboarding_done", true).apply()

        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }
}