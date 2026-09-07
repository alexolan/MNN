package com.alibaba.mnnllm.android.chat.input

import com.alibaba.mnnllm.android.rag.RagDocumentStatus

/** Converts persistent attachment states and technical failures into actionable UI feedback. */
object SessionAttachmentUiPolicy {
    fun status(status: RagDocumentStatus): SessionAttachmentUiStatus = when (status) {
        RagDocumentStatus.READY -> SessionAttachmentUiStatus.READY
        RagDocumentStatus.FAILED -> SessionAttachmentUiStatus.FAILED
        else -> SessionAttachmentUiStatus.PROCESSING
    }

    fun actionableError(message: String?): String {
        val normalized = message?.trim().orEmpty()
        val lower = normalized.lowercase()
        return when {
            lower.contains("unsupported") || lower.contains("attachment type") ->
                "Unsupported file format. Choose TXT, Markdown, DOCX, PDF, PNG, JPEG, or WebP."
            lower.contains("size limit") || lower.contains("too large") ->
                "The attachment is too large. Choose a file smaller than 32 MB."
            lower.contains("space") || lower.contains("disk") || lower.contains("storage") ->
                "Not enough storage space. Free device storage, then retry."
            lower.contains("permission") || lower.contains("revoked") ->
                "The attachment can no longer be read. Select the file again."
            lower.contains("ocr") && (lower.contains("memory") || lower.contains("pixel") || lower.contains("tile")) ->
                "OCR could not process this image within the memory limit. Use a smaller or lower-resolution image, then retry."
            lower.contains("ocr") && (lower.contains("time") || lower.contains("timeout")) ->
                "OCR took too long. Split the document or reduce its resolution, then retry."
            lower.contains("ocr") ->
                "OCR failed. Check that the document is clear and not damaged, then retry."
            lower.contains("model") || lower.contains("embedding") ->
                "The embedding model is unavailable. Reopen the app or configure a valid model, then retry."
            lower.contains("queue") && lower.contains("full") ->
                "Too many attachments are being processed. Wait for another attachment to finish, then retry."
            lower.contains("cancel") ->
                "Attachment indexing was cancelled. Retry when you are ready."
            lower.contains("damaged") || lower.contains("invalid") || lower.contains("missing") ->
                "The attachment is damaged or unavailable. Select a valid copy and retry."
            normalized.isNotEmpty() -> "$normalized Retry the attachment or select a valid copy."
            else -> "Attachment processing failed. Retry the attachment or select it again."
        }
    }
}
