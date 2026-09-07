package com.alibaba.mnnllm.android.rag

import java.io.File
import java.io.FileInputStream

/**
 * Selects the bounded parser shared by permanent knowledge-base documents and
 * session attachments. PDF and image inputs are intentionally deferred to the
 * dedicated PDF/OCR stages.
 */
object DocumentParserFactory {
    fun create(displayName: String, mimeType: String?): DocumentParser {
        val extension = displayName.substringAfterLast('.', "").lowercase()
        return when {
            extension in setOf("md", "markdown") || mimeType == "text/markdown" ->
                MarkdownDocumentParser()
            extension == "docx" ||
                mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                DocxDocumentParser()
            extension == "txt" || mimeType == "text/plain" ->
                TextDocumentParser()
            extension == "pdf" || mimeType == "application/pdf" ->
                PdfDocumentParser()
            else -> error("No indexing parser is available for $displayName")
        }
    }
}

/**
 * Parses an imported private attachment copy and stores deterministic chunks
 * scoped to both attachmentId and sessionId. One attachment failure is persisted
 * locally and does not affect other attachment jobs.
 */
class SessionAttachmentParsingPipeline(
    private val database: RagDatabase,
    private val chunker: DeterministicChunker,
    private val ocrPipeline: DocumentOcrEngine = DocumentOcrPipeline(),
    private val now: () -> Long = System::currentTimeMillis
) {
    fun parse(attachment: SessionAttachment): Result {
        require(attachment.id > 0L) { "Attachment id must be positive" }
        require(attachment.sessionId.isNotBlank()) { "Session id must not be blank" }
        require(attachment.status != RagDocumentStatus.READY) {
            "Ready attachment must not be parsed again without an explicit reindex request"
        }

        return try {
            updateRequired(attachment, RagDocumentStatus.PARSING)
            val source = File(attachment.privatePath).canonicalFile
            require(source.isFile) { "Attachment private copy is missing" }
            require(source.length() == attachment.sizeBytes) { "Attachment private copy size mismatch" }

            val blocks = if (isImageAttachment(attachment)) {
                updateRequired(attachment, RagDocumentStatus.OCR)
                ocrPipeline.recognizeImage(source)
            } else {
                val parser = DocumentParserFactory.create(attachment.displayName, attachment.mimeType)
                FileInputStream(source).use(parser::parse)
            }
            require(blocks.any { it.text.isNotBlank() }) { "Attachment contains no indexable text" }

            val chunks = chunkBlocks(attachment, blocks)
            require(chunks.isNotEmpty()) { "Attachment produced no chunks" }
            RagResourceGovernance.requireChunkCountAllowed(chunks.size)

            database.replaceSessionAttachmentChunks(
                attachmentId = attachment.id,
                sessionId = attachment.sessionId,
                chunks = chunks
            )
            updateRequired(attachment, RagDocumentStatus.EMBEDDING)
            Result.Prepared(chunks)
        } catch (ocrRequired: PdfOcrRequiredException) {
            try {
                updateRequired(attachment, RagDocumentStatus.OCR)
                val source = File(attachment.privatePath).canonicalFile
                val ocrBlocks = ocrPipeline.recognizePdfPages(source, ocrRequired.pageNumbers)
                val combinedBlocks = (ocrRequired.extractedBlocks + ocrBlocks)
                    .sortedWith(compareBy<LayoutBlock> { it.pageNumber ?: Int.MAX_VALUE }.thenBy { it.readingOrder })
                    .mapIndexed { index, block -> block.copy(readingOrder = index) }
                require(combinedBlocks.any { it.text.isNotBlank() }) { "PDF OCR produced no indexable text" }
                val chunks = chunkBlocks(attachment, combinedBlocks)
                require(chunks.isNotEmpty()) { "PDF OCR produced no chunks" }
                RagResourceGovernance.requireChunkCountAllowed(chunks.size)
                database.replaceSessionAttachmentChunks(
                    attachmentId = attachment.id,
                    sessionId = attachment.sessionId,
                    chunks = chunks
                )
                updateRequired(attachment, RagDocumentStatus.EMBEDDING)
                Result.Prepared(chunks)
            } catch (error: Throwable) {
                persistFailure(attachment, error)
            }
        } catch (error: Throwable) {
            persistFailure(attachment, error)
        }
    }

    private fun isImageAttachment(attachment: SessionAttachment): Boolean {
        val extension = attachment.displayName.substringAfterLast('.', "").lowercase()
        val mimeType = attachment.mimeType?.lowercase()
        return extension in setOf("png", "jpg", "jpeg", "webp") ||
            mimeType in setOf("image/png", "image/jpeg", "image/webp")
    }

    private fun chunkBlocks(
        attachment: SessionAttachment,
        blocks: List<LayoutBlock>
    ): List<SessionAttachmentChunk> = chunker.chunk(attachment.id, blocks).map { chunk ->
        SessionAttachmentChunk(
            attachmentId = attachment.id,
            sessionId = attachment.sessionId,
            ordinal = chunk.ordinal,
            text = chunk.text,
            tokenCount = chunk.tokenCount,
            headingPath = chunk.headingPath,
            startPage = chunk.startPage,
            endPage = chunk.endPage,
            layoutJson = chunk.layoutJson
        )
    }

    private fun updateRequired(attachment: SessionAttachment, status: RagDocumentStatus) {
        check(
            database.updateSessionAttachmentStatus(
                attachmentId = attachment.id,
                sessionId = attachment.sessionId,
                status = status,
                now = now()
            )
        ) { "Attachment status update failed" }
    }

    private fun persistFailure(attachment: SessionAttachment, error: Throwable): Result.Failed {
        val reason = readableError(error)
        database.updateSessionAttachmentStatus(
            attachmentId = attachment.id,
            sessionId = attachment.sessionId,
            status = RagDocumentStatus.FAILED,
            errorMessage = reason,
            now = now()
        )
        return Result.Failed(reason, error)
    }

    private fun readableError(error: Throwable): String =
        RagResourceGovernance.sanitizeError(error)

    sealed class Result {
        data class Prepared(val chunks: List<SessionAttachmentChunk>) : Result()
        data class Failed(val reason: String, val cause: Throwable) : Result()
    }

    companion object {
    }
}
