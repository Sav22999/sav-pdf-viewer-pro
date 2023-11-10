package com.saverio.pdfviewer.ui

import android.app.AlertDialog
import android.text.Html
import android.content.Intent
import android.net.Uri
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.link.DefaultLinkHandler
import com.github.barteksc.pdfviewer.link.LinkHandler
import com.github.barteksc.pdfviewer.model.LinkTapEvent
import com.saverio.pdfviewer.R


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
            showConfirmationDialog(uri)
        } catch (e: Exception) {
            error("No activity found for URI: $uri")
        }
    }

    private fun handlePage(page: Int) {
        pdfView.jumpTo(page)
    }

    private fun showConfirmationDialog(url: String) {
        val alertDialogBuilder = AlertDialog.Builder(pdfView.context)

        alertDialogBuilder.setTitle("Open link")
        alertDialogBuilder.setMessage(
            Html.fromHtml(
                pdfView.context.getString(R.string.confirmation_open_link).replace("{{url}}", url)
            )
        )

        alertDialogBuilder.setPositiveButton("Yes") { dialog, which ->
            // Yes button
            pdfView.context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            )
        }

        alertDialogBuilder.setNegativeButton("No") { dialog, which ->
            // No button
        }

        val alertDialog = alertDialogBuilder.create()
        alertDialog.show()
    }

    companion object {
        private val TAG = DefaultLinkHandler::class.java.simpleName
    }
}