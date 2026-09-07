package com.alibaba.mnnllm.android.rag

import java.util.concurrent.CancellationException

/**
 * Parses and embeds one session attachment using bounded embedding batches.
 * Database vector mappings are published only after every batch succeeds.
 */
class SessionAttachmentVectorIndexingPipeline(
    private val database: RagDatabase,
    private val parsingPipeline: SessionAttachmentParsingPipeline,
    private val vectorStore: VectorStore,
    private val embeddingEngine: EmbeddingEngine,
    private val batchSize: Int = DEFAULT_BATCH_SIZE
) : SessionAttachmentIndexingOrchestrator.SessionAttachmentIndexProcessor {

    init {
        require(batchSize > 0) { "Embedding batch size must be positive" }
        require(vectorStore.dimensions == embeddingEngine.dimensions) {
            "Embedding engine and vector store dimensions do not match"
        }
    }

    override fun process(attachment: SessionAttachment, isCancelled: () -> Boolean) {
        checkNotCancelled(isCancelled)
        val prepared = parsingPipeline.parse(attachment)
        if (prepared is SessionAttachmentParsingPipeline.Result.Failed) {
            throw prepared.cause
        }

        checkNotCancelled(isCancelled)
        val chunks = database.listSessionAttachmentChunksWithoutVectors(
            attachmentId = attachment.id,
            sessionId = attachment.sessionId
        )
        require(chunks.isNotEmpty()) { "Attachment has no chunks awaiting embedding" }

        val bindings = mutableListOf<Pair<Long, VectorLocation>>()
        chunks.chunked(batchSize).forEach { batch ->
            checkNotCancelled(isCancelled)
            val vectors = embeddingEngine.embedBatch(batch.map(SessionAttachmentChunk::text))
            check(vectors.size == batch.size) {
                "Embedding engine returned an unexpected batch size"
            }
            batch.zip(vectors).forEach { (chunk, vector) ->
                checkNotCancelled(isCancelled)
                require(chunk.id > 0) { "Persisted attachment chunk id must be positive" }
                check(vector.size == embeddingEngine.dimensions) {
                    "Embedding output dimensions do not match the model"
                }
                check(vector.all(Float::isFinite)) {
                    "Embedding output contains NaN or infinity"
                }
                bindings += chunk.id to vectorStore.append(vector)
            }
        }

        checkNotCancelled(isCancelled)
        database.attachSessionAttachmentVectors(
            attachmentId = attachment.id,
            sessionId = attachment.sessionId,
            bindings = bindings
        )
    }

    private fun checkNotCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw CancellationException("Attachment indexing was cancelled")
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 4
    }
}
