package com.ghostyapps.heynotes

import android.animation.TimeAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class WaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barColor = Color.parseColor("#f32626")
    private val barGapDp = 3f
    private val barWidthDp = 5f
    private val cornerRadiusDp = 3f

    private data class Bar(
        var heightPercent: Float = 0f,
        val speed: Float = 0f,
        val offset: Float = 0f,
        val maxScale: Float = 1f
    )

    private val bars = ArrayList<Bar>()
    private val barRect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = barColor
        style = Paint.Style.FILL
    }

    private var isRecording = false
    private var targetAmplitude = 0f
    private var currentAmplitude = 0f
    private var phase = 0f

    private var barWidth = 0f
    private var barGap = 0f
    private var cornerRadius = 0f
    private var maxBarHeight = 0f
    private var minBarHeight = 0f

    // Delta Time ile FPS'den bağımsız animasyon
    private var lastFrameTime = 0L

    private val animator = TimeAnimator().apply {
        setTimeListener { _, _, deltaTime ->
            if (visibility == VISIBLE) {
                // Delta time milisaniye cinsinden geliyor, saniyeye çevir
                val deltaSeconds = deltaTime / 1000f

                // FPS'den bağımsız hız
                phase += deltaSeconds * 1.5f * PI.toFloat()

                // Overflow kontrolü
                if (phase > 2 * PI) {
                    phase -= (2 * PI).toFloat()
                }

                invalidate()
            }
        }
    }

    fun addAmplitude(amp: Int) {
        val rawNorm = (amp / 5000f).coerceIn(0f, 1f)
        targetAmplitude = sqrt(rawNorm)
    }

    fun setRecordingState(recording: Boolean) {
        isRecording = recording
        if (recording) {
            if (!animator.isRunning) {
                lastFrameTime = System.currentTimeMillis()
                animator.start()
            }
        } else {
            targetAmplitude = 0f
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isRecording) {
            if (!animator.isRunning) animator.start()
        } else {
            if (animator.isRunning) animator.cancel()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val density = resources.displayMetrics.density

        barWidth = barWidthDp * density
        barGap = barGapDp * density
        cornerRadius = cornerRadiusDp * density

        maxBarHeight = h * 0.8f
        minBarHeight = 6f * density

        val totalSpace = barWidth + barGap
        // --- DEĞİŞİKLİK BURADA ---
        // Ekran genişliğine sığan maksimum sayıdan 10 eksiltiyoruz.
        // onDraw zaten ortaladığı için sağdan 5, soldan 5 boşluk kalmış olacak.
        val maxPossibleCount = (w / totalSpace).toInt()
        val count = (maxPossibleCount - 10).coerceAtLeast(0)

        bars.clear()
        bars.ensureCapacity(count)

        val centerIndex = count / 2f

        for (i in 0 until count) {
            val dist = abs(i - centerIndex)
            val normDist = dist / centerIndex
            val scale = (1f - (normDist * 0.6f)).coerceAtLeast(0.4f)

            bars.add(Bar(
                speed = Random.nextFloat() * 0.20f + 0.05f,
                offset = Random.nextFloat() * 2 * PI.toFloat(),
                maxScale = scale
            ))
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // DÜZELTME: height / 3f yerine height / 2f yapıldı.
        // Artık çizim tam ortadan başlayacak, böylece barlar yukarı uzadığında kesilmeyecek.
        val centerY = height / 2f

        if (isRecording) {
            currentAmplitude += (targetAmplitude - currentAmplitude) * 0.3f
        } else {
            currentAmplitude += (0f - currentAmplitude) * 0.1f
        }

        val baseAmp = if (isRecording) max(currentAmplitude, 0.2f) else 0f

        val totalWidth = bars.size * (barWidth + barGap) - barGap
        var currentX = (width - totalWidth) / 2f

        for (bar in bars) {
            val oscillation = sin((phase * bar.speed) + bar.offset)
            val normalizedOsc = (oscillation + 1f) / 2f

            var targetH = maxBarHeight * baseAmp * normalizedOsc * bar.maxScale
            targetH = max(minBarHeight, targetH)

            bar.heightPercent += (targetH - bar.heightPercent) * 0.2f

            val halfH = bar.heightPercent / 2f
            barRect.set(currentX, centerY - halfH, currentX + barWidth, centerY + halfH)

            val alpha = (120 + (bar.heightPercent / maxBarHeight) * 135).toInt().coerceIn(120, 255)
            paint.alpha = alpha

            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, paint)

            currentX += barWidth + barGap
        }
    }}