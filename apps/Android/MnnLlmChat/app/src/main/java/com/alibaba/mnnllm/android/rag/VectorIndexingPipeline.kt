package com.alibaba.mnnllm.android.rag

/**
 * Generates embeddings in bounded batches, appends them to the vector store,
 * and publishes all chunk-to-vector mappings in one SQLite transaction.
 */
class VectorIndexingPipeline(
    private val database: RagDatabase,
    private val vectorStore: VectorStore,
    private val embeddingEngine: EmbeddingEngine,
    private val indexingOrchestrator: IndexingOrchestrator,
    private val batchSize: Int = 8
) {
    init {
        require(batchSize > 0) { "Embedding batch size must be positive" }
        require(vectorStore.dimensions == embeddingEngine.dimensions) {
            "Embedding engine and vector store dimensions do not match"
        }
    }

    fun indexDocument(
        documentId: Long,
        now: () -> Long = System::currentTimeMillis
    ): Result {
        require(documentId > 0) { "Document id must be positive" }

        return try {
            val chunks = database.listChunksWithoutVectors(documentId)
            require(chunks.isNotEmpty()) { "Document has no chunks awaiting embedding" }

            val bindings = mutableListOf<Pair<Long, VectorLocation>>()
            chunks.chunked(batchSize).forEach { batch ->
                val vectors = embeddingEngine.embedBatch(batch.map(RagChunk::text))
                check(vectors.size == batch.size) {
                    "Embedding engine returned an unexpected batch size"
                }

                batch.zip(vectors).forEach { (chunk, vector) ->
                    require(chunk.id > 0) { "Persisted chunk id must be positive" }
                    check(vector.size == embeddingEngine.dimensions) {
                        "Embedding output dimensions do not match the model"
                    }
                    check(vector.all(Float::isFinite)) {
                        "Embedding output contains NaN or infinity"
                    }
                    bindings += chunk.id to vectorStore.append(vector)
                }
            }

            database.attachVectors(bindings)
            indexingOrchestrator.markReady(documentId, now())
            Result.Completed(bindings.size)
        } catch (error: Throwable) {
            indexingOrchestrator.markEmbeddingFailed(documentId, error, now())
            Result.Failed(readableError(error), error)
        }
    }

    private fun readableError(error: Throwable): String {
        return error.message?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_ERROR_LENGTH)
            ?: error::class.java.simpleName.ifBlank { "Embedding failed" }
    }

    sealed class Result {
        data class Completed(val indexedChunks: Int) : Result()
        data class Failed(val reason: String, val cause: Throwable) : Result()
    }

    companion object {
        private const val MAX_ERROR_LENGTH = 1_024
    }
}
