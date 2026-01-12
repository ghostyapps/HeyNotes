package com.ghostyapps.heynotes

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class HolePunchView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) // Oyma modu
    }

    // Delikleri oluşturacak resim (Senin microphone_holes.xml)
    private val maskDrawable = ContextCompat.getDrawable(context, R.drawable.ic_microphone_holes)

    // Mikrofonun gövde rengi (Kart rengi)
    private val bodyColor = ContextCompat.getColor(context, R.color.card_surface)

    override fun onDraw(canvas: Canvas) {
        // 1. Katman aç (Şart!)
        val saveCount = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)

        // 2. Tüm alanı gövde rengine boya (Dikdörtgen gibi)
        paint.color = bodyColor
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        // 3. Resmini (noktaları) silgi olarak kullan
        maskDrawable?.let {
            it.setBounds(0, 0, width, height)
            // Resmi "DST_OUT" modunda çizemediğimiz için (VectorDrawable),
            // Önce resmi normal çizip sonra modifiye edemeyiz.
            // Bu yüzden "Bitmap"e çevirip basmak en garantisidir.

            // Eğer performans sorunu olursa burası optimize edilebilir
            // ama şu an çalışması önemli.
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val c = Canvas(bitmap)
            it.draw(c) // Resmi bitmap'e çiz (Siyah noktalar)

            // Şimdi bu bitmap'i silgi olarak kullan
            canvas.drawBitmap(bitmap, 0f, 0f, eraserPaint)
        }

        canvas.restoreToCount(saveCount)
    }
}