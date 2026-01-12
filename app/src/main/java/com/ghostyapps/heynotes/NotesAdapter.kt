package com.ghostyapps.heynotes

import android.graphics.Color
import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class NotesAdapter(
    private val onItemClick: (NoteItem) -> Unit,
    private val onItemLongClick: (NoteItem, View) -> Unit, // 1. DEĞİŞİKLİK: View eklendi
    private val onIconClick: (NoteItem, View) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<NoteItem> = emptyList()
    var isGridMode = false

    // Define unique IDs for each view type
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

// NotesAdapter.kt -> NoteViewHolder Sınıfı

    inner class NoteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // Views
        private val tvNameList: TextView? = itemView.findViewById(R.id.tvName)
        private val ivIconList: ImageView? = itemView.findViewById(R.id.ivIcon)
        private val headerLayout: View? = itemView.findViewById(R.id.headerLayout)
        private val tvContentPreview: TextView? = itemView.findViewById(R.id.tvContentPreview)

        // YENİ: Tarih View'ı (Hem Grid hem List için aynı ID'yi verdik: tvDate)
        private val tvDate: TextView? = itemView.findViewById(R.id.tvDate)

        fun bind(item: NoteItem) {
            val displayName = item.name.removeSuffix(".md")

            // --- ORTAK TARİH MANTIĞI ---
            // Klasörlerde tarih gösterme, notlarda göster
            if (item.isFolder) {
                tvDate?.visibility = View.GONE
            } else {
                tvDate?.visibility = View.VISIBLE
                tvDate?.text = item.date // Gerçek tarihi yaz
            }

            // --- GRID MODE ---
            if (isGridMode) {
                tvNameList?.text = displayName
                tvContentPreview?.text = if (item.content.isNotEmpty()) item.content else "No additional text"

                val bgColor = item.color ?: Color.parseColor("#BDBDBD")
                headerLayout?.setBackgroundColor(bgColor)

                if (item.isSelected) itemView.alpha = 0.5f else itemView.alpha = 1.0f

                itemView.setOnClickListener { onItemClick(item) }
                itemView.setOnLongClickListener {
                    onItemLongClick(item, itemView)
                    true
                }
            }
            // --- LIST MODE ---
            else {
                tvNameList?.text = if (item.isFolder) item.name else displayName

                val cardView = itemView as? androidx.cardview.widget.CardView

                if (item.isSelected) {
                    ivIconList?.setImageResource(R.drawable.ic_check_tick)
                    ivIconList?.clearColorFilter()
                    cardView?.setCardBackgroundColor(Color.parseColor("#E0E0E0"))
                } else {
                    cardView?.setCardBackgroundColor(
                        itemView.context.resources.getColor(R.color.toolbar_background, itemView.context.theme)
                    )

                    if (item.isFolder) {
                        ivIconList?.setImageResource(R.drawable.icon_folder_dot)
                    } else {
                        ivIconList?.setImageResource(R.drawable.icon_note_dot)
                    }

                    if (item.color != null) {
                        ivIconList?.setColorFilter(item.color!!, PorterDuff.Mode.SRC_IN)
                    } else {
                        ivIconList?.clearColorFilter()
                    }
                }

                itemView.setOnClickListener { onItemClick(item) }
                itemView.setOnLongClickListener {
                    onItemLongClick(item, itemView)
                    true
                }
                ivIconList?.setOnClickListener { onIconClick(item, ivIconList) }
            }
        }
    }
    inner class DividerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
}