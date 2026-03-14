package com.saverio.pdfviewer.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.net.toUri
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.recyclerview.widget.RecyclerView
import com.saverio.pdfviewer.PDFViewer
import com.saverio.pdfviewer.R
import com.saverio.pdfviewer.db.BookmarksModel
import com.saverio.pdfviewer.db.DatabaseHandler
import com.shockwave.pdfium.PdfiumCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookmarksItemAdapter(
    private val context: Context, private val items: ArrayList<BookmarksModel>
) : RecyclerView.Adapter<BookmarksItemAdapter.ItemViewHolder>() {
    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val previewCache = HashMap<String, Bitmap>()
    private val fileUriCache = HashMap<String, Uri?>()
    private val databaseHandler: DatabaseHandler by lazy { DatabaseHandler(context) }


    override fun onCreateViewHolder(parent: ViewGroup, type: Int): ItemViewHolder {
        return ItemViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.bookmark_recyclerview, parent, false)
        )
    }

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        val item = items[position]

        holder.title.text = holder.page_number.replace("%d", (item.page + 1).toString())

        // Reset recycled view state before binding to avoid stale swipe visuals.
        holder.card.translationX = 0F
        holder.card.isInvisible = false
        holder.card.isGone = false
        holder.constraintLayoutRecyclerBookmark.isGone = false
        holder.cardRemoved.isGone = false
        holder.textViewBookmarkRemoved.isGone = true
        holder.imageRemoveBookmark.isGone = false
        holder.imageGoToBookmark.isGone = true
        holder.cardRemoved.setCardBackgroundColor(holder.colorRed)

        holder.card.setOnClickListener {
            goToPage(context = context, page = item.page, animation = true)
        }

        holder.previewJob?.cancel()
        holder.imagePdfPage.setImageDrawable(null)
        holder.imagePdfPage.isGone = true

        val previewKey = "${item.file}:${item.page}"
        holder.boundPreviewKey = previewKey

        val cachedBitmap = previewCache[previewKey]
        if (cachedBitmap != null) {
            holder.imagePdfPage.setImageBitmap(cachedBitmap)
            holder.imagePdfPage.isGone = false
            return
        }

        val uri = getFileUri(item.file) ?: return

        holder.previewJob = adapterScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                renderPreviewBitmap(lastPosition = item.page, uri = uri)
            }

            if (holder.boundPreviewKey != previewKey) return@launch

            if (bitmap != null) {
                previewCache[previewKey] = bitmap
                holder.imagePdfPage.setImageBitmap(bitmap)
                holder.imagePdfPage.isGone = false
            } else {
                holder.imagePdfPage.setImageDrawable(null)
                holder.imagePdfPage.isGone = true
            }
        }
    }

    fun goToPage(context: Context, page: Int, animation: Boolean = true) {
        (context as PDFViewer).goToPage(
            valueToGo = page,
            animation = animation
        )
    }

    fun getItemAt(position: Int): BookmarksModel? {
        if (position !in 0 until items.size) return null
        return items[position]
    }

    fun removeItemAt(position: Int) {
        if (position !in 0 until items.size) return
        items.removeAt(position)
        notifyItemRemoved(position)
    }

    private fun getFileUri(fileId: String): Uri? {
        if (fileUriCache.containsKey(fileId)) {
            return fileUriCache[fileId]
        }

        val uri = try {
            databaseHandler.getFiles(fileId).firstOrNull()?.path?.toUri()
        } catch (e: Exception) {
            null
        }

        fileUriCache[fileId] = uri
        return uri
    }

    private fun renderPreviewBitmap(lastPosition: Int, uri: Uri): Bitmap? {
        try {
            val pdfiumCore = PdfiumCore(context)
            context.contentResolver.openFileDescriptor(uri, "r")?.use { parcelFileDescriptor ->
                val pdfDocument = pdfiumCore.newDocument(parcelFileDescriptor)
                try {
                    pdfiumCore.openPage(pdfDocument, lastPosition)
                    val width = pdfiumCore.getPageWidthPoint(pdfDocument, lastPosition)
                    val height = pdfiumCore.getPageHeightPoint(pdfDocument, lastPosition)

                    if (width <= 0 || height <= 0) return null

                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
                    pdfiumCore.renderPageBitmap(
                        pdfDocument,
                        bitmap,
                        lastPosition,
                        0,
                        0,
                        width,
                        height,
                        true
                    )

                    return bitmap
                } finally {
                    pdfiumCore.closeDocument(pdfDocument)
                }
            }

            return null
        } catch (e: Exception) {
            println("Exception 12: ${e.message}")
            return null
        }
    }

    override fun getItemCount(): Int {
        return items.size
    }

    override fun onViewRecycled(holder: ItemViewHolder) {
        holder.previewJob?.cancel()
        holder.previewJob = null
        holder.boundPreviewKey = null
        holder.imagePdfPage.setImageDrawable(null)
        holder.imagePdfPage.isGone = true
        super.onViewRecycled(holder)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        adapterScope.cancel()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    class ItemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.textViewTitleBookmark)
        val imagePdfPage: ImageView = view.findViewById(R.id.imageViewPDFPage)
        val card: CardView = view.findViewById(R.id.cardViewBookmark)
        val cardRemoved: CardView = view.findViewById(R.id.cardViewBookmarkRemoved)
        val imageRemoveBookmark: ImageView = view.findViewById(R.id.imageViewRemoveBookmark)
        val imageGoToBookmark: ImageView = view.findViewById(R.id.imageViewGoToBookmark)
        val textViewBookmarkRemoved: TextView = view.findViewById(R.id.textViewBookmarkRemoved)
        val constraintLayoutRecyclerBookmark: ConstraintLayout =
            view.findViewById(R.id.constraintLayoutRecyclerBookmark)

        val page_number = view.resources.getString(R.string.page_number_text)
        val deleted_bookmark_text = view.resources.getString(R.string.toast_bookmark_removed)
        val colorRed = view.resources.getColor(R.color.red)
        var previewJob: Job? = null
        var boundPreviewKey: String? = null
    }
}
