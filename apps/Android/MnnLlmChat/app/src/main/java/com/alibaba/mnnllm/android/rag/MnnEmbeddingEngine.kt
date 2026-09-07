package com.alibaba.mnnllm.android.rag

import java.io.Closeable
import java.io.File

/**
 * Embedding inference boundary used by the indexing pipeline.
 * Implementations must return one finite vector for every input string.
 */
interface EmbeddingEngine : Closeable {
    val dimensions: Int
    fun embed(text: String): FloatArray

    fun embedBatch(texts: List<String>): List<FloatArray> = texts.map(::embed)
}

/**
 * JNI adapter for MNN Transformer Embedding::createEmbedding and txt_embedding.
 * The config file belongs to a bundle that has already passed manifest validation.
 */
class MnnEmbeddingEngine(
    configFile: File,
    expectedDimensions: Int
) : EmbeddingEngine {
    private var nativeHandle: Long

    override val dimensions: Int

    init {
        require(configFile.isFile) { "Embedding config file does not exist" }
        require(expectedDimensions > 0) { "Embedding dimensions must be positive" }
        nativeHandle = nativeCreate(configFile.canonicalPath)
        check(nativeHandle != 0L) { "Unable to load the MNN embedding model" }

        val nativeDimensions = nativeDimensions(nativeHandle)
        if (nativeDimensions != expectedDimensions) {
            nativeRelease(nativeHandle)
            nativeHandle = 0L
            throw IllegalStateException(
                "Embedding dimension mismatch: expected $expectedDimensions but model reports $nativeDimensions"
            )
        }
        dimensions = nativeDimensions
    }

    @Synchronized
    override fun embed(text: String): FloatArray {
        check(nativeHandle != 0L) { "Embedding engine is closed" }
        val normalized = EmbeddingInputNormalizer.normalize(text)
        require(normalized.isNotEmpty()) { "Embedding input must not be blank" }

        val vector = nativeEmbed(nativeHandle, normalized)
        check(vector.size == dimensions) {
            "Embedding output dimension mismatch: expected $dimensions but received ${vector.size}"
        }
        check(vector.all(Float::isFinite)) { "Embedding output contains NaN or infinity" }
        return vector
    }

    @Synchronized
    override fun embedBatch(texts: List<String>): List<FloatArray> {
        check(nativeHandle != 0L) { "Embedding engine is closed" }
        if (texts.isEmpty()) return emptyList()
        return texts.map(::embed)
    }

    @Synchronized
    override fun close() {
        if (nativeHandle != 0L) {
            nativeRelease(nativeHandle)
            nativeHandle = 0L
        }
    }

    private external fun nativeCreate(configPath: String): Long
    private external fun nativeDimensions(handle: Long): Int
    private external fun nativeEmbed(handle: Long, text: String): FloatArray
    private external fun nativeRelease(handle: Long)

    companion object {
        init {
            System.loadLibrary("mnnllmapp")
        }
    }
}
