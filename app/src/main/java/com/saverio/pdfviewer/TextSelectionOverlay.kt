package com.saverio.pdfviewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.github.barteksc.pdfviewer.PDFView
import kotlin.math.abs
import kotlin.math.hypot

class TextSelectionManager(private val context: Context) {

    var ocrEngine: PdfOcrEngine? = null
    var pdfView: PDFView? = null
    var viewerToLogicalPage: (Int) -> Int = { it }
    var logicalToViewerPage: (Int) -> Int = { it }

    var active = false; private set
    var selectedWords: List<PdfOcrEngine.WordElement> = emptyList(); private set

    private data class SelectedWordRef(
        val logicalPage: Int,
        val wordIndex: Int,
        val charIndex: Int? = null,
        val word: PdfOcrEngine.WordElement
    )

    private data class PageLayout(
        val viewerPage: Int,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val width: Float,
        val height: Float
    )

    private enum class SharpCorner {
        TOP_LEFT,
        BOTTOM_RIGHT
    }

    private var selecting = false
    private var characterSelectionEnabled = false
    private var pendingSelectionPoint: Triple<Int, Float, Float>? = null // logicalPage, normX, normY

    private var startRef: SelectedWordRef? = null
    private var endRef: SelectedWordRef? = null
    private var selectionRefs: List<SelectedWordRef> = emptyList()

    private val pageWidths = mutableMapOf<Int, Float>()
    private val pageHeights = mutableMapOf<Int, Float>()
    private val pageDrawRects = mutableMapOf<Int, RectF>()

