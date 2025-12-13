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
    private val onItemLongClick: (NoteItem) -> Unit,
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

        // This forces RecyclerView to inflate a NEW layout when mode changes
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
        // Views for List Mode
        private val tvNameList: TextView? = itemView.findViewById(R.id.tvName)
        private val ivIconList: ImageView? = itemView.findViewById(R.id.ivIcon)

        // Views for Grid Mode (Card)
        private val headerLayout: View? = itemView.findViewById(R.id.headerLayout)
        private val tvContentPreview: TextView? = itemView.findViewById(R.id.tvContentPreview)

        fun bind(item: NoteItem) {
            val displayName = item.name.removeSuffix(".md")

            // --- GRID MODE LOGIC ---
            if (isGridMode) {
                // If we are in Grid Mode, we expect the Grid Layout (Card)
                // 1. Set Title (Re-using tvName ID if it matches, otherwise use header logic)
                // Note: In item_note_grid.xml, the title ID is also tvName
                tvNameList?.text = displayName

                // 2. Set Preview Body
                tvContentPreview?.text = if (item.content.isNotEmpty()) item.content else "No additional text"

                // 3. Set Header Color
                val bgColor = item.color ?: Color.parseColor("#BDBDBD") // Default Gray
                headerLayout?.setBackgroundColor(bgColor)

                // 4. Selection Logic (Fade effect for Grid)
                if (item.isSelected) {
                    itemView.alpha = 0.5f
                } else {
                    itemView.alpha = 1.0f
                }

                // Grid click listener (entire card)
                itemView.setOnClickListener { onItemClick(item) }
                itemView.setOnLongClickListener { onItemLongClick(item); true }

                // (Optional) If you want to change color in Grid view, you might need a dedicated button,
                // but for now, let's assume long-press or list view handles that to keep cards clean.
            }
            // --- LIST MODE LOGIC ---
// --- LIST MODE LOGIC ---
            else {
                tvNameList?.text = if (item.isFolder) item.name else displayName

                // KART RENGİNİ AYARLAMA (CardContainer üzerinden)
                // Bu satırları ekleyerek kartın arkaplanını yönetiyoruz
                val cardView = itemView as? androidx.cardview.widget.CardView

                if (item.isSelected) {
                    ivIconList?.setImageResource(R.drawable.ic_check_tick)
                    ivIconList?.clearColorFilter()
                    // Seçiliyse kartı hafif gri yap
                    cardView?.setCardBackgroundColor(Color.parseColor("#E0E0E0"))
                } else {
                    // Seçili değilse varsayılan renk (toolbar_background / beyaz)
                    // Rengi colors.xml'den almak en doğrusu ama şimdilik White/ToolbarBg varsayalım
                    // Grid modunda olduğu gibi dinamik renk de verebiliriz ama liste modunda genelde beyaz kart istenir.
                    // Varsayılan renge dön:
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
                itemView.setOnLongClickListener { onItemLongClick(item); true }
                ivIconList?.setOnClickListener { onIconClick(item, ivIconList) }
            }        }
    }

    inner class DividerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
}