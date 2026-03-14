package com.saverio.pdfviewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.*
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
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
    var viewerToLogicalPage: (Int) -> Int = { it }
    var logicalToViewerPage: (Int) -> Int = { it }

    // ── public state ──────────────────────────────────────────────────────────
    var active = false; private set
    var selectedWords: List<PdfOcrEngine.WordElement> = emptyList(); private set

    // ── internal state ────────────────────────────────────────────────────────
    private var selecting = false
    private var selectionPage = -1
    private var selectionStartIndex = -1
    private var selectionEndIndex = -1
    private var pendingSelectionPoint: PointF? = null
    private val anchorNorm = PointF()
    private val currentNorm = PointF()
    private val pageWidths = mutableMapOf<Int, Float>()
    private val pageHeights = mutableMapOf<Int, Float>()
    private val handleRadiusPx = maxOf(18f, context.resources.displayMetrics.density * 12f)
    private val handleOffsetPx = handleRadiusPx * 0.6f
    private val dropletSizePx = handleRadiusPx * 1.75f
    private val handlePath = Path()

    // ── paints ────────────────────────────────────────────────────────────────
    private val fillPaint = Paint().apply {
        color = withAlpha(ContextCompat.getColor(context, R.color.light_red), 120)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint().apply {
        color = withAlpha(ContextCompat.getColor(context, R.color.dark_red), 195)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val handleFillPaint = Paint().apply {
        color = withAlpha(ContextCompat.getColor(context, R.color.dark_red), 178)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val handleLinePaint = Paint().apply {
        color = withAlpha(ContextCompat.getColor(context, R.color.dark_red), 178)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private enum class SharpCorner {
        TOP_LEFT,
        BOTTOM_RIGHT
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }

    // ── mode toggle ───────────────────────────────────────────────────────────
    fun activate() { active = true }

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

    fun viewToPageClamped(viewX: Float, viewY: Float, page: Int): Triple<Int, Float, Float>? {
        val pageRect = getViewerPageRect(page) ?: return null
        val clampedX = viewX.coerceIn(pageRect.left, pageRect.right)
        val clampedY = viewY.coerceIn(pageRect.top, pageRect.bottom)
        val normX = ((clampedX - pageRect.left) / pageRect.width()).coerceIn(0f, 1f)
        val normY = ((clampedY - pageRect.top) / pageRect.height()).coerceIn(0f, 1f)
        return Triple(page, normX, normY)
    }

    private fun getViewerPageRect(page: Int): RectF? {
        val pdf = pdfView ?: return null
        val zoom = pdf.zoom
        val xOff = pdf.currentXOffset
        val yOff = pdf.currentYOffset
        val density = context.resources.displayMetrics.density
        val spacing = try {
            pdf.spacingPx
        } catch (_: Exception) {
            (5 * density).toInt()
        }
        val size = pdf.getPageSize(page) ?: return null
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

        return RectF(pageX, pageY, pageX + pageW, pageY + pageH)
    }

    // ── page-normalised coords → PDFView view coords ──────────────────────────
    /**
     * Convert normalised (0..1) page coordinates to view-level coordinates (x,y relative to PDFView).
     * Uses PDFView.currentXOffset, currentYOffset, zoom, getPageSize(), spacingPx, isSwipeVertical.
     */
    fun pageNormToViewCoords(normX: Float, normY: Float, page: Int): PointF? {
        val pdf = pdfView ?: return null
        val zoom = pdf.zoom
        val xOff = pdf.currentXOffset
        val yOff = pdf.currentYOffset
        val density = context.resources.displayMetrics.density
        val spacing = try {
            pdf.spacingPx
        } catch (_: Exception) {
            (5 * density).toInt()
        }
        val size = pdf.getPageSize(page) ?: return null
        val pageW = pageWidths[page] ?: (size.width * zoom)
        val pageH = pageHeights[page] ?: (size.height * zoom)

        if (pdf.isSwipeVertical) {
            val pageLeftX = xOff + (pdf.width - pageW) / 2f
            var pageTopY = yOff
            for (p in 0 until page) {
                val s = pdf.getPageSize(p) ?: continue
                val h = pageHeights[p] ?: (s.height * zoom)
                pageTopY += h + spacing
            }
            return PointF(pageLeftX + normX * pageW, pageTopY + normY * pageH)
        } else {
            val pageTopY = yOff + (pdf.height - pageH) / 2f
            var pageLeftX = xOff
            for (p in 0 until page) {
                val s = pdf.getPageSize(p) ?: continue
                val w = pageWidths[p] ?: (s.width * zoom)
                pageLeftX += w + spacing
            }
            return PointF(pageLeftX + normX * pageW, pageTopY + normY * pageH)
        }
    }

    // ── handle positions (screen coords) ─────────────────────────────────────
    /** Returns the start-handle position in screen (raw) coordinates, or null. */
    fun getStartHandleScreenPos(): PointF? {
        if (selectedWords.isEmpty() || selectionPage < 0) return null
        val first = selectedWords.first()
        val anchorPos = pageNormToViewCoords(
            first.rect.left,
            first.rect.top,
            logicalToViewerPage(selectionPage)
        ) ?: return null
        val centerPos = markerCenterFromAnchor(
            anchorX = anchorPos.x,
            anchorY = anchorPos.y,
            size = dropletSizePx,
            sharpCorner = SharpCorner.BOTTOM_RIGHT
        )
        return pdfViewToScreen(centerPos)
    }

    /** Returns the end-handle position in screen (raw) coordinates, or null. */
    fun getEndHandleScreenPos(): PointF? {
        if (selectedWords.isEmpty() || selectionPage < 0) return null
        val last = selectedWords.last()
        val anchorPos = pageNormToViewCoords(
            last.rect.right,
            last.rect.bottom,
            logicalToViewerPage(selectionPage)
        ) ?: return null
        val centerPos = markerCenterFromAnchor(
            anchorX = anchorPos.x,
            anchorY = anchorPos.y,
            size = dropletSizePx,
            sharpCorner = SharpCorner.TOP_LEFT
        )
        return pdfViewToScreen(centerPos)
    }

    private fun pdfViewToScreen(viewCoords: PointF): PointF {
        val pdf = pdfView ?: return viewCoords
        val loc = IntArray(2)
        pdf.getLocationOnScreen(loc)
        return PointF(viewCoords.x + loc[0], viewCoords.y + loc[1])
    }

    /**
     * Returns 1 if screenX/Y is near the start handle, 2 if near the end handle, 0 otherwise.
     * Only meaningful when [selectedWords] is non-empty and not currently rubber-band selecting.
     */
    fun findHandleHit(screenX: Float, screenY: Float, hitRadiusPx: Float = handleRadiusPx * 3.5f): Int {
        if (selectedWords.isEmpty() || selecting) return 0
        val start = getStartHandleScreenPos() ?: return 0
        val end   = getEndHandleScreenPos()   ?: return 0
        fun dist(ax: Float, ay: Float, bx: Float, by: Float) =
            Math.hypot((ax - bx).toDouble(), (ay - by).toDouble()).toFloat()
        if (dist(screenX, screenY, start.x, start.y) < hitRadiusPx) return 1
        if (dist(screenX, screenY, end.x,   end.y)   < hitRadiusPx) return 2
        return 0
    }

    // ── touch handlers ────────────────────────────────────────────────────────
    fun onDown(normX: Float, normY: Float, page: Int) {
        if (selectedWords.isNotEmpty()) clearSelection()
        selectionStartIndex = -1
        selectionEndIndex = -1
        pendingSelectionPoint = null
        anchorNorm.set(normX, normY); currentNorm.set(normX, normY)
        selectionPage = page; selecting = true
        updateSelection()
    }

    fun onMove(normX: Float, normY: Float, page: Int) {
        if (!selecting || page != selectionPage) return
        currentNorm.set(normX, normY)
        updateSelection()
    }

    fun onUp() { selecting = false }

    fun selectWordAt(normX: Float, normY: Float, page: Int): Boolean {
        active = true
        selecting = false
        selectionPage = viewerToLogicalPage(page)
        anchorNorm.set(normX, normY)
        currentNorm.set(normX, normY)

        val words = getOrderedWordsForPage(selectionPage)
        if (words == null) {
            pendingSelectionPoint = PointF(normX, normY)
            selectionStartIndex = -1
            selectionEndIndex = -1
            selectedWords = emptyList()
            ocrEngine?.ensurePageIndexedAsync(selectionPage)
            return false
        }

        val wordIndex = findBestWordIndexForTap(words, normX, normY)
        if (wordIndex < 0) {
            active = false
            pendingSelectionPoint = null
            selectionPage = -1
            selectionStartIndex = -1
            selectionEndIndex = -1
            selectedWords = emptyList()
            return false
        }

        pendingSelectionPoint = null
        selectionStartIndex = wordIndex
        selectionEndIndex = wordIndex
        updateSelectedWordsFromIndices(words)
        return true
    }

    /** Moves the start-handle anchor to a new position and refreshes selection. */
    fun moveStartHandle(normX: Float, normY: Float) {
        val words = getOrderedWordsForPage(selectionPage) ?: return
        val wordIndex = findBestWordIndexForTap(words, normX, normY)
        if (wordIndex < 0) return
        if (selectionEndIndex < 0) selectionEndIndex = selectionStartIndex.takeIf { it >= 0 } ?: wordIndex
        selectionStartIndex = wordIndex
        pendingSelectionPoint = null
        updateSelectedWordsFromIndices(words)
    }

    /** Moves the end-handle anchor to a new position and refreshes selection. */
    fun moveEndHandle(normX: Float, normY: Float) {
        val words = getOrderedWordsForPage(selectionPage) ?: return
        val wordIndex = findBestWordIndexForTap(words, normX, normY)
        if (wordIndex < 0) return
        if (selectionStartIndex < 0) selectionStartIndex = selectionEndIndex.takeIf { it >= 0 } ?: wordIndex
        selectionEndIndex = wordIndex
        pendingSelectionPoint = null
        updateSelectedWordsFromIndices(words)
    }

    /** Re-evaluates the selection after OCR words become available for the page. */
    fun refreshSelection() {
        if (selectionPage < 0) return
        val words = getOrderedWordsForPage(selectionPage) ?: run {
            ocrEngine?.ensurePageIndexedAsync(selectionPage)
            return
        }
        if (selectionStartIndex >= 0 || selectionEndIndex >= 0) {
            updateSelectedWordsFromIndices(words)
            return
        }
        val pendingPoint = pendingSelectionPoint ?: return
        val wordIndex = findBestWordIndexForTap(words, pendingPoint.x, pendingPoint.y)
        if (wordIndex >= 0) {
            selectionStartIndex = wordIndex
            selectionEndIndex = wordIndex
            pendingSelectionPoint = null
            updateSelectedWordsFromIndices(words)
        } else {
            deactivate()
        }
    }

    // ── selection logic ───────────────────────────────────────────────────────
    private fun updateSelection() {
        val words = getOrderedWordsForPage(selectionPage)
        if (words == null) {
            ocrEngine?.ensurePageIndexedAsync(selectionPage)
            return
        }
        if (selectionStartIndex >= 0 || selectionEndIndex >= 0) {
            updateSelectedWordsFromIndices(words)
            return
        }
        val r = RectF(
            minOf(anchorNorm.x, currentNorm.x), minOf(anchorNorm.y, currentNorm.y),
            maxOf(anchorNorm.x, currentNorm.x), maxOf(anchorNorm.y, currentNorm.y)
        )
        selectedWords = words.filter { RectF.intersects(r, it.rect) }
            .sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
    }

    private fun getOrderedWordsForPage(page: Int): List<PdfOcrEngine.WordElement>? {
        return ocrEngine?.getWordsForPage(page)
            ?.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
    }

    private fun updateSelectedWordsFromIndices(words: List<PdfOcrEngine.WordElement>) {
        if (words.isEmpty()) {
            selectedWords = emptyList()
            return
        }
        val start = selectionStartIndex.coerceIn(0, words.lastIndex)
        val end = selectionEndIndex.coerceIn(0, words.lastIndex)
        val from = minOf(start, end)
        val to = maxOf(start, end)
        selectedWords = words.subList(from, to + 1)
    }

    private fun findBestWordIndexForTap(
        words: List<PdfOcrEngine.WordElement>,
        normX: Float,
        normY: Float
    ): Int {
        if (words.isEmpty()) return -1

        words.indexOfFirst { normX in it.rect.left..it.rect.right && normY in it.rect.top..it.rect.bottom }
            .takeIf { it >= 0 }
            ?.let { return it }

        val lineTolerance = 0.02f
        val sameLineCandidates = words.withIndex().filter { (_, word) ->
            normY >= (word.rect.top - lineTolerance) && normY <= (word.rect.bottom + lineTolerance)
        }
        if (sameLineCandidates.isNotEmpty()) {
            return sameLineCandidates.minByOrNull { (_, word) ->
                when {
                    normX < word.rect.left -> word.rect.left - normX
                    normX > word.rect.right -> normX - word.rect.right
                    else -> 0f
                }
            }!!.index
        }

        return findNearestWordIndex(words, normX, normY, 0.0025f)
    }

    private fun findNearestWordIndex(
        words: List<PdfOcrEngine.WordElement>,
        normX: Float,
        normY: Float,
        maxDistanceSq: Float? = null
    ): Int {
        if (words.isEmpty()) return -1

        words.indexOfFirst { normX in it.rect.left..it.rect.right && normY in it.rect.top..it.rect.bottom }
            .takeIf { it >= 0 }
            ?.let { return it }

        var bestIndex = -1
        var bestDistance = Float.MAX_VALUE
        words.forEachIndexed { index, word ->
            val dx = when {
                normX < word.rect.left -> word.rect.left - normX
                normX > word.rect.right -> normX - word.rect.right
                else -> 0f
            }
            val dy = when {
                normY < word.rect.top -> word.rect.top - normY
                normY > word.rect.bottom -> normY - word.rect.bottom
                else -> 0f
            }
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        if (maxDistanceSq != null && bestDistance > maxDistanceSq) return -1
        return bestIndex
    }

    fun clearSelection() {
        selectedWords = emptyList(); selecting = false; selectionPage = -1
        selectionStartIndex = -1
        selectionEndIndex = -1
        pendingSelectionPoint = null
    }

    fun hasPendingSelection(): Boolean = pendingSelectionPoint != null

    fun getSelectionPage() = selectionPage
    fun getSelectionViewerPage(): Int =
        if (selectionPage < 0) -1 else logicalToViewerPage(selectionPage)

    fun isSelecting() = selecting

    // ── draw highlights + handles via onDraw ──────────────────────────────────
    fun drawOnPage(canvas: Canvas, pageWidth: Float, pageHeight: Float, displayedPage: Int) {
        if (!active || selectedWords.isEmpty() || displayedPage != logicalToViewerPage(selectionPage)) return

        // Draw word highlights
        for (w in selectedWords) {
            val l = w.rect.left * pageWidth
            val t = w.rect.top * pageHeight
            val ri = w.rect.right * pageWidth
            val b = w.rect.bottom * pageHeight
            canvas.drawRect(l, t, ri, b, fillPaint)
            canvas.drawRect(l, t, ri, b, borderPaint)
        }

        // Draw handles only when not actively rubber-band selecting
        if (!selecting) {
            val first = selectedWords.first()
            val last  = selectedWords.last()

            // Start handle: droplet anchored at first word top-left (sharp bottom-right)
            val sX = first.rect.left * pageWidth
            val sTop = first.rect.top * pageHeight
            val sBot = first.rect.bottom * pageHeight
            canvas.drawLine(sX, sTop, sX, sBot, handleLinePaint)
            drawDropletMarker(
                canvas = canvas,
                anchorX = sX,
                anchorY = sTop,
                size = dropletSizePx,
                sharpCorner = SharpCorner.BOTTOM_RIGHT
            )

            // End handle: droplet anchored at last word bottom-right (sharp top-left)
            val eX = last.rect.right * pageWidth
            val eTop = last.rect.top * pageHeight
            val eBot = last.rect.bottom * pageHeight
            canvas.drawLine(eX, eTop, eX, eBot, handleLinePaint)
            drawDropletMarker(
                canvas = canvas,
                anchorX = eX,
                anchorY = eBot,
                size = dropletSizePx,
                sharpCorner = SharpCorner.TOP_LEFT
            )
        }
    }

    private fun markerCenterFromAnchor(
        anchorX: Float,
        anchorY: Float,
        size: Float,
        sharpCorner: SharpCorner
    ): PointF {
        val half = size / 2f
        return when (sharpCorner) {
            // Anchor the droplet by its sharp corner on both axes.
            SharpCorner.TOP_LEFT -> PointF(anchorX + half, anchorY + half)
            SharpCorner.BOTTOM_RIGHT -> PointF(anchorX - half, anchorY - half)
        }
    }

    private fun drawDropletMarker(
        canvas: Canvas,
        anchorX: Float,
        anchorY: Float,
        size: Float,
        sharpCorner: SharpCorner
    ) {
        val center = markerCenterFromAnchor(anchorX, anchorY, size, sharpCorner)
        val half = size / 2f
        val rect = RectF(center.x - half, center.y - half, center.x + half, center.y + half)
        val radius = size * 0.5f
        val radii = floatArrayOf(
            radius, radius, // top-left
            radius, radius, // top-right
            radius, radius, // bottom-right
            radius, radius  // bottom-left
        )

        when (sharpCorner) {
            SharpCorner.TOP_LEFT -> {
                radii[0] = 0f
                radii[1] = 0f
            }
            SharpCorner.BOTTOM_RIGHT -> {
                radii[4] = 0f
                radii[5] = 0f
            }
        }

        handlePath.reset()
        handlePath.addRoundRect(rect, radii, Path.Direction.CW)
        canvas.drawPath(handlePath, handleFillPaint)
        canvas.drawPath(handlePath, handleLinePaint)
    }

    // ── copy ──────────────────────────────────────────────────────────────────
    fun copySelectedText(): Boolean {
        if (selectedWords.isEmpty()) return false
        val text = buildText()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("PDF Text", text))
        val preview = if (text.length > 40) text.substring(0, 40) + "…" else text
        val msg = String.format(context.getString(R.string.text_copied_preview), preview)
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
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
