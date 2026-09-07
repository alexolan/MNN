package com.alibaba.mnnllm.android.rag

import java.io.File

/**
 * Centralized privacy and resource limits for local RAG processing.
 *
 * RAG document parsing, OCR, embedding and retrieval are local-only. Source
 * URIs, private paths, session identifiers, document text and vectors must not
 * be included in logs or network requests.
 */
object RagResourceGovernance {
    const val MAX_ATTACHMENT_BYTES = 32L * 1024L * 1024L
    const val MAX_ATTACHMENTS_PER_SESSION = 10
    const val MAX_TOTAL_ATTACHMENT_BYTES_PER_SESSION = 128L * 1024L * 1024L
    const val MIN_FREE_STORAGE_RESERVE_BYTES = 64L * 1024L * 1024L
    const val MAX_CHUNKS_PER_ATTACHMENT = 4_096
    const val MAX_ERROR_LENGTH = 1_024

    fun requireAttachmentImportAllowed(
        attachmentRoot: File,
        existingAttachmentCount: Int,
        existingAttachmentBytes: Long,
        incomingBytes: Long?
    ) {
        require(existingAttachmentCount >= 0) { "Attachment count must not be negative" }
        require(existingAttachmentBytes >= 0L) { "Attachment byte total must not be negative" }
        require(existingAttachmentCount < MAX_ATTACHMENTS_PER_SESSION) {
            "Session attachment limit reached"
        }
        incomingBytes?.let { size ->
            require(size in 0L..MAX_ATTACHMENT_BYTES) { "Attachment exceeds the size limit" }
            require(existingAttachmentBytes <= MAX_TOTAL_ATTACHMENT_BYTES_PER_SESSION - size) {
                "Session attachment storage limit reached"
            }
            require(attachmentRoot.usableSpace >= size + MIN_FREE_STORAGE_RESERVE_BYTES) {
                "Not enough storage space for this attachment"
            }
        }
    }

    fun requireChunkCountAllowed(chunkCount: Int) {
        require(chunkCount in 1..MAX_CHUNKS_PER_ATTACHMENT) {
            "Attachment exceeds the chunk limit"
        }
    }

    fun sanitizeError(error: Throwable): String {
        val type = error::class.java.simpleName.ifBlank { "Attachment processing failed" }
        val message = error.message
            ?.replace(Regex("content://\\S+", RegexOption.IGNORE_CASE), "[content-uri]")
            ?.replace(Regex("file://\\S+", RegexOption.IGNORE_CASE), "[private-file]")
            ?.replace(Regex("/(?:data|storage|sdcard|mnt)/\\S+", RegexOption.IGNORE_CASE), "[private-path]")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.take(MAX_ERROR_LENGTH)
        return message ?: type
    }

    fun deletePrivateFile(path: String, allowedRoot: File): Boolean {
        val root = allowedRoot.canonicalFile
        val candidate = File(path).canonicalFile
        require(candidate.toPath().startsWith(root.toPath()) && candidate != root) {
            "Attachment path is outside private storage"
        }
        return !candidate.exists() || candidate.delete()
    }
}
