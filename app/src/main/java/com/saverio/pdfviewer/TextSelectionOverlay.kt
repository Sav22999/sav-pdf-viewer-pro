package com.saverio.pdfviewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.*
import android.util.Log
import android.widget.Toast
import com.github.barteksc.pdfviewer.PDFView

/**
 * Manages interactive text selection on the PDF.
 *
 * Drawing goes through [drawOnPage] called from the PDFView onDraw callback.
 * Touch coordinate conversion uses PDFView's public API properties.
 */
class TextSelectionManager(private val context: Context) {

    var ocrEngine: PdfOcrEngine? = null
    var pdfView: PDFView? = null

    // ── public state ──────────────────────────────────────────────────────────
    var active = false; private set
    var selectedWords: List<PdfOcrEngine.WordElement> = emptyList(); private set

    // ── internal state ────────────────────────────────────────────────────────
    private var selecting = false
    private var selectionPage = -1
    private val anchorNorm = PointF()
    private val currentNorm = PointF()
    private val pageWidths = mutableMapOf<Int, Float>()
    private val pageHeights = mutableMapOf<Int, Float>()

    // ── paints ────────────────────────────────────────────────────────────────
    private val fillPaint = Paint().apply {
        color = Color.argb(70, 33, 150, 243); style = Paint.Style.FILL
    }
    private val borderPaint = Paint().apply {
        color = Color.argb(180, 33, 150, 243); style = Paint.Style.STROKE; strokeWidth = 2f
    }

    // ── mode toggle ───────────────────────────────────────────────────────────
    fun toggleMode(): Boolean {
        active = !active; if (!active) clearSelection(); return active
    }

    fun deactivate() {
        active = false; clearSelection()
    }

    /**
     * Records the actual rendered size of a page as reported by the PDFView's onDraw callback.
     * This helps in accurately mapping view coordinates to page coordinates.
     */
    fun recordPageSize(page: Int, width: Float, height: Float) {
        pageWidths[page] = width
        pageHeights[page] = height
    }

    // ── touch → normalised page coords ────────────────────────────────────────
    /**
     * Convert a view-level touch (x,y relative to PDFView) to normalised (0..1) page coordinates.
     * Uses PDFView.currentXOffset, currentYOffset, zoom, getPageSize(), spacingPx, isSwipeVertical.
     */
    fun viewToPage(viewX: Float, viewY: Float, currentPage: Int): Triple<Int, Float, Float>? {
        val pdf = pdfView ?: return null
        val zoom = pdf.zoom
        val xOff = pdf.currentXOffset
        val yOff = pdf.currentYOffset
        val totalPages = pdf.pageCount

        // spacing is set to 5dp in the configurator; convert to px
        val density = context.resources.displayMetrics.density
        val spacing = try {
            pdf.spacingPx
        } catch (_: Exception) {
            (5 * density).toInt()
        }

        Log.d(
            "TextSelect",
            "viewToPage: zoom=$zoom xOff=$xOff yOff=$yOff spacing=$spacing viewSize=(${pdf.width},${pdf.height})"
        )

        val lo = maxOf(0, currentPage - 2)
        val hi = minOf(totalPages - 1, currentPage + 2)

        for (page in lo..hi) {
            val size = pdf.getPageSize(page) ?: continue
            val pageW = pageWidths[page] ?: (size.width * zoom)
            val pageH = pageHeights[page] ?: (size.height * zoom)

            val pageX: Float
            val pageY: Float
            if (pdf.isSwipeVertical) {
                pageX = xOff + (pdf.width - pageW) / 2f
                var y = yOff
                for (p in 0 until page) {
                    val s = pdf.getPageSize(p)
                    if (s != null) {
                        val h = pageHeights[p] ?: (s.height * zoom)
                        y += h + spacing
                    }
                }
                pageY = y
            } else {
                pageY = yOff + (pdf.height - pageH) / 2f
                var x = xOff
                for (p in 0 until page) {
                    val s = pdf.getPageSize(p)
                    if (s != null) {
                        val w = pageWidths[p] ?: (s.width * zoom)
                        x += w + spacing
                    }
                }
                pageX = x
            }

            Log.d(
                "TextSelect",
                "viewToPage: touch=($viewX,$viewY) page=$page pageRect=($pageX,$pageY,${pageX + pageW},${pageY + pageH})"
            )

            if (viewX in pageX..(pageX + pageW) && viewY in pageY..(pageY + pageH)) {
                val normX = ((viewX - pageX) / pageW).coerceIn(0f, 1f)
                val normY = ((viewY - pageY) / pageH).coerceIn(0f, 1f)
                Log.d("TextSelect", "HIT page=$page norm=($normX,$normY)")
                return Triple(page, normX, normY)
            }
        }
        Log.d("TextSelect", "viewToPage: NO HIT")
        return null
    }

