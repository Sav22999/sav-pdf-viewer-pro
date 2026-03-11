package com.saverio.pdfviewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * PDF text search engine.
 *
 * For each page it renders a bitmap via PdfiumCore, runs ML Kit OCR
 * **synchronously** (using Tasks.await()), and stores every recognised word
 * with its bounding box.  Search counts every occurrence of the query
 * inside those word-level elements, producing one [SearchResult] per match.
 */
class PdfOcrEngine(private val context: Context) {

    /** One search hit — one occurrence on one page. */
    data class SearchResult(val pageIndex: Int)

    // ── callbacks ─────────────────────────────────────────────────────────────
    var onResults: ((results: List<SearchResult>, finished: Boolean) -> Unit)? = null
    var onIndexingPage: ((page: Int, total: Int) -> Unit)? = null

    // ── internals ─────────────────────────────────────────────────────────────
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** word text (lower-case) + normalised bounding rect (0‥1) */
    data class WordElement(val text: String, val rect: RectF)

    /** page index → list of words with bounding boxes */
    private val wordsCache = ConcurrentHashMap<Int, List<WordElement>>()

    @Volatile private var pdfUri: Uri? = null
    @Volatile private var pageCount = 0
    private var searchJob: Job? = null

    // ── public API ────────────────────────────────────────────────────────────

    fun open(uri: Uri, totalPages: Int) {
        pdfUri = uri
        pageCount = totalPages
        wordsCache.clear()
        Log.d(TAG, "open  uri=$uri  pages=$totalPages")
    }

    fun close() {
        searchJob?.cancel()
        pdfUri = null
        pageCount = 0
        wordsCache.clear()
    }

    /**
     * Get the full text of a page by concatenating all cached words.
     * If the page hasn't been indexed yet, renders and OCR's it first
     * (runs on the calling thread — call from a background thread).
     */
    fun getPageText(pageIndex: Int): String {
        val uri = pdfUri ?: return ""
        ensurePageIndexed(uri, pageIndex)
        val words = wordsCache[pageIndex] ?: return ""
        if (words.isEmpty()) return ""
        // Reconstruct text: sort words top-to-bottom, left-to-right,
        // grouping words on the same line (similar Y coordinate)
        val sorted = words.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
        val sb = StringBuilder()
        var lastTop = -1f
        val lineThreshold = 0.01f // words within 1% vertical distance are on the same line
        for (w in sorted) {
            if (lastTop >= 0 && (w.rect.top - lastTop) > lineThreshold) {
                sb.append('\n')
            } else if (sb.isNotEmpty() && lastTop >= 0) {
                sb.append(' ')
            }
            sb.append(w.text)
            lastTop = w.rect.top
        }
        return sb.toString()
    }

    /** Returns the cached words for a page, or null if not yet indexed. */
    fun getWordsForPage(pageIndex: Int): List<WordElement>? = wordsCache[pageIndex]

    /** Trigger background indexing for the given page so words are ready for selection. */
    fun ensurePageIndexedAsync(pageIndex: Int) {
        val uri = pdfUri ?: return
        scope.launch { ensurePageIndexed(uri, pageIndex) }
    }

    /**
     * Returns normalised highlight rects for every occurrence of [query]
     * on [pageIndex].  Called from the UI thread (onDraw) at render time,
     * so it must be fast and non-blocking.
     */
    fun getHighlightsForPage(pageIndex: Int, query: String): List<RectF> {
        if (query.isBlank()) return emptyList()
        val q = query.trim().lowercase()
        val words = wordsCache[pageIndex]
        if (words == null) {
            Log.d(TAG, "getHighlightsForPage($pageIndex): wordsCache is NULL")
            return emptyList()
        }
        Log.d(TAG, "getHighlightsForPage($pageIndex): ${words.size} words in cache, searching '$q'")
        val rects = mutableListOf<RectF>()
        for (w in words) {
            var idx = w.text.indexOf(q)
            while (idx >= 0) {
                val len = w.text.length.coerceAtLeast(1)
                val frac0 = idx.toFloat() / len
                val frac1 = (idx + q.length).toFloat() / len
                val width = w.rect.right - w.rect.left
                rects.add(RectF(
                    w.rect.left + width * frac0,
                    w.rect.top,
                    w.rect.left + width * frac1,
                    w.rect.bottom
                ))
                idx = w.text.indexOf(q, idx + 1)
            }
        }
        Log.d(TAG, "getHighlightsForPage($pageIndex): found ${rects.size} rects")
        return rects
    }

