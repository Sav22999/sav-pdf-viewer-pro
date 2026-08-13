package com.saverio.pdfviewer

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfiumCore
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * PDF text search engine (100% FOSS — no proprietary libraries).
 *
 * For each page it extracts the embedded text layer via Pdfium's native
 * text APIs (io.legere:pdfiumandroid) and stores every word with its
 * normalised bounding box.  Search counts every occurrence of the query
 * inside those word-level elements, producing one [SearchResult] per match.
 */
class PdfOcrEngine(private val context: Context) {

    data class SearchOptions(
        val caseSensitive: Boolean = false,
        val wholeWord: Boolean = false
    )

    /** One search hit — one occurrence on one page. */
    data class SearchResult(val pageIndex: Int)

    // ── callbacks ─────────────────────────────────────────────────────────────
    var onResults: ((results: List<SearchResult>, finished: Boolean) -> Unit)? = null
    var onIndexingPage: ((page: Int, total: Int) -> Unit)? = null
    /** Fired on the main thread when a single page finishes text indexing. */
    var onPageIndexed: ((page: Int) -> Unit)? = null

    // ── internals ─────────────────────────────────────────────────────────────
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pdfiumCore by lazy { PdfiumCore(context) }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** word text + normalised bounding rect (0‥1) */
    data class WordElement(val text: String, val rect: RectF)

    /** page index → list of words with bounding boxes */
    private val wordsCache = ConcurrentHashMap<Int, List<WordElement>>()

    @Volatile private var pdfUri: Uri? = null
    @Volatile private var pageCount = 0
    @Volatile private var pdfPassword: String? = null
    private var searchJob: Job? = null

    private val documentLock = Any()
    private var document: PdfDocument? = null

    // ── public API ────────────────────────────────────────────────────────────

    fun open(uri: Uri, totalPages: Int, password: String? = null) {
        closeDocument()
        pdfUri = uri
        pageCount = totalPages
        pdfPassword = password
        wordsCache.clear()
        Log.d(TAG, "open  uri=$uri  pages=$totalPages")
    }

    fun close() {
        searchJob?.cancel()
        pdfUri = null
        pageCount = 0
        pdfPassword = null
        wordsCache.clear()
        closeDocument()
    }

    /** Outcome of probing a document with the modern (io.legere) Pdfium engine. */
    enum class OpenProbeResult {
        /** The document opened successfully (password, if any, is correct). */
        OPENED,
        /** A password is required or the supplied password is wrong. */
        WRONG_PASSWORD,
        /** The document could not be opened for another reason. */
        ERROR
    }

    /**
     * Tries to open [uri] with the modern Pdfium engine (io.legere), which
     * supports recent encryption schemes (AES-256, R5/R6). This is used to tell
     * apart a genuinely wrong password from a correct password on a document
     * whose encryption the rendering engine cannot handle.
     *
     * Runs off the main thread and delivers [callback] back on the main thread.
     */
    fun probeEncryption(uri: Uri, password: String?, callback: (OpenProbeResult) -> Unit) {
        scope.launch {
            val result = try {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (pfd == null) {
                    OpenProbeResult.ERROR
                } else {
                    val doc = if (password.isNullOrEmpty()) {
                        pdfiumCore.newDocument(pfd)
                    } else {
                        pdfiumCore.newDocument(pfd, password)
                    }
                    doc.close()
                    OpenProbeResult.OPENED
                }
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                if (msg.contains("Password", ignoreCase = true)) {
                    OpenProbeResult.WRONG_PASSWORD
                } else {
                    Log.w(TAG, "probeEncryption failed: $msg")
                    OpenProbeResult.ERROR
                }
            }
            withContext(Dispatchers.Main) { callback(result) }
        }
    }

    /**
     * Get the full text of a page by concatenating all cached words.
     * If the page hasn't been indexed yet, extracts its text layer first
     * (runs on the calling thread — call from a background thread).
     */
    fun getPageText(pageIndex: Int): String {
        if (pdfUri == null) return ""
        ensurePageIndexed(pageIndex)
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
        if (pdfUri == null) return
        scope.launch { ensurePageIndexed(pageIndex) }
    }

