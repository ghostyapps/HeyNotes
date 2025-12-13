package com.ghostyapps.heynotes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Match Status Bar
        window.statusBarColor = resources.getColor(R.color.header_background, theme)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        setContentView(R.layout.activity_about)

        // GitHub Link
        findViewById<TextView>(R.id.tvGithubLink).setOnClickListener {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/GhostyApps"))
            startActivity(browserIntent)
        }
    }
}