package com.saverio.pdfviewer.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
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
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import kotlin.math.min


class BookmarksItemAdapter(
    private val context: Context,
    private val items: ArrayList<BookmarksModel>
) :
    RecyclerView.Adapter<BookmarksItemAdapter.ItemViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, type: Int): ItemViewHolder {
        return ItemViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.bookmark_recyclerview, parent, false)
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        val item = items[position]

        holder.title.text = holder.page_number.replace("%d", (item.page + 1).toString())

        val databaseHandler = DatabaseHandler(context)

        var onlyClicked = false

        holder.card.setOnTouchListener(
            View.OnTouchListener { view, event ->
                val displayMetrics = view.resources.displayMetrics
                val cardWidth = view.width
                val cardStart = (displayMetrics.widthPixels.toFloat() / 2) - (cardWidth / 2)
                val MAX_SWIPE_LEFT_DISTANCE = 100
                val POSITION_TO_ARRIVE = MAX_SWIPE_LEFT_DISTANCE.toFloat() - (cardWidth / 2)
                val POSITION_ALL_TO_LEFT = -(cardWidth + cardStart)
                when (event.action) {
                    MotionEvent.ACTION_MOVE -> {
                        onlyClicked = false

                        // get the new co-ordinate of X-axis
                        val newX = event.rawX

                        // carry out swipe only if newX > MAX_SWIPE_LEFT_DISTANCE, i.e.
                        // the card is swiped to the left side, not to the right
                        if (newX > MAX_SWIPE_LEFT_DISTANCE) {
                            view.animate()
                                .x(
                                    min(cardStart, newX - (cardWidth / 2))
                                )
                                .setDuration(0)
                                .start()
                        } else {
                            view.animate().x(POSITION_TO_ARRIVE)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        //when the action is up
                        //"onClick"
                        if (onlyClicked) (context as PDFViewer).goToPage(
                            valueToGo = item.page,
                            animation = true
                        )


                        //"onActionUp"
                        val POSITION_TO_ARRIVE_WITH_ERROR =
                            POSITION_TO_ARRIVE - (POSITION_TO_ARRIVE / 25)
                        if (view.x <= POSITION_TO_ARRIVE_WITH_ERROR) {
                            //Activated
                            //Go all to left
                            view.animate().x(POSITION_ALL_TO_LEFT).setDuration(500).start()
                            Handler().postDelayed(
                                {
                                    /*view.animate().x(-(POSITION_ALL_TO_LEFT * 2)).setDuration(0)
                                        .start()*/
                                    view.animate().x(cardStart).setDuration(100).start()
                                }, 500
                            )
                            holder.card.isInvisible = true
                            holder.imageRemoveBookmark.isGone = true
                            holder.cardRemoved.isGone = false

                            //println(holder.cardRemoved.width.toFloat())
                            val initialX = holder.textViewBookmarkRemoved.x
                            holder.textViewBookmarkRemoved.animate()
                                .x(holder.cardRemoved.width.toFloat() * 2)
                                .setDuration(0).start()
                            holder.textViewBookmarkRemoved.isGone = false
                            holder.textViewBookmarkRemoved.animate().x(initialX)
                                .setDuration(500).start()

                            Handler().postDelayed(
                                {
                                    holder.textViewBookmarkRemoved.isGone = true
                                    holder.card.isGone = true
                                    holder.constraintLayoutRecyclerBookmark.isGone = true
                                }, 5000
                            )

                            databaseHandler.deleteBookmark(item.id!!) //delete the bookmark
                            if (databaseHandler.getBookmarks(item.file).size == 0) {
                                //no more items
                                (context as PDFViewer).hideBottomSheet()
                            }

                            Toast.makeText(
                                context,
                                holder.deleted_bookmark_text,
                                Toast.LENGTH_SHORT
                            )
                                .show()
                        } else {
                            //Not activated (cancelled)
                            view.animate().x(cardStart).setDuration(500).start()
                        }
                    }
                    MotionEvent.ACTION_DOWN -> {
                        onlyClicked = true
                    }
                }

                // required to by-pass lint warning
                view.performClick()
                return@OnTouchListener true
            }
        )

        val uri = databaseHandler.getFiles(item.file)[0].path.toUri()
        val lastPosition = item.page

        Handler().post {
            loadPreview(
                lastPosition = lastPosition,
                uri = uri,
                holder = holder
            )
        }
    }

    fun loadPreview(
        lastPosition: Int,
        uri: Uri,
        holder: ItemViewHolder
    ) {
        try {
            val pdfiumCore = PdfiumCore(context)
            try {
                val parcelFileDescriptor: ParcelFileDescriptor? =
                    context.getContentResolver().openFileDescriptor(uri, "r")
                val pdfDocument: PdfDocument = pdfiumCore.newDocument(parcelFileDescriptor)
                pdfiumCore.openPage(pdfDocument, lastPosition)
                val width = pdfiumCore.getPageWidthPoint(pdfDocument, lastPosition)
                val height = pdfiumCore.getPageHeightPoint(pdfDocument, lastPosition)

                // ARGB_8888 - best quality, high memory usage, higher possibility of OutOfMemoryError
                // RGB_565 - little worse quality, twice less memory usage
                val bitmap = Bitmap.createBitmap(
                    width, height,
                    Bitmap.Config.RGB_565
                )
                pdfiumCore.renderPageBitmap(
                    pdfDocument, bitmap, lastPosition, 0, 0,
                    width, height, true
                )
                //if you need to render annotations and form fields, you can use
                //the same method above adding 'true' as last param
                holder.imagePdfPage.setImageBitmap(bitmap)
                holder.imagePdfPage.isGone = false
                pdfiumCore.closeDocument(pdfDocument) //very important
                parcelFileDescriptor?.close()
            } catch (e: Exception) {
                println("Exception 11: ${e.message}")
                holder.imagePdfPage.isGone = true
            }
        } catch (e: Exception) {
            println("Exception 12")
        }
    }

    override fun getItemCount(): Int {
        return items.size
    }

    class ItemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.textViewTitleBookmark)
        val imagePdfPage: ImageView = view.findViewById(R.id.imageViewPDFPage)
        val card: CardView = view.findViewById(R.id.cardViewBookmark)
        val cardRemoved: CardView = view.findViewById(R.id.cardViewBookmarkRemoved)
        val imageRemoveBookmark: ImageView = view.findViewById(R.id.imageViewRemoveBookmark)
        val textViewBookmarkRemoved: TextView = view.findViewById(R.id.textViewBookmarkRemoved)
        val constraintLayoutRecyclerBookmark: ConstraintLayout =
            view.findViewById(R.id.constraintLayoutRecyclerBookmark)

        val page_number = view.resources.getString(R.string.page_number_text)
        val deleted_bookmark_text = view.resources.getString(R.string.toast_bookmark_removed)
    }
}