    /**
     * Returns the highlights for every occurrence of [query] on [pageIndex].
     * Each occurrence is represented as a list of normalised [RectF]s: usually
     * a single rect, but a multi-word match that wraps across lines yields one
     * rect per line so only the matched words are highlighted (not whole
     * lines). The outer list therefore has one element per match, preserving
     * the "one match == one result" contract used by the search UI.
     *
     * Called from the UI thread (onDraw) at render time, so it must be fast and
     * non-blocking.
     */
    fun getHighlightsForPage(
        pageIndex: Int,
        query: String,
        options: SearchOptions = SearchOptions()
    ): List<List<RectF>> {
        if (query.isBlank()) return emptyList()
        val q = query.trim()
        val words = wordsCache[pageIndex]
        if (words == null) {
            Log.d(TAG, "getHighlightsForPage($pageIndex): wordsCache is NULL")
            return emptyList()
        }
        Log.d(
            TAG,
            "getHighlightsForPage($pageIndex): ${words.size} words in cache, searching '$q' cs=${options.caseSensitive} ww=${options.wholeWord}"
        )
        // Multi-word / sentence queries can span several WordElements, so they
        // cannot be matched inside a single word. Handle them with a dedicated
        // path that matches against the whole page text and highlights the
        // matched words grouped by line.
        if (q.any { it.isWhitespace() }) {
            val matches = getSentenceHighlights(words, q, options)
            Log.d(TAG, "getHighlightsForPage($pageIndex): found ${matches.size} sentence matches")
            return matches
        }

        val matches = mutableListOf<List<RectF>>()
        for (w in words) {
            val matchRanges = findMatchRanges(w.text, q, options)
            for (range in matchRanges) {
                val len = w.text.length.coerceAtLeast(1)
                val frac0 = range.first.toFloat() / len
                val frac1 = range.lastExclusive.toFloat() / len
                val width = w.rect.right - w.rect.left
                matches.add(listOf(RectF(
                    w.rect.left + width * frac0,
                    w.rect.top,
                    w.rect.left + width * frac1,
                    w.rect.bottom
                )))
            }
        }
        Log.d(TAG, "getHighlightsForPage($pageIndex): found ${matches.size} matches")
        return matches
    }

    /**
     * Matches a multi-word [query] across [words]. The word texts are joined
     * into a single string (separated by single spaces) so a phrase that spans
     * several words can be found. Each match produces a list of [RectF]s: the
     * matched words are grouped by line and one bounding rect is emitted per
     * line. This way a phrase that wraps across lines highlights only the
     * matched words on each line instead of the whole lines, while still
     * counting as a single match (one entry in the returned list).
     */
    private fun getSentenceHighlights(
        words: List<WordElement>,
        query: String,
        options: SearchOptions
    ): List<List<RectF>> {
        if (words.isEmpty()) return emptyList()

        val joined = StringBuilder()
        val charToWord = ArrayList<Int>()
        for ((wi, w) in words.withIndex()) {
            if (joined.isNotEmpty()) {
                joined.append(' ')
                charToWord.add(-1)
            }
            for (c in w.text) {
                joined.append(c)
                charToWord.add(wi)
            }
        }

        // Collapse any run of whitespace in the query to a single space so it
        // lines up with the single-space separators used in [joined].
        val needle = query.trim().replace(Regex("\\s+"), " ")
        if (needle.isBlank()) return emptyList()

        // Words within this vertical distance are considered to be on the same
        // line (matches the threshold used by [getPageText]).
        val lineThreshold = 0.01f

        val matches = findMatchRanges(joined.toString(), needle, options)
        val result = mutableListOf<List<RectF>>()
        for (m in matches) {
            var minWi = Int.MAX_VALUE
            var maxWi = -1
            for (ci in m.first until m.lastExclusive) {
                val wi = charToWord.getOrElse(ci) { -1 }
                if (wi >= 0) {
                    if (wi < minWi) minWi = wi
                    if (wi > maxWi) maxWi = wi
                }
            }
            if (maxWi < 0) continue

            // Group the matched words (in reading order) into lines and emit one
            // union rect per line, so only the matched words are highlighted.
            val lineRects = mutableListOf<RectF>()
            var left = Float.MAX_VALUE
            var top = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            var bottom = -Float.MAX_VALUE
            var lineTop = Float.NaN
            var hasLine = false

            fun flushLine() {
                if (hasLine && right > left && bottom > top) {
                    lineRects.add(RectF(left, top, right, bottom))
                }
                left = Float.MAX_VALUE
                top = Float.MAX_VALUE
                right = -Float.MAX_VALUE
                bottom = -Float.MAX_VALUE
                hasLine = false
            }

            for (wi in minWi..maxWi) {
                val r = words[wi].rect
                if (!hasLine) {
                    lineTop = r.top
                } else if (kotlin.math.abs(r.top - lineTop) > lineThreshold) {
                    // New line: close the current one and start a fresh group.
                    flushLine()
                    lineTop = r.top
                }
                hasLine = true
                if (r.left < left) left = r.left
                if (r.top < top) top = r.top
                if (r.right > right) right = r.right
                if (r.bottom > bottom) bottom = r.bottom
            }
            flushLine()

            if (lineRects.isNotEmpty()) {
                result.add(lineRects)
            }
        }
        return result
    }

