package com.saverio.pdfviewer.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.RecyclerView
import com.saverio.pdfviewer.DocumentNameResolver
import com.saverio.pdfviewer.R
import com.saverio.pdfviewer.db.FilesModel

class RecentFilesAdapter(
    private val onOpen: (FilesModel) -> Unit
) : RecyclerView.Adapter<RecentFilesAdapter.RecentFileViewHolder>() {

    private val items = ArrayList<FilesModel>()

    init {
        setHasStableIds(true)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecentFileViewHolder {
        val itemView = LayoutInflater.from(parent.context)
            .inflate(R.layout.recent_file_recyclerview, parent, false)
        return RecentFileViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: RecentFileViewHolder, position: Int) {
        val file = items[position]
        // Reset recycled state before binding to avoid stale swipe visuals.
        holder.card.translationX = 0F
        holder.constraintLayoutRecyclerRecent.visibility = View.VISIBLE
        holder.card.visibility = View.VISIBLE
        holder.cardRemoved.visibility = View.VISIBLE
        holder.cardRemoved.setCardBackgroundColor(holder.colorRed)
        val fileName = DocumentNameResolver.resolveDisplayName(
            holder.itemView.context,
            file.path,
            file.id
        )
        holder.title.text = fileName
        holder.date.text = file.lastUpdate.ifBlank { file.date }
        holder.foreground.contentDescription = holder.foreground.context.getString(
            R.string.home_recent_item_content_description,
            fileName
        )
        holder.foreground.setOnClickListener {
            onOpen(file)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long {
        return items[position].id.hashCode().toLong()
    }

    fun submitList(files: List<FilesModel>) {
        if (items.size == files.size && items.zip(files).all { (old, new) ->
                old.id == new.id &&
                    old.path == new.path &&
                    old.lastUpdate == new.lastUpdate &&
                    old.date == new.date
            }) {
            return
        }
        items.clear()
        items.addAll(files)
        notifyDataSetChanged()
    }

    fun getItemAt(position: Int): FilesModel? {
        if (position !in 0 until items.size) return null
        return items[position]
    }

    fun removeItemAt(position: Int): FilesModel? {
        if (position !in 0 until items.size) return null
        val removed = items.removeAt(position)
        notifyItemRemoved(position)
        return removed
    }

    class RecentFileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val foreground: View = itemView.findViewById(R.id.recentItemForeground)
        val card: CardView = itemView.findViewById(R.id.cardViewRecent)
        val cardRemoved: CardView = itemView.findViewById(R.id.cardViewRecentRemoved)
        val constraintLayoutRecyclerRecent: ConstraintLayout =
            itemView.findViewById(R.id.constraintLayoutRecyclerRecent)
        val title: TextView = itemView.findViewById(R.id.textRecentTitle)
        val date: TextView = itemView.findViewById(R.id.textRecentDate)
        val colorRed = itemView.resources.getColor(R.color.red)
    }
}

