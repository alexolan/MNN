package com.alibaba.mnnllm.android.rag

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/**
 * Copies SAF attachments into app-private storage and persists metadata only
 * after the private copy has been completely written and verified.
 */
class SessionAttachmentImporter(
    context: Context,
    private val database: RagDatabase,
    private val attachmentRoot: File = File(context.applicationContext.filesDir, PRIVATE_ROOT),
    private val maxAttachmentBytes: Long = DEFAULT_MAX_ATTACHMENT_BYTES
) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    init {
        require(maxAttachmentBytes > 0L) { "Maximum attachment size must be positive" }
    }

    fun importAttachments(
        sessionId: String,
        uris: List<Uri>,
        parserVersion: Int,
        now: Long = System.currentTimeMillis()
    ): List<ImportResult> {
        require(sessionId.isNotBlank()) { "Session id must not be blank" }
        require(parserVersion > 0) { "Parser version must be positive" }
        return uris.distinct().map { uri ->
            runCatching { importOne(sessionId, uri, parserVersion, now) }
                .getOrElse { ImportResult.Failed(uri, readableError(it)) }
        }
    }

    private fun importOne(
        sessionId: String,
        uri: Uri,
        parserVersion: Int,
        now: Long
    ): ImportResult {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Only content URIs are accepted" }
        val metadata = queryMetadata(uri)
        require(isSupported(metadata.displayName, metadata.mimeType)) { "Unsupported attachment type" }
        metadata.sizeBytes?.let {
            require(it in 0..maxAttachmentBytes) { "Attachment exceeds the size limit" }
        }
        val existingAttachments = database.listSessionAttachments(sessionId)
        RagResourceGovernance.requireAttachmentImportAllowed(
            attachmentRoot = attachmentRoot,
            existingAttachmentCount = existingAttachments.size,
            existingAttachmentBytes = existingAttachments.sumOf(SessionAttachment::sizeBytes),
            incomingBytes = metadata.sizeBytes
        )

        val sessionDirectory = File(attachmentRoot, sha256(sessionId.toByteArray(Charsets.UTF_8)))
        require(sessionDirectory.mkdirs() || sessionDirectory.isDirectory) {
            "Unable to create attachment directory"
        }
        val temporary = File(sessionDirectory, ".import-${UUID.randomUUID()}")
        var published: File? = null
        try {
            val copied = copyAndHash(uri, temporary)
            if (metadata.sizeBytes == null) {
                RagResourceGovernance.requireAttachmentImportAllowed(
                    attachmentRoot = attachmentRoot,
                    existingAttachmentCount = existingAttachments.size,
                    existingAttachmentBytes = existingAttachments.sumOf(SessionAttachment::sizeBytes),
                    incomingBytes = copied.sizeBytes
                )
            }
            val duplicate = database.findSessionAttachmentByHash(sessionId, copied.sha256)
            if (duplicate != null) {
                temporary.delete()
                return ImportResult.Duplicate(uri, duplicate)
            }

            val extension = safeExtension(metadata.displayName)
            val destination = File(sessionDirectory, copied.sha256 + extension)
            if (destination.exists()) {
                require(destination.isFile && destination.length() == copied.sizeBytes) {
                    "Existing attachment copy is invalid"
                }
                temporary.delete()
            } else {
                require(temporary.renameTo(destination)) { "Unable to publish attachment copy" }
                published = destination
            }

            val attachment = SessionAttachment(
                sessionId = sessionId,
                sourceUri = uri.toString(),
                displayName = metadata.displayName,
                mimeType = metadata.mimeType,
                sizeBytes = copied.sizeBytes,
                sha256 = copied.sha256,
                privatePath = destination.canonicalPath,
                parserVersion = parserVersion,
                status = RagDocumentStatus.QUEUED,
                createdAt = now,
                updatedAt = now
            )
            return try {
                val id = database.insertSessionAttachment(attachment)
                ImportResult.Imported(uri, attachment.copy(id = id))
            } catch (error: Throwable) {
                published?.delete()
                throw error
            }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun copyAndHash(uri: Uri, destination: File): CopiedFile {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        resolver.openInputStream(uri)?.use { raw ->
            BufferedInputStream(raw).use { input ->
                FileOutputStream(destination).use { fileOutput ->
                    BufferedOutputStream(fileOutput).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            total = Math.addExact(total, count.toLong())
                            if (total > maxAttachmentBytes) {
                                throw IllegalArgumentException("Attachment exceeds the size limit")
                            }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
        } ?: throw IOException("Unable to open attachment")
        require(destination.length() == total) { "Attachment copy size mismatch" }
        return CopiedFile(total, digest.digest().toHex())
    }

    private fun queryMetadata(uri: Uri): Metadata {
        var displayName: String? = null
        var sizeBytes: Long? = null
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor: Cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { displayName = cursor.getString(it) }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { sizeBytes = cursor.getLong(it) }
            }
        }
        val safeName = displayName?.trim().takeUnless { it.isNullOrBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "attachment"
        sizeBytes?.let { require(it >= 0L) { "Attachment size is invalid" } }
        return Metadata(safeName, resolver.getType(uri), sizeBytes)
    }

    private fun isSupported(displayName: String, mimeType: String?): Boolean {
        val extension = displayName.substringAfterLast('.', "").lowercase()
        return extension in SUPPORTED_EXTENSIONS || mimeType in SUPPORTED_MIME_TYPES
    }

    private fun safeExtension(displayName: String): String {
        val extension = displayName.substringAfterLast('.', "").lowercase()
        return if (extension in SUPPORTED_EXTENSIONS) ".$extension" else ""
    }

    private fun readableError(error: Throwable): String = when (error) {
        is SecurityException -> "Attachment permission was denied or revoked"
        is IOException, is IllegalArgumentException -> RagResourceGovernance.sanitizeError(error)
        else -> "Attachment import failed"
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class Metadata(
        val displayName: String,
        val mimeType: String?,
        val sizeBytes: Long?
    )

    private data class CopiedFile(val sizeBytes: Long, val sha256: String)

    sealed class ImportResult {
        abstract val uri: Uri

        data class Imported(
            override val uri: Uri,
            val attachment: SessionAttachment
        ) : ImportResult()

        data class Duplicate(
            override val uri: Uri,
            val existing: SessionAttachment
        ) : ImportResult()

        data class Failed(
            override val uri: Uri,
            val reason: String
        ) : ImportResult()
    }

    companion object {
        const val PRIVATE_ROOT = "rag/session-attachments"
        const val DEFAULT_MAX_ATTACHMENT_BYTES = 32L * 1024L * 1024L
        private const val BUFFER_SIZE = 64 * 1024
        private val SUPPORTED_EXTENSIONS = setOf(
            "txt", "md", "markdown", "pdf", "docx", "png", "jpg", "jpeg", "webp"
        )
        private val SUPPORTED_MIME_TYPES = setOf(
            "text/plain",
            "text/markdown",
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "image/png",
            "image/jpeg",
            "image/webp"
        )
    }
}
