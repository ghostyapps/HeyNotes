package com.ghostyapps.heynotes

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.airbnb.lottie.LottieAnimationView
import java.io.File
import java.io.IOException

// Sınıf ismini dosya ismiyle EŞİTLEDİK
class VoiceRecorderDialog : AppCompatActivity() {

    private var isRecording = false
    private var isPaused = false

    private var timer: CountDownTimer? = null
    private var secondsElapsed = 0
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null

    // UI Bileşenleri
    private lateinit var tvTimer: TextView
    private lateinit var btnRecordAction: View
    private lateinit var tvRecordLabel: TextView
    private lateinit var lottieWaveInside: LottieAnimationView
    private lateinit var btnCancelAction: View
    private lateinit var btnSaveAction: View

    // Ana Animasyon
    private lateinit var lottieRecordingPulse: LottieAnimationView

    // İzin Yöneticisi
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(this, "Permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Microphone permission is required.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Status Bar Ayarları
        window.statusBarColor = ContextCompat.getColor(this, R.color.background_color)
        val isNightMode = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (!isNightMode) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }

        setContentView(R.layout.dialog_voice_recorder)

        // View Bağlamaları
        tvTimer = findViewById(R.id.tvTimer)
        btnRecordAction = findViewById(R.id.btnRecordAction)
        tvRecordLabel = findViewById(R.id.tvRecordLabel)
        lottieWaveInside = findViewById(R.id.lottieWaveInside)
        btnCancelAction = findViewById(R.id.btnCancelAction)
        btnSaveAction = findViewById(R.id.btnSaveAction)
        lottieRecordingPulse = findViewById(R.id.lottieRecordingPulse)

        // Geçici Dosya
        val tempDir = File(cacheDir, "temp_voice")
        if (!tempDir.exists()) tempDir.mkdirs()
        audioFile = File(tempDir, "temp_recording.m4a")

        setupListeners()
        checkPermissions()
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun setupListeners() {
        btnRecordAction.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                if (!isRecording) {
                    startRecording()
                } else {
                    if (isPaused) resumeRecording() else pauseRecording()
                }
            } else {
                checkPermissions()
            }
        }

        btnCancelAction.setOnClickListener {
            if (isRecording || (audioFile != null && audioFile!!.exists())) {
                showCancelConfirmation()
            } else {
                finish()
            }
        }

        btnSaveAction.setOnClickListener {
            if (isRecording || isPaused) {
                stopRecording(shouldSave = true)
            } else {
                Toast.makeText(this, "No recording to save", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startRecording() {
        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128000)
            setAudioSamplingRate(44100)
            setOutputFile(audioFile!!.absolutePath)
            try {
                prepare()
                start()
            } catch (e: IOException) {
                e.printStackTrace()
                Toast.makeText(this@VoiceRecorderDialog, "Recording failed", Toast.LENGTH_SHORT).show()
                return
            }
        }

        lottieRecordingPulse.visibility = View.VISIBLE
        lottieRecordingPulse.playAnimation()
        isRecording = true
        isPaused = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tvRecordLabel.visibility = View.GONE
        lottieWaveInside.visibility = View.VISIBLE
        lottieWaveInside.playAnimation()

        secondsElapsed = 0
        startTimer()
    }

    private fun pauseRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) mediaRecorder?.pause()
        isPaused = true
        timer?.cancel()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lottieRecordingPulse.pauseAnimation()
        lottieWaveInside.pauseAnimation()
        lottieWaveInside.visibility = View.GONE
        tvRecordLabel.text = "PAUSED"
        tvRecordLabel.visibility = View.VISIBLE
    }

    private fun resumeRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) mediaRecorder?.resume()
        isPaused = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startTimer()
        lottieRecordingPulse.visibility = View.VISIBLE
        lottieRecordingPulse.resumeAnimation()
        tvRecordLabel.visibility = View.GONE
        lottieWaveInside.visibility = View.VISIBLE
        lottieWaveInside.resumeAnimation()
    }

    private fun startTimer() {
        timer?.cancel()
        timer = object : CountDownTimer(Long.MAX_VALUE, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                secondsElapsed++
                val minutes = secondsElapsed / 60
                val seconds = secondsElapsed % 60
                tvTimer.text = String.format("%02d:%02d", minutes, seconds)
            }
            override fun onFinish() {}
        }.start()
    }

    private fun stopRecording(shouldSave: Boolean) {
        if (isRecording || isPaused) {
            try {
                mediaRecorder?.stop()
                mediaRecorder?.release()
            } catch (e: Exception) { e.printStackTrace() }
            mediaRecorder = null
            isRecording = false
            isPaused = false
            timer?.cancel()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            lottieWaveInside.cancelAnimation()
            lottieRecordingPulse.cancelAnimation()
            lottieRecordingPulse.visibility = View.INVISIBLE
        }

        if (shouldSave && audioFile != null && audioFile!!.exists()) {
            val resultIntent = Intent()
            resultIntent.putExtra("AUDIO_PATH", audioFile!!.absolutePath)
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        } else {
            try { audioFile?.delete() } catch (e: Exception) {}
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    private fun showCancelConfirmation() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_delete_confirm, null)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tvMessage)
        val btnDelete = dialogView.findViewById<TextView>(R.id.btnDelete)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btnCancel)
        tvMessage.text = "Discard this recording?"
        btnDelete.text = "Discard"
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        btnDelete.setOnClickListener { stopRecording(shouldSave = false); dialog.dismiss() }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isRecording || isPaused) showCancelConfirmation() else super.onBackPressed()
    }
}