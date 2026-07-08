package com.saverio.pdfviewer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.net.URLDecoder

object DocumentNameResolver {
    fun resolveDisplayName(context: Context, storedPath: String?, fallbackId: String = ""): String {
        val rawValue = storedPath?.trim().orEmpty()
        if (rawValue.isBlank()) return fallbackId.ifBlank { "document.pdf" }

        if (!rawValue.contains("://")) {
            return File(rawValue).name.ifBlank { fallbackId.ifBlank { rawValue } }
        }

        val uri = runCatching { Uri.parse(rawValue) }.getOrNull()
            ?: return decodeReadableName(rawValue, fallbackId)

        if (uri.scheme == "file") {
            return File(uri.path ?: rawValue).name.ifBlank { decodeReadableName(rawValue, fallbackId) }
        }

        if (uri.scheme == "content") {
            queryDisplayName(context, uri)?.let { return it }

            runCatching {
                if (DocumentsContract.isDocumentUri(context, uri)) {
                    val documentId = DocumentsContract.getDocumentId(uri)
                    readableDocumentId(documentId)?.let { return it }
                }
            }
        }

        return decodeReadableName(uri.lastPathSegment ?: rawValue, fallbackId)
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(0)?.trim()?.takeIf { it.isNotBlank() }
                    } else {
                        null
                    }
                }
        }.getOrNull()
    }

    private fun decodeReadableName(value: String, fallbackId: String): String {
        val decoded = runCatching { android.net.Uri.decode(value) }.getOrDefault(value)
        readableDocumentId(decoded)?.let { return it }
        return decoded.substringAfterLast('/').ifBlank {
            fallbackId.ifBlank { decoded }
        }
    }

    private fun readableDocumentId(value: String): String? {
        val candidate = value.substringAfterLast(':').trim()
        return candidate.takeIf { it.isNotBlank() }
    }
}