    /**
     * Start a new search.  Results are delivered incrementally via [onResults].
     * Each individual highlight rect = one [SearchResult].
     * This uses [getHighlightsForPage] as the **single source of truth**
     * — the exact same function that onDraw uses to paint highlights.
     */
    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            onResults?.invoke(emptyList(), true)
            return
        }
        val q = query.trim().lowercase()
        val uri = pdfUri ?: run { onResults?.invoke(emptyList(), true); return }
        val total = pageCount
        if (total == 0) { onResults?.invoke(emptyList(), true); return }

        Log.d(TAG, "search  q='$q'  pages=$total")

        searchJob = scope.launch {
            val all = mutableListOf<SearchResult>()

            for (page in 0 until total) {
                if (!isActive) break
                withContext(Dispatchers.Main) { onIndexingPage?.invoke(page, total) }

                // Index the page (renders bitmap + OCR, stores words in wordsCache)
                ensurePageIndexed(uri, page)

                // Use getHighlightsForPage — THE SAME function onDraw uses.
                // Number of rects = number of occurrences on this page.
                val rects = getHighlightsForPage(page, q)
                val pageHits = rects.size

                if (pageHits > 0) {
                    Log.d(TAG, "  page $page → $pageHits hits")
                    repeat(pageHits) { all.add(SearchResult(page)) }
                    val snap = all.toList()
                    withContext(Dispatchers.Main) { onResults?.invoke(snap, false) }
                }
            }

            if (isActive) {
                val snap = all.toList()
                withContext(Dispatchers.Main) {
                    Log.d(TAG, "search done: ${snap.size} total hits")
                    onResults?.invoke(snap, true)
                }
            }
        }
    }

    // ── page indexing ─────────────────────────────────────────────────────────

    /**
     * Render the page to a bitmap and run ML Kit OCR **synchronously**
     * (Tasks.await blocks on the IO thread — perfectly safe here).
     * Stores every recognised word + bounding box in [wordsCache].
     */
    private fun ensurePageIndexed(uri: Uri, page: Int) {
        if (wordsCache.containsKey(page)) return

        val bitmap = renderPage(uri, page)
        if (bitmap == null) {
            Log.w(TAG, "page $page: render failed")
            wordsCache[page] = emptyList()
            return
        }

        val bmpW = bitmap.width.toFloat()
        val bmpH = bitmap.height.toFloat()

        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            // *** blocking await with timeout — no async callback race ***
            val visionText = Tasks.await(recognizer.process(image), 30, TimeUnit.SECONDS)
            val words = mutableListOf<WordElement>()
            for (block in visionText.textBlocks) {
                for (line in block.lines) {
                    for (elem in line.elements) {
                        val b = elem.boundingBox ?: continue
                        words.add(WordElement(
                            text = elem.text.lowercase(),
                            rect = RectF(
                                b.left / bmpW, b.top / bmpH,
                                b.right / bmpW, b.bottom / bmpH
                            )
                        ))
                    }
                }
            }
            wordsCache[page] = words
            Log.d(TAG, "page $page: indexed ${words.size} words → [${words.take(20).joinToString { it.text }}]")
        } catch (e: Exception) {
            Log.e(TAG, "page $page OCR failed: ${e.message}")
            wordsCache[page] = emptyList()
        } finally {
            bitmap.recycle()
        }
    }

    // ── bitmap rendering ──────────────────────────────────────────────────────

    private fun renderPage(uri: Uri, page: Int): Bitmap? {
        return renderWithPdfium(uri, page) ?: renderWithPdfRenderer(uri, page)
    }

    private fun renderWithPdfium(uri: Uri, pageIndex: Int): Bitmap? {
        var pfd: ParcelFileDescriptor? = null
        var doc: PdfDocument? = null
        val core = PdfiumCore(context)
        return try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            doc = core.newDocument(pfd)
            core.openPage(doc, pageIndex)
            val w = core.getPageWidthPoint(doc, pageIndex)
            val h = core.getPageHeightPoint(doc, pageIndex)
            val scale = (1500f / w.coerceAtLeast(1)).coerceIn(1f, 3f).toInt()
            val bw = w * scale;  val bh = h * scale
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            core.renderPageBitmap(doc, bmp, pageIndex, 0, 0, bw, bh, true)
            bmp
        } catch (e: Exception) {
            Log.w(TAG, "renderPdfium p$pageIndex: ${e.message}"); null
        } finally {
            try { doc?.let { core.closeDocument(it) } } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    private fun renderWithPdfRenderer(uri: Uri, pageIndex: Int): Bitmap? {
        return try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            pfd.use { fd ->
                android.graphics.pdf.PdfRenderer(fd).use { renderer ->
                    if (pageIndex >= renderer.pageCount) return null
                    renderer.openPage(pageIndex).use { pg ->
                        val s = 2
                        val bmp = Bitmap.createBitmap(pg.width * s, pg.height * s, Bitmap.Config.ARGB_8888)
                        pg.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "renderPdfRenderer p$pageIndex: ${e.message}"); null
        }
    }

    companion object {
        private const val TAG = "PdfOcrEngine"
    }
}
