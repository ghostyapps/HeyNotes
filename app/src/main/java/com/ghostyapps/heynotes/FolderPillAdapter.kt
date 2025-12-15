package com.ghostyapps.heynotes

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat // <--- BU IMPORT ÖNEMLİ
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class FolderPillAdapter(
    private val onItemClick: (NoteItem, View) -> Unit,
    private val onItemLongClick: (NoteItem) -> Unit
) : RecyclerView.Adapter<FolderPillAdapter.FolderViewHolder>() {

    private var items: List<NoteItem> = emptyList()

    fun submitList(newItems: List<NoteItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_folder_pill, parent, false)
        return FolderViewHolder(view)
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class FolderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardContainer: MaterialCardView = itemView.findViewById(R.id.cardContainer)
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val ivIcon: ImageView = itemView.findViewById(R.id.ivIcon)

        fun bind(item: NoteItem) {
            tvName.text = item.name

            // Renkleri hazırla
            val assignedColor = item.color ?: Color.parseColor("#616161")

            // Tema Renkleri (Main butonu için)
            val themeTextColor = ContextCompat.getColor(itemView.context, R.color.text_color)
            val themeBgColor = ContextCompat.getColor(itemView.context, R.color.background_color) // veya toolbar_background

            // --- 1. SELECTION MODE (Silme) ---
            if (item.isSelected) {
                cardContainer.setCardBackgroundColor(ColorStateList.valueOf(Color.parseColor("#D32F2F")))
                cardContainer.strokeWidth = 0
                tvName.setTextColor(Color.WHITE)

                ivIcon.setImageResource(R.drawable.ic_check_tick)
                ivIcon.setColorFilter(Color.WHITE)
                ivIcon.visibility = View.VISIBLE
            }
            // --- 2. MAIN FOLDER (ÖZEL STİL - FAB GİBİ) ---
            else if (item.id == "ROOT") {
                if (item.isActive) {
                    // AKTİF: Zemin Siyah(Text), Yazı Beyaz(Bg)
                    cardContainer.setCardBackgroundColor(ColorStateList.valueOf(themeTextColor))
                    cardContainer.strokeWidth = 0
                    tvName.setTextColor(themeBgColor)
                    ivIcon.setColorFilter(themeBgColor)
                } else {
                    // PASİF: Zemin Beyaz, Kenarlık Siyah
                    cardContainer.setCardBackgroundColor(ColorStateList.valueOf(themeBgColor))
                    cardContainer.strokeColor = themeTextColor
                    cardContainer.strokeWidth = (1 * itemView.resources.displayMetrics.density).toInt() // İnce kenarlık
                    tvName.setTextColor(themeTextColor)
                    ivIcon.setColorFilter(themeTextColor)
                }
                // İkonu ayarla (Main için klasör ikonu yerine belki home ikonu veya yine klasör)
                ivIcon.setImageResource(R.drawable.icon_folder_dot)
                ivIcon.visibility = View.VISIBLE
            }
            // --- 3. DİĞER KLASÖRLER (STANDART STİL) ---
            else if (item.isActive) {
                cardContainer.setCardBackgroundColor(ColorStateList.valueOf(assignedColor))
                cardContainer.strokeWidth = 0
                tvName.setTextColor(Color.WHITE)

                ivIcon.setImageResource(R.drawable.icon_folder_dot)
                ivIcon.setColorFilter(Color.WHITE)
                ivIcon.visibility = View.VISIBLE
            }
            else {
                // Pasif Diğer Klasörler
                cardContainer.setCardBackgroundColor(ColorStateList.valueOf(Color.WHITE)) // Her zaman beyaz zemin
                cardContainer.strokeColor = assignedColor
                cardContainer.strokeWidth = (2 * itemView.resources.displayMetrics.density).toInt()
                tvName.setTextColor(Color.parseColor("#202124")) // Standart koyu yazı

                // Kilitli mi?
                if (item.isLocked) {
                    ivIcon.setImageResource(R.drawable.ic_lock_closed)
                } else {
                    ivIcon.setImageResource(R.drawable.icon_folder_dot)
                }

                ivIcon.setColorFilter(assignedColor)
                ivIcon.visibility = View.VISIBLE
            }

            itemView.setOnClickListener { onItemClick(item, cardContainer) }

            itemView.setOnLongClickListener {
                onItemLongClick(item)
                true
            }
        }
    }
}