    // ── touch handlers ────────────────────────────────────────────────────────
    fun onDown(normX: Float, normY: Float, page: Int) {
        if (selectedWords.isNotEmpty()) clearSelection()
        anchorNorm.set(normX, normY); currentNorm.set(normX, normY)
        selectionPage = page; selecting = true
        Log.d(
            "TextSelect",
            "onDown page=$page norm=($normX,$normY) words=${ocrEngine?.getWordsForPage(page)?.size ?: "null"}"
        )
        updateSelection()
    }

    fun onMove(normX: Float, normY: Float, page: Int) {
        if (!selecting || page != selectionPage) return
        currentNorm.set(normX, normY)
        updateSelection()
    }

    fun onUp() {
        selecting = false
    }

    // ── selection logic ───────────────────────────────────────────────────────
    private fun updateSelection() {
        val words = ocrEngine?.getWordsForPage(selectionPage)
        if (words == null) {
            Log.d(
                "TextSelect",
                "updateSelection: no words for page $selectionPage — triggering async index"
            )
            ocrEngine?.ensurePageIndexedAsync(selectionPage)
            return
        }
        val r = RectF(
            minOf(anchorNorm.x, currentNorm.x), minOf(anchorNorm.y, currentNorm.y),
            maxOf(anchorNorm.x, currentNorm.x), maxOf(anchorNorm.y, currentNorm.y)
        )
        selectedWords = words.filter { RectF.intersects(r, it.rect) }
            .sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
        Log.d(
            "TextSelect",
            "updateSelection: selRect=$r found=${selectedWords.size} words out of ${words.size}"
        )
    }

    fun clearSelection() {
        selectedWords = emptyList(); selecting = false; selectionPage = -1
    }

    fun getSelectionPage() = selectionPage
    fun isSelecting() = selecting

    // ── draw highlights via onDraw ────────────────────────────────────────────
    fun drawOnPage(canvas: Canvas, pageWidth: Float, pageHeight: Float, displayedPage: Int) {
        if (!active || selectedWords.isEmpty() || displayedPage != selectionPage) return
        for (w in selectedWords) {
            val l = w.rect.left * pageWidth;
            val t = w.rect.top * pageHeight
            val ri = w.rect.right * pageWidth;
            val b = w.rect.bottom * pageHeight
            canvas.drawRect(l, t, ri, b, fillPaint)
            canvas.drawRect(l, t, ri, b, borderPaint)
        }
    }

    // ── copy ──────────────────────────────────────────────────────────────────
    fun copySelectedText(): Boolean {
        if (selectedWords.isEmpty()) return false
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("PDF Text", buildText()))
        Toast.makeText(context, context.getString(R.string.select_text_copied), Toast.LENGTH_SHORT)
            .show()
        return true
    }

    fun getSelectedText(): String = buildText()

    private fun buildText(): String {
        if (selectedWords.isEmpty()) return ""
        val sorted = selectedWords.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
        val sb = StringBuilder()
        var lastTop = -1f
        for (w in sorted) {
            if (lastTop >= 0 && (w.rect.top - lastTop) > 0.01f) sb.append('\n')
            else if (sb.isNotEmpty()) sb.append(' ')
            sb.append(w.text)
            lastTop = w.rect.top
        }
        return sb.toString()
    }
}
