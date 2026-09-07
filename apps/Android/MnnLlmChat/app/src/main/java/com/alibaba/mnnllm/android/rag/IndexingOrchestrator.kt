package com.alibaba.mnnllm.android.rag

/**
 * Coordinates parsing and deterministic chunk generation while keeping every
 * persistent document state transition explicit and recoverable.
 */
class IndexingOrchestrator(
    private val database: RagDatabase,
    private val chunker: DeterministicChunker
) {
    fun prepareForEmbedding(
        document: RagDocument,
        parse: () -> List<LayoutBlock>,
        requiresOcr: Boolean = false,
        now: () -> Long = System::currentTimeMillis
    ): IndexingResult {
        require(document.id > 0) { "Document id must be positive" }
        require(document.status != RagDocumentStatus.READY) {
            "A ready document must be explicitly requeued before reindexing"
        }

        return try {
            updateRequired(document.id, RagDocumentStatus.PARSING, now = now())
            val blocks = parse()
            require(blocks.any { it.text.isNotBlank() }) {
                "Document did not contain indexable text"
            }

            if (requiresOcr) {
                updateRequired(document.id, RagDocumentStatus.OCR, now = now())
            }

            val chunks = chunker.chunk(document.id, blocks)
            require(chunks.isNotEmpty()) { "Document did not produce any chunks" }
            database.replaceChunks(document.id, chunks)
            updateRequired(document.id, RagDocumentStatus.EMBEDDING, now = now())
            IndexingResult.Prepared(chunks)
        } catch (error: Throwable) {
            val message = readableError(error)
            database.updateDocumentStatus(
                document.id,
                RagDocumentStatus.FAILED,
                message,
                now()
            )
            IndexingResult.Failed(message, error)
        }
    }

    fun markReady(documentId: Long, now: Long = System.currentTimeMillis()) {
        require(documentId > 0) { "Document id must be positive" }
        updateRequired(documentId, RagDocumentStatus.READY, now = now)
    }

    fun markEmbeddingFailed(
        documentId: Long,
        error: Throwable,
        now: Long = System.currentTimeMillis()
    ) {
        require(documentId > 0) { "Document id must be positive" }
        updateRequired(
            documentId,
            RagDocumentStatus.FAILED,
            readableError(error),
            now
        )
    }

    fun recoverInterrupted(document: RagDocument, now: Long = System.currentTimeMillis()): Boolean {
        require(document.id > 0) { "Document id must be positive" }
        if (document.status !in INTERRUPTED_STATES) return false
        return database.updateDocumentStatus(
            document.id,
            RagDocumentStatus.QUEUED,
            "Indexing was interrupted and queued for retry",
            now
        )
    }

    private fun updateRequired(
        documentId: Long,
        status: RagDocumentStatus,
        errorMessage: String? = null,
        now: Long
    ) {
        check(database.updateDocumentStatus(documentId, status, errorMessage, now)) {
            "Document no longer exists"
        }
    }

    private fun readableError(error: Throwable): String {
        val message = error.message?.trim().orEmpty()
        return when {
            message.isNotEmpty() -> message.take(MAX_ERROR_LENGTH)
            else -> error::class.java.simpleName.ifBlank { "Indexing failed" }
        }
    }

    sealed class IndexingResult {
        data class Prepared(val chunks: List<RagChunk>) : IndexingResult()
        data class Failed(val reason: String, val cause: Throwable) : IndexingResult()
    }

    companion object {
        private const val MAX_ERROR_LENGTH = 1_024
        private val INTERRUPTED_STATES = setOf(
            RagDocumentStatus.PARSING,
            RagDocumentStatus.OCR,
            RagDocumentStatus.EMBEDDING
        )
    }
}
