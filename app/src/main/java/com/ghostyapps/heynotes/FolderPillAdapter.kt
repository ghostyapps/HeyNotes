package com.ghostyapps.heynotes

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class FolderPillAdapter(
    private val onItemClick: (NoteItem, View) -> Unit,
    private val onItemLongClick: (NoteItem, View) -> Unit
) : ListAdapter<NoteItem, FolderPillAdapter.PillViewHolder>(PillDiffCallback()) {

    inner class PillViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // XML'deki ID'ler ile eşleştirdik
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val ivIcon: ImageView = itemView.findViewById(R.id.ivIcon)
        private val container: MaterialCardView = itemView.findViewById(R.id.cardContainer)

        fun bind(item: NoteItem) {
            tvName.text = item.name

            // --- İKON AYARLARI ---
            if (item.id == "ROOT") {
                // Main için not/ev ikonu
                ivIcon.setImageResource(R.drawable.ic_notes_icon)
            } else if (item.id == "PRIVATE_ROOT") {
                // Private için kilit ikonu
                ivIcon.setImageResource(R.drawable.ic_lock_closed)
            } else {
                // Diğerleri için klasör ikonu
                ivIcon.setImageResource(R.drawable.icon_folder_dot)
            }

            // --- RENK VE SEÇİLİLİK ---
            if (item.isActive) {
                // Seçiliyse: Arka plan rengini klasörün rengi yap
                val color = item.color ?: Color.BLACK
                container.setCardBackgroundColor(color)

                // Seçiliyken yazı ve ikon BEYAZ
                tvName.setTextColor(Color.WHITE)
                ivIcon.setColorFilter(Color.WHITE)

                // Fontu kalın yap
                try {
                    tvName.typeface = ResourcesCompat.getFont(itemView.context, R.font.productsans_bold)
                } catch (e: Exception) {
                    tvName.typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
            } else {
                // Seçili Değilse: Arka plan şeffaf veya gri
                // cardContainer olduğu için rengi şeffaf yapıyoruz, stroke verebiliriz istersek
                container.setCardBackgroundColor(Color.TRANSPARENT)

                // Yazı ve ikon GRİ
                val defaultColor = Color.parseColor("#9E9E9E") // Veya R.color.text_color_alt
                tvName.setTextColor(defaultColor)
                ivIcon.setColorFilter(defaultColor)

                // Fontu normal yap
                try {
                    tvName.typeface = ResourcesCompat.getFont(itemView.context, R.font.productsans_medium)
                } catch (e: Exception) {
                    tvName.typeface = android.graphics.Typeface.DEFAULT
                }
            }

            // Tıklama olayları
            container.setOnClickListener { onItemClick(item, container) }
            container.setOnLongClickListener {
                onItemLongClick(item, container)
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PillViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_folder_pill, parent, false)
        return PillViewHolder(view)
    }

    override fun onBindViewHolder(holder: PillViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class PillDiffCallback : DiffUtil.ItemCallback<NoteItem>() {
        override fun areItemsTheSame(oldItem: NoteItem, newItem: NoteItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: NoteItem, newItem: NoteItem): Boolean {
            return oldItem == newItem
        }
    }
}