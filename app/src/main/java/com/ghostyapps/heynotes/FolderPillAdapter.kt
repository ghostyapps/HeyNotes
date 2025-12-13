package com.ghostyapps.heynotes

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
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

            // --- FIX: Define assignedColor here so it's visible everywhere below ---
            val assignedColor = item.color ?: Color.parseColor("#616161")

            // 1. SELECTION MODE (Deleting)
            if (item.isSelected) {
                cardContainer.setCardBackgroundColor(ColorStateList.valueOf(Color.parseColor("#D32F2F")))
                cardContainer.strokeWidth = 0
                tvName.setTextColor(Color.WHITE)

                ivIcon.setImageResource(R.drawable.ic_check_tick)
                ivIcon.setColorFilter(Color.WHITE)
                ivIcon.visibility = View.VISIBLE
            }
            // 2. ACTIVE STATE (Current Folder)
            else if (item.isActive) {
                cardContainer.setCardBackgroundColor(ColorStateList.valueOf(assignedColor))
                cardContainer.strokeWidth = 0
                tvName.setTextColor(Color.WHITE)

                // If active, show folder dot (white)
                ivIcon.setImageResource(R.drawable.icon_folder_dot)
                ivIcon.setColorFilter(Color.WHITE)
                ivIcon.visibility = View.VISIBLE
            }
            // 3. INACTIVE STATE (Navigable Subfolders)
            else {
                cardContainer.setCardBackgroundColor(ColorStateList.valueOf(Color.WHITE))
                cardContainer.strokeColor = assignedColor
                cardContainer.strokeWidth = (2 * itemView.resources.displayMetrics.density).toInt()
                tvName.setTextColor(Color.parseColor("#202124"))

                // Check if Locked to show Lock Icon, otherwise Folder Dot
                if (item.isLocked) {
                    ivIcon.setImageResource(R.drawable.ic_lock_closed)
                } else {
                    ivIcon.setImageResource(R.drawable.icon_folder_dot)
                }

                // Tint with the folder's color
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