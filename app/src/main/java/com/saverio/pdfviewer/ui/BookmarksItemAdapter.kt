package com.saverio.pdfviewer.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.recyclerview.widget.RecyclerView
import com.saverio.pdfviewer.R
import com.saverio.pdfviewer.db.BookmarksModel
import com.saverio.pdfviewer.db.DatabaseHandler
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.FileDescriptor
import kotlin.math.min


class BookmarksItemAdapter(
    private val context: Context,
    private val items: ArrayList<BookmarksModel>,
    private val password: String = ""
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
                            holder.card.isGone = true
                            holder.imageRemoveBookmark.isGone = true
                            holder.textBookmarkRemoved.isGone = false
                            holder.cardRemoved.isGone = false
                            holder.constraintLayoutRecyclerBookmark.isGone = true
                            databaseHandler.deleteBookmark(item.id!!) //delete the bookmark
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
                }

                // required to by-pass lint warning
                view.performClick()
                return@OnTouchListener true
            }
        )

        runBlocking {
            async {
                try {
                    val uri = databaseHandler.getFiles(item.file)[0].path.toUri()
                    val lastPosition = item.page

                    val pdfiumCore = PdfiumCore(context)
                    try {
                        val parcelFileDescriptor: ParcelFileDescriptor? =
                            context.getContentResolver().openFileDescriptor(uri, "r")
                        val fileDescriptor: FileDescriptor = parcelFileDescriptor!!.fileDescriptor
                        val bitmapTemp = BitmapFactory.decodeFileDescriptor(fileDescriptor)

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
                        parcelFileDescriptor.close()
                    } catch (e: Exception) {
                        println("Exception 11: ${e.message}")
                        holder.imagePdfPage.isGone = true
                    }
                } catch (e: Exception) {
                    println("Exception 12")
                }
            }
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
        val textBookmarkRemoved: TextView = view.findViewById(R.id.textViewBookmarkRemoved)
        val imageRemoveBookmark: ImageView = view.findViewById(R.id.imageViewRemoveBookmark)
        val constraintLayoutRecyclerBookmark: ConstraintLayout =
            view.findViewById(R.id.constraintLayoutRecyclerBookmark)

        val page_number = view.resources.getString(R.string.page_number_text)
        val deleted_bookmark_text = view.resources.getString(R.string.toast_bookmark_removed)
    }
}
