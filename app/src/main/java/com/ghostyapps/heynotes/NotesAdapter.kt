package com.ghostyapps.heynotes

import android.graphics.Color
import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class NotesAdapter(
    private val onItemClick: (NoteItem) -> Unit,
    private val onItemLongClick: (NoteItem, View) -> Unit,
    private val onIconClick: (NoteItem, View) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<NoteItem> = emptyList()
    var isGridMode = false

    private val TYPE_LIST_ITEM = 0
    private val TYPE_GRID_ITEM = 1
    private val TYPE_DIVIDER = 2

    fun submitList(newItems: List<NoteItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        if (items[position].isDivider) return TYPE_DIVIDER
        return if (isGridMode) TYPE_GRID_ITEM else TYPE_LIST_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_GRID_ITEM -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_note_grid, parent, false)
                NoteViewHolder(view)
            }
            TYPE_DIVIDER -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_divider, parent, false)
                DividerViewHolder(view)
            }
            else -> { // TYPE_LIST_ITEM
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_note, parent, false)
                NoteViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is NoteViewHolder) {
            holder.bind(items[position])
        }
    }

    override fun getItemCount() = items.size

    inner class NoteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val tvTitle: TextView = itemView.findViewById(R.id.note_title)
        private val tvContent: TextView = itemView.findViewById(R.id.note_content)
        private val tvDate: TextView = itemView.findViewById(R.id.note_date)
        private val cardView: MaterialCardView = itemView.findViewById(R.id.note_card_view)

        fun bind(item: NoteItem) {
            val displayName = item.name.removeSuffix(".md")

            // --- 1. BAŞLIK VE GÖRÜNÜRLÜK (Ortak) ---
            // Arama sonucu da olsa, normal de olsa başlık görünmeli
            tvTitle.text = if (item.isFolder) item.name else displayName
            tvTitle.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)

            if (item.isFolder) {
                tvContent.visibility = View.GONE
                tvDate.visibility = View.GONE
            } else {
                tvContent.visibility = View.VISIBLE
                tvDate.visibility = View.VISIBLE
                tvDate.text = item.date

                // --- İÇERİK KARARI (Farklılaşan Kısım Burası) ---
                if (item.searchPreview != null) {
                    // Arama sonucuysa: Search Preview göster
                    tvContent.text = item.searchPreview
                } else {
                    // Normal notsa: Normal içeriği temizle göster
                    tvContent.text = getCleanContent(item.content)
                }
            }

            // --- 2. RENK AYARLARI (Ortak) ---
            // Arama sonuçları da renkli olmalı, o yüzden bu kodu if/else dışına aldık.
            val colorCode = item.color ?: Color.WHITE
            cardView.setCardBackgroundColor(colorCode)

            // --- 3. SEÇİLİ OLMA DURUMU (Ortak) ---
            if (item.isSelected) {
                cardView.strokeWidth = 6
                cardView.strokeColor = Color.parseColor("#4285F4")
                itemView.alpha = 0.8f
            } else {
                if (colorCode == Color.WHITE) {
                    cardView.strokeWidth = 2
                    cardView.strokeColor = Color.parseColor("#1F000000")
                } else {
                    cardView.strokeWidth = 0
                }
                itemView.alpha = 1.0f
            }

            // --- 4. TIKLAMA (Ortak) ---
            // Arama sonuçlarına da tıklanabilmeli
            itemView.setOnClickListener { onItemClick(item) }
            itemView.setOnLongClickListener {
                onItemLongClick(item, itemView)
                true
            }
        }

    }

    // Markdown sembollerini temizleyen yardımcı fonksiyon
    private fun getCleanContent(markdown: String): String {
        if (markdown.isEmpty()) return "No additional text"

        var text = markdown

        // 1. Kalın (**text**) işaretlerini kaldır -> text
        text = text.replace("**", "")

        // 2. İtalik (*text* veya _text_) işaretlerini kaldır
        text = text.replace("*", "").replace("_", "")

        // 3. Başlık işaretlerini (#) kaldır
        text = text.replace("#", "")

        // 4. Linkleri temizle [Link](url) -> Link (Basit versiyon)
        text = text.replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1")

        // 5. Kod bloklarını (`) temizle
        text = text.replace("`", "")

        // 6. Fazla boşlukları temizle
        return text.trim()
    }

    inner class DividerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
}