    private data class MatchRange(val first: Int, val lastExclusive: Int)

    private fun findMatchRanges(text: String, query: String, options: SearchOptions): List<MatchRange> {
        if (query.isBlank() || text.isBlank()) return emptyList()

        if (options.wholeWord) {
            val pattern = "\\b${Regex.escape(query)}\\b"
            val regex = if (options.caseSensitive) {
                Regex(pattern)
            } else {
                Regex(pattern, RegexOption.IGNORE_CASE)
            }
            return regex.findAll(text).map { MatchRange(it.range.first, it.range.last + 1) }.toList()
        }

        val haystack = if (options.caseSensitive) text else text.lowercase()
        val needle = if (options.caseSensitive) query else query.lowercase()
        val out = mutableListOf<MatchRange>()
        var idx = haystack.indexOf(needle)
        while (idx >= 0) {
            out.add(MatchRange(idx, idx + needle.length))
            idx = haystack.indexOf(needle, idx + 1)
        }
        return out
    }

    /**
     * Start a new search.  Results are delivered incrementally via [onResults].
     * Each individual highlight rect = one [SearchResult].
     * This uses [getHighlightsForPage] as the **single source of truth**
     * — the exact same function that onDraw uses to paint highlights.
     */
    fun search(query: String, options: SearchOptions = SearchOptions()) {
        searchJob?.cancel()
        if (query.isBlank()) {
            onResults?.invoke(emptyList(), true)
            return
        }
        val q = query.trim()
        if (pdfUri == null) { onResults?.invoke(emptyList(), true); return }
        val total = pageCount
        if (total == 0) { onResults?.invoke(emptyList(), true); return }

        Log.d(TAG, "search  q='$q'  pages=$total cs=${options.caseSensitive} ww=${options.wholeWord}")

        searchJob = scope.launch {
            val all = mutableListOf<SearchResult>()

            for (page in 0 until total) {
                if (!isActive) break
                withContext(Dispatchers.Main) { onIndexingPage?.invoke(page, total) }

                // Index the page (extracts text layer, stores words in wordsCache)
                ensurePageIndexed(page)

                // Use getHighlightsForPage — THE SAME function onDraw uses.
                // Number of rects = number of occurrences on this page.
                val rects = getHighlightsForPage(page, q, options)
                val pageHits = rects.size

                if (pageHits > 0) {
                    Log.d(TAG, "  page $page → $pageHits hits")
                    repeat(pageHits) { all.add(SearchResult(page)) }
                }

                // Emit a progressive snapshot for every indexed page so the UI can react early.
                val snap = all.toList()
                withContext(Dispatchers.Main) { onResults?.invoke(snap, false) }
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
     * Extract the embedded text layer of the page via Pdfium and store
     * every word + normalised bounding box in [wordsCache].
     */
    private fun ensurePageIndexed(page: Int) {
        if (wordsCache.containsKey(page)) return

        val words = try {
            extractWords(page)
        } catch (e: Exception) {
            Log.e(TAG, "page $page text extraction failed: ${e.message}")
            emptyList()
        }
        wordsCache[page] = words
        Log.d(TAG, "page $page: indexed ${words.size} words → [${words.take(20).joinToString { it.text }}]")
        mainHandler.post { onPageIndexed?.invoke(page) }
    }

    // ── text extraction ───────────────────────────────────────────────────────

    /**
     * Builds the word list of a page from Pdfium's character-level data.
     * Char boxes come in PDF page space (origin bottom-left, y axis up),
     * so they are converted to normalised top-left coordinates (0‥1).
     */
    private fun extractWords(pageIndex: Int): List<WordElement> {
        val doc = openDocumentIfNeeded() ?: return emptyList()
        doc.openPage(pageIndex).use { pdfPage ->
            val pageWidth = pdfPage.getPageWidthPoint().toFloat().coerceAtLeast(1f)
            val pageHeight = pdfPage.getPageHeightPoint().toFloat().coerceAtLeast(1f)
            pdfPage.openTextPage().use { textPage ->
                val charCount = textPage.textPageCountChars()
                if (charCount <= 0) return emptyList()
                val text = textPage.textPageGetText(0, charCount) ?: return emptyList()

                val words = mutableListOf<WordElement>()
                val wordText = StringBuilder()
                var left = Float.MAX_VALUE
                var right = -Float.MAX_VALUE
                var topPage = -Float.MAX_VALUE     // max y in page space
                var bottomPage = Float.MAX_VALUE   // min y in page space

                fun flushWord() {
                    if (wordText.isNotEmpty() && left < right && bottomPage < topPage) {
                        words.add(
                            WordElement(
                                text = wordText.toString(),
                                rect = RectF(
                                    left / pageWidth,
                                    1f - (topPage / pageHeight),
                                    right / pageWidth,
                                    1f - (bottomPage / pageHeight)
                                )
                            )
                        )
                    }
                    wordText.clear()
                    left = Float.MAX_VALUE
                    right = -Float.MAX_VALUE
                    topPage = -Float.MAX_VALUE
                    bottomPage = Float.MAX_VALUE
                }

                val count = minOf(charCount, text.length)
                for (i in 0 until count) {
                    val c = text[i]
                    if (c.isWhitespace()) {
                        flushWord()
                        continue
                    }
                    wordText.append(c)
                    val box = textPage.textPageGetCharBox(i) ?: continue
                    // Note: in page space box.top >= box.bottom (y axis up)
                    if (box.left < left) left = box.left
                    if (box.right > right) right = box.right
                    if (box.top > topPage) topPage = box.top
                    if (box.bottom < bottomPage) bottomPage = box.bottom
                }
                flushWord()
                return words
            }
        }
    }

    // ── document lifecycle ────────────────────────────────────────────────────

    private fun openDocumentIfNeeded(): PdfDocument? {
        val uri = pdfUri ?: return null
        synchronized(documentLock) {
            document?.let { return it }
            return try {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
                val password = pdfPassword
                val doc = if (password.isNullOrEmpty()) {
                    pdfiumCore.newDocument(pfd)
                } else {
                    pdfiumCore.newDocument(pfd, password)
                }
                document = doc
                doc
            } catch (e: Exception) {
                Log.w(TAG, "openDocument failed: ${e.message}")
                null
            }
        }
    }

    private fun closeDocument() {
        synchronized(documentLock) {
            try {
                document?.close()
            } catch (e: Exception) {
                Log.w(TAG, "closeDocument: ${e.message}")
            }
            document = null
        }
    }

    companion object {
        private const val TAG = "PdfOcrEngine"
    }
}