    private val handleRadiusPx = maxOf(18f, context.resources.displayMetrics.density * 12f)
    private val dropletSizePx = handleRadiusPx * 1.75f
    private val selectionInsetPx = 2f
    private val handlePath = Path()

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

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }

    fun deactivate() {
        active = false
        clearSelection()
    }

    fun isCharacterSelectionEnabled(): Boolean = characterSelectionEnabled

    fun setCharacterSelectionEnabled(enabled: Boolean) {
        if (characterSelectionEnabled == enabled) return
        characterSelectionEnabled = enabled
        if (startRef != null && endRef != null) {
            rebuildSelectionRange()
        }
    }

    fun recordPageSize(page: Int, width: Float, height: Float) {
        pageWidths[page] = width
        pageHeights[page] = height
    }

    fun recordPageDrawGeometry(page: Int, canvas: Canvas, width: Float, height: Float) {
        recordPageSize(page, width, height)
        val localRect = RectF(
            0f,
            0f,
            width,
            height
        )
        val mappedRect = RectF(localRect)
        val drawMatrix = Matrix(canvas.matrix)
        drawMatrix.mapRect(mappedRect)
        pageDrawRects[page] = mappedRect
    }

    private fun getLocalPageDrawOffset(pageWidth: Float, pageHeight: Float): PointF {
        val pdf = pdfView ?: return PointF(0f, 0f)
        val contentWidth = pdf.width.toFloat().coerceAtLeast(1f)
        val contentHeight = pdf.height.toFloat().coerceAtLeast(1f)

        return if (pdf.isSwipeVertical) {
            PointF(((contentWidth - pageWidth) / 2f).coerceAtLeast(0f), 0f)
        } else {
            PointF(0f, ((contentHeight - pageHeight) / 2f).coerceAtLeast(0f))
        }
    }

    private fun buildPageLayouts(): List<PageLayout> {
        if (pageDrawRects.isNotEmpty()) {
            return pageDrawRects.entries
                .sortedBy { it.key }
                .map { (viewerPage, rect) ->
                    PageLayout(
                        viewerPage = viewerPage,
                        left = rect.left,
                        top = rect.top,
                        right = rect.right,
                        bottom = rect.bottom,
                        width = rect.width().coerceAtLeast(1f),
                        height = rect.height().coerceAtLeast(1f)
                    )
                }
        }

        val pdf = pdfView ?: return emptyList()
        val zoom = pdf.zoom
        val xOff = pdf.currentXOffset
        val yOff = pdf.currentYOffset
        val totalPages = pdf.pageCount
        val contentWidth = (pdf.width - pdf.paddingLeft - pdf.paddingRight).toFloat().coerceAtLeast(1f)
        val contentHeight = (pdf.height - pdf.paddingTop - pdf.paddingBottom).toFloat().coerceAtLeast(1f)

        val density = context.resources.displayMetrics.density
        val spacing = try {
            pdf.spacingPx.toFloat()
        } catch (_: Exception) {
            (5 * density)
        }

        val layouts = ArrayList<PageLayout>(totalPages)
        if (pdf.isSwipeVertical) {
            var currentTop = yOff + pdf.paddingTop
            for (page in 0 until totalPages) {
                val size = pdf.getPageSize(page) ?: continue
                val pageW = size.width * zoom
                val pageH = size.height * zoom
                val left = xOff + pdf.paddingLeft + (contentWidth - pageW) / 2f
                layouts.add(
                    PageLayout(
                        viewerPage = page,
                        left = left,
                        top = currentTop,
                        right = left + pageW,
                        bottom = currentTop + pageH,
                        width = pageW,
                        height = pageH
                    )
                )
                currentTop += pageH + spacing
            }
        } else {
            var currentLeft = xOff + pdf.paddingLeft
            for (page in 0 until totalPages) {
                val size = pdf.getPageSize(page) ?: continue
                val pageW = size.width * zoom
                val pageH = size.height * zoom
                val top = yOff + pdf.paddingTop + (contentHeight - pageH) / 2f
                layouts.add(
                    PageLayout(
                        viewerPage = page,
                        left = currentLeft,
                        top = top,
                        right = currentLeft + pageW,
                        bottom = top + pageH,
                        width = pageW,
                        height = pageH
                    )
                )
                currentLeft += pageW + spacing
            }
        }
        return layouts
    }

    fun viewToPage(viewX: Float, viewY: Float, currentPage: Int): Triple<Int, Float, Float>? {
        val layouts = buildPageLayouts()
        if (layouts.isEmpty()) return null
        val hitTolerancePx = 6f * context.resources.displayMetrics.density

        val exactHits = layouts.filter {
            viewX >= (it.left - hitTolerancePx) && viewX <= (it.right + hitTolerancePx) &&
                viewY >= (it.top - hitTolerancePx) && viewY <= (it.bottom + hitTolerancePx)
        }
        val chosen = if (exactHits.isNotEmpty()) {
            // In case of geometric overlaps (mixed page sizes / offset drift), pick the closest center.
            exactHits.minByOrNull { layout ->
                val cx = (layout.left + layout.right) / 2f
                val cy = (layout.top + layout.bottom) / 2f
                val dx = viewX - cx
                val dy = viewY - cy
                (dx * dx) + (dy * dy)
            }
        } else {
            null
        } ?: return null

        val clampedX = viewX.coerceIn(chosen.left, chosen.right)
        val clampedY = viewY.coerceIn(chosen.top, chosen.bottom)
        val normX = ((clampedX - chosen.left) / chosen.width).coerceIn(0f, 1f)
        val normY = ((clampedY - chosen.top) / chosen.height).coerceIn(0f, 1f)

        return Triple(chosen.viewerPage, normX, normY)
    }

    fun viewToPageClamped(viewX: Float, viewY: Float, page: Int): Triple<Int, Float, Float>? {
        val layout = buildPageLayouts().firstOrNull { it.viewerPage == page } ?: return null
        val clampedX = viewX.coerceIn(layout.left, layout.right)
        val clampedY = viewY.coerceIn(layout.top, layout.bottom)
        val normX = ((clampedX - layout.left) / layout.width).coerceIn(0f, 1f)
        val normY = ((clampedY - layout.top) / layout.height).coerceIn(0f, 1f)
        return Triple(page, normX, normY)
    }

    fun pageNormToViewCoords(normX: Float, normY: Float, page: Int): PointF? {
        val layout = buildPageLayouts().firstOrNull { it.viewerPage == page } ?: return null
        return PointF(
            layout.left + normX * layout.width,
            layout.top + normY * layout.height
        )
    }

    private fun getOrderedWordsForPage(logicalPage: Int): List<PdfOcrEngine.WordElement>? {
        return ocrEngine?.getWordsForPage(logicalPage)
            ?.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
    }

    private fun compareRefs(a: SelectedWordRef, b: SelectedWordRef): Int {
        if (a.logicalPage != b.logicalPage) return a.logicalPage.compareTo(b.logicalPage)
        if (a.wordIndex != b.wordIndex) return a.wordIndex.compareTo(b.wordIndex)
        val aChar = a.charIndex ?: 0
        val bChar = b.charIndex ?: 0
        return aChar.compareTo(bChar)
    }

    private fun toCharIndex(word: PdfOcrEngine.WordElement, normX: Float): Int {
        val len = word.text.length.coerceAtLeast(1)
        val width = (word.rect.right - word.rect.left).coerceAtLeast(0.0001f)
        val rel = ((normX - word.rect.left) / width).coerceIn(0f, 1f)
        return (rel * len).toInt().coerceIn(0, len - 1)
    }

    private fun buildWordSegment(
        word: PdfOcrEngine.WordElement,
        charStart: Int,
        charEnd: Int
    ): PdfOcrEngine.WordElement {
        val len = word.text.length.coerceAtLeast(1)
        val start = charStart.coerceIn(0, len - 1)
        val end = charEnd.coerceIn(start, len - 1)
        val width = word.rect.right - word.rect.left
        val startFrac = start.toFloat() / len.toFloat()
        val endFrac = (end + 1).toFloat() / len.toFloat()
        val left = word.rect.left + (width * startFrac)
        val right = word.rect.left + (width * endFrac)
        val text = if (word.text.isNotEmpty()) {
            word.text.substring(start, end + 1)
        } else {
            ""
        }
        return PdfOcrEngine.WordElement(text = text, rect = RectF(left, word.rect.top, right, word.rect.bottom))
    }

    private fun rebuildSelectionRange() {
        val a = startRef
        val b = endRef
        if (a == null || b == null) {
            selectionRefs = emptyList()
            selectedWords = emptyList()
            return
        }

        val from = if (compareRefs(a, b) <= 0) a else b
        val to = if (compareRefs(a, b) <= 0) b else a

        val refs = mutableListOf<SelectedWordRef>()
        for (logicalPage in from.logicalPage..to.logicalPage) {
            val words = getOrderedWordsForPage(logicalPage)
            if (words == null) {
                ocrEngine?.ensurePageIndexedAsync(logicalPage)
                continue
            }

            val startIndex = when {
                logicalPage == from.logicalPage -> from.wordIndex.coerceIn(0, words.lastIndex)
                else -> 0
            }
            val endIndex = when {
                logicalPage == to.logicalPage -> to.wordIndex.coerceIn(0, words.lastIndex)
                else -> words.lastIndex
            }
            if (startIndex > endIndex) continue

            for (idx in startIndex..endIndex) {
                val srcWord = words[idx]
                if (!characterSelectionEnabled) {
                    refs.add(SelectedWordRef(logicalPage, idx, null, srcWord))
                    continue
                }

                val startChar = when {
                    logicalPage == from.logicalPage && idx == from.wordIndex -> from.charIndex ?: 0
                    else -> 0
                }
                val endChar = when {
                    logicalPage == to.logicalPage && idx == to.wordIndex ->
                        to.charIndex ?: (srcWord.text.length.coerceAtLeast(1) - 1)
                    else -> (srcWord.text.length.coerceAtLeast(1) - 1)
                }

                if (startChar > endChar) continue
                refs.add(
                    SelectedWordRef(
                        logicalPage = logicalPage,
                        wordIndex = idx,
                        charIndex = if (logicalPage == from.logicalPage && idx == from.wordIndex) startChar else null,
                        word = buildWordSegment(srcWord, startChar, endChar)
                    )
                )
            }
        }

        selectionRefs = refs
        selectedWords = refs.map { it.word }
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

    fun selectWordAt(normX: Float, normY: Float, page: Int): Boolean {
        val logicalPage = viewerToLogicalPage(page)
        active = true
        selecting = false

        val words = getOrderedWordsForPage(logicalPage)
        if (words == null) {
            pendingSelectionPoint = Triple(logicalPage, normX, normY)
            ocrEngine?.ensurePageIndexedAsync(logicalPage)
            startRef = null
            endRef = null
            rebuildSelectionRange()
            return false
        }

        val index = findBestWordIndexForTap(words, normX, normY)
        if (index < 0) {
            deactivate()
            return false
        }

        val charIndex = if (characterSelectionEnabled) toCharIndex(words[index], normX) else null
        val ref = SelectedWordRef(logicalPage, index, charIndex, words[index])
        startRef = ref
        endRef = ref
        pendingSelectionPoint = null
        rebuildSelectionRange()
        return true
    }

    // Legacy compatibility for old rubber-band flow.
    fun onDown(normX: Float, normY: Float, page: Int) {
        selectWordAt(normX, normY, page)
        selecting = true
    }

    fun onMove(normX: Float, normY: Float, page: Int) {
        if (!active) return
        moveEndHandle(normX, normY, page)
    }

    fun onUp() {
        selecting = false
    }

    fun moveStartHandle(normX: Float, normY: Float, page: Int = getSelectionViewerPage()) {
        val logicalPage = viewerToLogicalPage(page)
        val words = getOrderedWordsForPage(logicalPage) ?: run {
            ocrEngine?.ensurePageIndexedAsync(logicalPage)
            return
        }
        val index = findBestWordIndexForTap(words, normX, normY)
        if (index < 0) return
        val charIndex = if (characterSelectionEnabled) toCharIndex(words[index], normX) else null
        startRef = SelectedWordRef(logicalPage, index, charIndex, words[index])
        if (endRef == null) endRef = startRef
        pendingSelectionPoint = null
        rebuildSelectionRange()
    }

    fun moveEndHandle(normX: Float, normY: Float, page: Int = getSelectionViewerPage()) {
        val logicalPage = viewerToLogicalPage(page)
        val words = getOrderedWordsForPage(logicalPage) ?: run {
            ocrEngine?.ensurePageIndexedAsync(logicalPage)
            return
        }
        val index = findBestWordIndexForTap(words, normX, normY)
        if (index < 0) return
        val charIndex = if (characterSelectionEnabled) toCharIndex(words[index], normX) else null
        endRef = SelectedWordRef(logicalPage, index, charIndex, words[index])
        if (startRef == null) startRef = endRef
        pendingSelectionPoint = null
        rebuildSelectionRange()
    }

    fun refreshSelection() {
        val pending = pendingSelectionPoint
        if (pending != null) {
            val (logicalPage, normX, normY) = pending
            val words = getOrderedWordsForPage(logicalPage)
            if (words == null) {
                ocrEngine?.ensurePageIndexedAsync(logicalPage)
                return
            }
            val index = findBestWordIndexForTap(words, normX, normY)
            if (index < 0) {
                deactivate()
                return
            }
            val charIndex = if (characterSelectionEnabled) toCharIndex(words[index], normX) else null
            val ref = SelectedWordRef(logicalPage, index, charIndex, words[index])
            startRef = ref
            endRef = ref
            pendingSelectionPoint = null
        }

        if (startRef != null && endRef != null) {
            rebuildSelectionRange()
        }
    }

    fun getSelectionPage(): Int {
        return startRef?.logicalPage ?: -1
    }

    fun getSelectionViewerPage(): Int {
        val logical = startRef?.logicalPage ?: return -1
        return logicalToViewerPage(logical)
    }

    private fun getStartHandleScreenPos(): PointF? {
        val ref = selectionRefs.firstOrNull() ?: return null
        val anchorPos = pageNormToViewCoords(
            ref.word.rect.left,
            ref.word.rect.top,
            logicalToViewerPage(ref.logicalPage)
        ) ?: return null
        val centerPos = markerCenterFromAnchor(anchorPos.x, anchorPos.y, dropletSizePx, SharpCorner.BOTTOM_RIGHT)
        return pdfViewToScreen(centerPos)
    }

    private fun getEndHandleScreenPos(): PointF? {
        val ref = selectionRefs.lastOrNull() ?: return null
        val anchorPos = pageNormToViewCoords(
            ref.word.rect.right,
            ref.word.rect.bottom,
            logicalToViewerPage(ref.logicalPage)
        ) ?: return null
        val centerPos = markerCenterFromAnchor(anchorPos.x, anchorPos.y, dropletSizePx, SharpCorner.TOP_LEFT)
        return pdfViewToScreen(centerPos)
    }

    private fun pdfViewToScreen(viewCoords: PointF): PointF {
        val pdf = pdfView ?: return viewCoords
        val loc = IntArray(2)
        pdf.getLocationOnScreen(loc)
        return PointF(viewCoords.x + loc[0], viewCoords.y + loc[1])
    }

    fun findHandleHit(screenX: Float, screenY: Float, hitRadiusPx: Float = handleRadiusPx * 3.5f): Int {
        if (selectionRefs.isEmpty() || selecting) return 0
        val start = getStartHandleScreenPos() ?: return 0
        val end = getEndHandleScreenPos() ?: return 0
        if (hypot((screenX - start.x).toDouble(), (screenY - start.y).toDouble()) < hitRadiusPx) return 1
        if (hypot((screenX - end.x).toDouble(), (screenY - end.y).toDouble()) < hitRadiusPx) return 2
        return 0
    }

    fun clearSelection() {
        selecting = false
        pendingSelectionPoint = null
        startRef = null
        endRef = null
        selectionRefs = emptyList()
        selectedWords = emptyList()
    }

    fun clearPageGeometryCache() {
        pageDrawRects.clear()
        pageWidths.clear()
        pageHeights.clear()
    }

    fun hasPendingSelection(): Boolean = pendingSelectionPoint != null

    private fun markerCenterFromAnchor(
        anchorX: Float,
        anchorY: Float,
        size: Float,
        sharpCorner: SharpCorner
    ): PointF {
        val half = size / 2f
        return when (sharpCorner) {
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
            radius, radius,
            radius, radius,
            radius, radius,
            radius, radius
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

    fun drawOnPage(canvas: Canvas, pageWidth: Float, pageHeight: Float, displayedPage: Int) {
        if (!active || selectionRefs.isEmpty()) return
        val logicalDisplayed = viewerToLogicalPage(displayedPage)

        val pageRefs = selectionRefs.filter { it.logicalPage == logicalDisplayed }
        if (pageRefs.isEmpty()) return

        for (ref in pageRefs) {
            val w = ref.word
            val l = (w.rect.left * pageWidth) - selectionInsetPx
            val t = (w.rect.top * pageHeight) - selectionInsetPx
            val r = (w.rect.right * pageWidth) + selectionInsetPx
            val b = (w.rect.bottom * pageHeight) + selectionInsetPx
            if (r <= l || b <= t) continue
            canvas.drawRect(l, t, r, b, fillPaint)
        }

        if (!selecting) {
            val first = selectionRefs.first()
            val last = selectionRefs.last()

            if (first.logicalPage == logicalDisplayed) {
                val sX = first.word.rect.left * pageWidth
                val sTop = first.word.rect.top * pageHeight
                val sBot = first.word.rect.bottom * pageHeight
                canvas.drawLine(sX, sTop, sX, sBot, handleLinePaint)
                drawDropletMarker(canvas, sX, sTop, dropletSizePx, SharpCorner.BOTTOM_RIGHT)
            }

            if (last.logicalPage == logicalDisplayed) {
                val eX = last.word.rect.right * pageWidth
                val eTop = last.word.rect.top * pageHeight
                val eBot = last.word.rect.bottom * pageHeight
                canvas.drawLine(eX, eTop, eX, eBot, handleLinePaint)
                drawDropletMarker(canvas, eX, eBot, dropletSizePx, SharpCorner.TOP_LEFT)
            }
        }
    }

    fun copySelectedText(): Boolean {
        if (selectionRefs.isEmpty()) return false
        val text = buildText()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("PDF Text", text))
        val preview = if (text.length > 40) text.substring(0, 40) + "..." else text
        val msg = String.format(context.getString(R.string.text_copied_preview), preview)
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        return true
    }

    private fun buildText(): String {
        if (selectionRefs.isEmpty()) return ""
        val sb = StringBuilder()
        var lastPage = -1
        var lastTop = -1f
        for (ref in selectionRefs) {
            if (lastPage != -1 && ref.logicalPage != lastPage) {
                sb.append('\n')
                sb.append('\n')
                lastTop = -1f
            }
            val top = ref.word.rect.top
            if (lastTop >= 0 && abs(top - lastTop) > 0.01f) sb.append('\n')
            else if (sb.isNotEmpty() && sb.last() != '\n') sb.append(' ')
            sb.append(ref.word.text)
            lastPage = ref.logicalPage
            lastTop = top
        }
        return sb.toString()
    }
}
