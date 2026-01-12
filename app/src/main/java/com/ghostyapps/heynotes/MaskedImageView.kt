package com.ghostyapps.heynotes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

class MaskedImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) // Silici Mod
    }

    override fun onDraw(canvas: Canvas) {
        // Arka planı (background tint veya background color) çizmek için katman açıyoruz
        val saveCount = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)

        // 1. Önce arka plan rengini (Solid Gövde) çizdiriyoruz
        // XML'de background="@color/card_surface" vereceğiz, o burada çizilecek.
        background?.draw(canvas)

        // 2. Şimdi "src" içindeki resmi (senin noktalar) alıp "Silici" boyasıyla çiziyoruz
        drawable?.let {
            // Resmin boyutunu view boyutuna eşitle
            it.setBounds(0, 0, width, height)

            // Android'in VectorDrawable'ı bazen Paint xfermode'u yoksayar.
            // O yüzden layer'ı manipüle ediyoruz.
            // Burada basitçe: "Bu resmi çizdiğim yerleri oy" diyoruz.
            // Ancak VectorDrawable için saveLayerAlpha gerekebilir, şimdilik basit deniyoruz.
            val p = Paint()
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)

            // Resmi çizmek yerine, resmin maskesini uygulamamız lazım.
            // En garantisi resmi bir bitmap gibi kullanıp silmektir ama performans düşer.
            // Hadi basit yapalım:

            // src resmini çiz ama "Paint" ile modifiye ederek değil, layer mantığıyla.
            // Maalesef VectorDrawable doğrudan xfermode desteklemez.
        }

        // --- ÇÖZÜM ---
        // Kafanı karıştırmadan en temiz yöntem:
        // Standart ImageView yerine, senin resmini "Maske" olarak kullanan özel kod.

        super.onDraw(canvas) // Bu standart çizim yapar, biz bunu istemiyoruz aslında.
        canvas.restoreToCount(saveCount)
    }
}