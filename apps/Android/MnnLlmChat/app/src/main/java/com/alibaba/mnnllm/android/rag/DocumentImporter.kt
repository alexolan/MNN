package com.alibaba.mnnllm.android.rag

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Imports user-selected documents through the Storage Access Framework.
 * The caller owns document picking; this class validates metadata, persists
 * read access when available, hashes the stream, and creates database metadata.
 */
class DocumentImporter(
    context: Context,
    private val database: RagDatabase
) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    fun importDocuments(
        knowledgeBaseId: Long,
        uris: List<Uri>,
        parserVersion: Int,
        now: Long = System.currentTimeMillis()
    ): List<ImportResult> {
        require(knowledgeBaseId > 0) { "Knowledge base id must be positive" }
        require(parserVersion > 0) { "Parser version must be positive" }
        return uris.distinct().map { uri ->
            runCatching { importOne(knowledgeBaseId, uri, parserVersion, now) }
                .getOrElse { error -> ImportResult.Failed(uri, readableError(error)) }
        }
    }

    private fun importOne(
        knowledgeBaseId: Long,
        uri: Uri,
        parserVersion: Int,
        now: Long
    ): ImportResult {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) {
            "Only content URIs are accepted"
        }
        persistReadPermission(uri)
        val metadata = queryMetadata(uri)
        require(isSupported(metadata.displayName, metadata.mimeType)) {
            "Unsupported document type"
        }
        val digest = sha256(uri)
        val duplicate = database.findDocumentByHash(knowledgeBaseId, digest)
        if (duplicate != null) return ImportResult.Duplicate(uri, duplicate)

        val document = RagDocument(
            knowledgeBaseId = knowledgeBaseId,
            sourceUri = uri.toString(),
            displayName = metadata.displayName,
            mimeType = metadata.mimeType,
            sizeBytes = metadata.sizeBytes,
            sha256 = digest,
            parserVersion = parserVersion,
            status = RagDocumentStatus.QUEUED,
            createdAt = now,
            updatedAt = now
        )
        val id = database.insertDocument(document)
        return ImportResult.Imported(uri, document.copy(id = id))
    }

    private fun persistReadPermission(uri: Uri) {
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some providers grant only temporary access. The current import may still work;
            // later indexing will report a clear failure if access is revoked.
        }
    }

    private fun queryMetadata(uri: Uri): DocumentMetadata {
        var displayName: String? = null
        var size: Long? = null
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor: Cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        val safeName = displayName?.trim().takeUnless { it.isNullOrEmpty() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
            ?: "document"
        val safeSize = size ?: countBytes(uri)
        require(safeSize >= 0) { "Document size is invalid" }
        return DocumentMetadata(safeName, resolver.getType(uri), safeSize)
    }

    private fun sha256(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)?.use { raw ->
            BufferedInputStream(raw).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            }
        } ?: throw IOException("Unable to open document")
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun countBytes(uri: Uri): Long {
        var total = 0L
        resolver.openInputStream(uri)?.use { raw ->
            BufferedInputStream(raw).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total = Math.addExact(total, count.toLong())
                }
            }
        } ?: throw IOException("Unable to open document")
        return total
    }

    private fun isSupported(displayName: String, mimeType: String?): Boolean {
        val extension = displayName.substringAfterLast('.', "").lowercase()
        return extension in SUPPORTED_EXTENSIONS || mimeType in SUPPORTED_MIME_TYPES
    }

    private fun readableError(error: Throwable): String {
        return when (error) {
            is SecurityException -> "Document permission was denied or revoked"
            is IOException -> error.message ?: "Document could not be read"
            is IllegalArgumentException -> error.message ?: "Document is invalid"
            else -> "Document import failed"
        }
    }

    data class DocumentMetadata(
        val displayName: String,
        val mimeType: String?,
        val sizeBytes: Long
    )

    sealed class ImportResult {
        abstract val uri: Uri

        data class Imported(override val uri: Uri, val document: RagDocument) : ImportResult()
        data class Duplicate(override val uri: Uri, val existing: RagDocument) : ImportResult()
        data class Failed(override val uri: Uri, val reason: String) : ImportResult()
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private val SUPPORTED_EXTENSIONS = setOf("txt", "md", "markdown", "docx", "pdf")
        private val SUPPORTED_MIME_TYPES = setOf(
            "text/plain",
            "text/markdown",
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        )
    }
}
