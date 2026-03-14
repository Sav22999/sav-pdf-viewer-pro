package com.saverio.pdfviewer.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.saverio.pdfviewer.R
import com.saverio.pdfviewer.db.FilesModel

class RecentFilesAdapter(
    private val onOpen: (FilesModel) -> Unit
) : RecyclerView.Adapter<RecentFilesAdapter.RecentFileViewHolder>() {

    private val items = ArrayList<FilesModel>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecentFileViewHolder {
        val itemView = LayoutInflater.from(parent.context)
            .inflate(R.layout.recent_file_recyclerview, parent, false)
        return RecentFileViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: RecentFileViewHolder, position: Int) {
        val file = items[position]
        val fileName = file.path.substringAfterLast('/').ifBlank { file.path }
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

    fun submitList(files: List<FilesModel>) {
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
        val background: View = itemView.findViewById(R.id.recentItemBackground)
        val title: TextView = itemView.findViewById(R.id.textRecentTitle)
        val date: TextView = itemView.findViewById(R.id.textRecentDate)
    }
}

