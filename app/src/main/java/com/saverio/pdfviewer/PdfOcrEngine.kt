package com.saverio.pdfviewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class PdfOcrEngine(private val context: Context) {

    /**
     * A single search occurrence.
     * [pageIndex] is the 0-based page, [highlightRect] is the normalised rect (0..1)
     * covering only the matched term on that page.
     */
    data class SearchResult(
        val pageIndex: Int,
        val highlightRect: RectF = RectF()
    )

    var onResults: ((results: List<SearchResult>, finished: Boolean) -> Unit)? = null
    var onIndexingPage: ((page: Int, total: Int) -> Unit)? = null

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cacheMutex = Mutex()

    // page → recognised text (lower-cased)
    private val textCache = mutableMapOf<Int, String>()
    // page → list of (text, normalised bounding-box) for every text element on the page
    private val elementsCache = mutableMapOf<Int, List<TextElement>>()

    @Volatile private var pdfUri: Uri? = null
    @Volatile private var pageCount = 0

    private var searchJob: Job? = null

    // internal helper
    private data class TextElement(val text: String, val rect: RectF)

    // ── public API ────────────────────────────────────────────────────────────

    fun open(uri: Uri, totalPages: Int) {
        pdfUri = uri
        pageCount = totalPages
        scope.launch {
            cacheMutex.withLock {
                textCache.clear()
                elementsCache.clear()
            }
        }
        Log.d(TAG, "open: uri=$uri  pages=$totalPages")
    }

    fun close() {
        searchJob?.cancel()
        pdfUri = null
        pageCount = 0
        scope.launch {
            cacheMutex.withLock {
                textCache.clear()
                elementsCache.clear()
            }
        }
    }

    /** Returns normalised highlight rects for a page (if already indexed).
     *  Instead of highlighting the whole word/line, we approximate the sub-rect
     *  that covers only the matched substring inside each element.
     */
    fun getHighlightsForPage(pageIndex: Int, query: String): List<RectF> {
        if (query.isBlank()) return emptyList()
        val q = query.trim().lowercase()
        val elements = elementsCache[pageIndex] ?: return emptyList()
        val rects = mutableListOf<RectF>()
        for (el in elements) {
            val text = el.text
            var startIdx = text.indexOf(q, ignoreCase = true)
            while (startIdx >= 0) {
                // Proportionally calculate the horizontal sub-rect for the match
                val endIdx = startIdx + q.length
                val totalLen = text.length.coerceAtLeast(1)
                val fracStart = startIdx.toFloat() / totalLen
                val fracEnd = endIdx.toFloat() / totalLen
                val r = el.rect
                val width = r.right - r.left
                val subRect = RectF(
                    r.left + width * fracStart,
                    r.top,
                    r.left + width * fracEnd,
                    r.bottom
                )
                rects.add(subRect)
                startIdx = text.indexOf(q, startIdx + 1, ignoreCase = true)
            }
        }
        return rects
    }

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            onResults?.invoke(emptyList(), true)
            return
        }
        val q = query.trim().lowercase()
        val uri = pdfUri
        val total = pageCount
        Log.d(TAG, "search: q='$q'  uri=$uri  total=$total")

        if (uri == null || total == 0) {
            onResults?.invoke(emptyList(), true)
            return
        }

        searchJob = scope.launch {
            val accumulated = mutableListOf<SearchResult>()

            for (pageIdx in 0 until total) {
                if (!isActive) break

                withContext(Dispatchers.Main) { onIndexingPage?.invoke(pageIdx, total) }

                val cached = cacheMutex.withLock { textCache[pageIdx] }
                val pageText = cached ?: run {
                    val result = extractText(uri, pageIdx)
                    cacheMutex.withLock { textCache[pageIdx] = result }
                    result
                }

                if (pageText.contains(q, ignoreCase = true)) {
                    val rects = getHighlightsForPage(pageIdx, q)
                    if (rects.isNotEmpty()) {
                        for (rect in rects) {
                            accumulated.add(SearchResult(pageIdx, rect))
                        }
                    } else {
                        // Text matched but no bounding boxes available (native text fallback)
                        accumulated.add(SearchResult(pageIdx))
                    }
                    val snap = accumulated.toList()
                    withContext(Dispatchers.Main) { onResults?.invoke(snap, false) }
                }
            }

            if (isActive) {
                val snap = accumulated.toList()
                withContext(Dispatchers.Main) {
                    Log.d(TAG, "search finished: ${snap.size} results")
                    onResults?.invoke(snap, true)
                }
            }
        }
    }

    // ── text extraction ───────────────────────────────────────────────────────

    private suspend fun extractText(uri: Uri, pageIndex: Int): String {
        // Always try OCR first — we need bounding boxes for highlighting
        val ocrText = extractOcrText(uri, pageIndex)
        if (ocrText.isNotBlank()) {
            Log.d(TAG, "page $pageIndex: OCR text (${ocrText.length} chars)")
            return ocrText.lowercase()
        }

        // Fallback: native text via PdfiumCore (no bounding boxes available)
        val nativeText = extractNativeText(uri, pageIndex)
        if (!nativeText.isNullOrBlank()) {
            Log.d(TAG, "page $pageIndex: native text fallback (${nativeText.length} chars)")
            return nativeText.lowercase()
        }

        Log.d(TAG, "page $pageIndex: no text found")
        return ""
    }

    private fun extractNativeText(uri: Uri, pageIndex: Int): String? {
        var pfd: ParcelFileDescriptor? = null
        var pdfDoc: PdfDocument? = null
        val core = PdfiumCore(context)
        return try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            pdfDoc = core.newDocument(pfd)
            core.openPage(pdfDoc, pageIndex)
            getPageTextSafe(core, pdfDoc, pageIndex)
        } catch (e: Exception) {
            Log.w(TAG, "extractNativeText page $pageIndex: ${e.message}")
            null
        } finally {
            try { if (pdfDoc != null) core.closeDocument(pdfDoc) } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    private fun getPageTextSafe(core: PdfiumCore, doc: PdfDocument, page: Int): String? {
        return try {
            val method = core.javaClass.getMethod(
                "getPageText", PdfDocument::class.java, Int::class.javaPrimitiveType
            )
            method.invoke(core, doc, page) as? String
        } catch (e: NoSuchMethodException) {
            Log.d(TAG, "PdfiumCore.getPageText not available")
            null
        } catch (e: Exception) {
            Log.w(TAG, "getPageTextSafe: ${e.message}")
            null
        }
    }

    private suspend fun extractOcrText(uri: Uri, pageIndex: Int): String {
        val bitmap = renderWithPdfium(uri, pageIndex) ?: renderWithPdfRenderer(uri, pageIndex)
        if (bitmap == null) {
            Log.w(TAG, "page $pageIndex: could not render bitmap")
            return ""
        }
        return runOcr(bitmap, pageIndex)
    }

    private fun renderWithPdfium(uri: Uri, pageIndex: Int): Bitmap? {
        var pfd: ParcelFileDescriptor? = null
        var pdfDoc: PdfDocument? = null
        val core = PdfiumCore(context)
        return try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            pdfDoc = core.newDocument(pfd)
            core.openPage(pdfDoc, pageIndex)
            val w = core.getPageWidthPoint(pdfDoc, pageIndex)
            val h = core.getPageHeightPoint(pdfDoc, pageIndex)
            val scale = maxOf(1, 1500 / w.coerceAtLeast(1))
            val bw = w * scale
            val bh = h * scale
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            core.renderPageBitmap(pdfDoc, bmp, pageIndex, 0, 0, bw, bh, true)
            bmp
        } catch (e: Exception) {
            Log.w(TAG, "renderWithPdfium page $pageIndex: ${e.message}")
            null
        } finally {
            try { if (pdfDoc != null) core.closeDocument(pdfDoc) } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    private fun renderWithPdfRenderer(uri: Uri, pageIndex: Int): Bitmap? {
        return try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            pfd.use { fd ->
                android.graphics.pdf.PdfRenderer(fd).use { renderer ->
                    if (pageIndex >= renderer.pageCount) return null
                    renderer.openPage(pageIndex).use { page ->
                        val scale = 2
                        val bmp = Bitmap.createBitmap(
                            page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888
                        )
                        page.render(
                            bmp, null, null,
                            android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                        )
                        bmp
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "renderWithPdfRenderer page $pageIndex: ${e.message}")
            null
        }
    }

    /**
     * Run ML Kit OCR. Also stores normalised element bounding boxes in [elementsCache]
     * so we can highlight individual text blocks later.
     */
    private suspend fun runOcr(bitmap: Bitmap, pageIndex: Int): String {
        val bmpWidth = bitmap.width.toFloat()
        val bmpHeight = bitmap.height.toFloat()
        return withContext(Dispatchers.IO) {
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                suspendCoroutine<String> { cont ->
                    recognizer.process(image)
                        .addOnSuccessListener { result ->
                            // Collect bounding boxes ONLY at the Element (word) level
                            // for precise per-word highlighting.
                            // We do NOT add line-level rects to avoid highlighting entire lines.
                            val elements = mutableListOf<TextElement>()
                            for (block in result.textBlocks) {
                                for (line in block.lines) {
                                    for (element in line.elements) {
                                        collectElements(element.text, element.boundingBox, bmpWidth, bmpHeight, elements)
                                    }
                                }
                            }
                            scope.launch {
                                cacheMutex.withLock { elementsCache[pageIndex] = elements }
                            }
                            bitmap.recycle()
                            cont.resume(result.text)
                        }
                        .addOnFailureListener { err ->
                            bitmap.recycle()
                            Log.w(TAG, "MLKit OCR error: ${err.message}")
                            cont.resume("")
                        }
                }
            } catch (e: Exception) {
                Log.e(TAG, "runOcr error: ${e.message}")
                try { bitmap.recycle() } catch (_: Exception) {}
                ""
            }
        }
    }

    private fun collectElements(
        text: String,
        bounds: android.graphics.Rect?,
        bmpW: Float, bmpH: Float,
        out: MutableList<TextElement>
    ) {
        if (bounds == null) return
        val r = RectF(
            bounds.left / bmpW,
            bounds.top / bmpH,
            bounds.right / bmpW,
            bounds.bottom / bmpH
        )
        out.add(TextElement(text.lowercase(), r))
    }

    companion object {
        private const val TAG = "PdfOcrEngine"
    }
}
