package com.saverio.pdfviewer.ui

import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.link.DefaultLinkHandler
import com.github.barteksc.pdfviewer.link.LinkHandler
import com.github.barteksc.pdfviewer.model.LinkTapEvent

class SavPdfViewerLinkHandler(private val pdfView: PDFView) : LinkHandler {
    override fun handleLinkEvent(event: LinkTapEvent) {
        val uri = event.link.uri
        val page = event.link.destPageIdx
        if (uri != null && !uri.isEmpty()) {
            handleUri(uri)
        } else page?.let { handlePage(it) }
    }

    private fun handleUri(uri: String) {
        try {
            pdfView.context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            )
        } catch (e: Exception) {
            error("No activity found for URI: $uri")
        }
    }

    private fun handlePage(page: Int) {
        pdfView.jumpTo(page)
    }

    companion object {
        private val TAG = DefaultLinkHandler::class.java.simpleName
    }
}