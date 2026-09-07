package com.alibaba.mnnllm.android.rag

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Append-only float32 vector storage. SQLite owns chunk-to-offset mappings.
 * A vector is visible to retrieval only after its database mapping is committed.
 */
class VectorStore(
    file: File,
    val dimensions: Int,
    private val modelHash: String
) : Closeable {

    private val access: RandomAccessFile
    private val channel: FileChannel
    private var vectorCount: Long

    init {
        require(dimensions > 0) { "Vector dimensions must be positive" }
        require(modelHash.matches(Regex("[0-9a-fA-F]{64}"))) {
            "Model hash must be a 64-character SHA-256 value"
        }
        file.parentFile?.let { parent ->
            check(parent.exists() || parent.mkdirs()) {
                "Unable to create vector store directory"
            }
        }
        access = RandomAccessFile(file, "rw")
        channel = access.channel
        vectorCount = if (channel.size() == 0L) {
            writeNewHeader()
            0L
        } else {
            readAndValidateHeader()
        }
    }

    @Synchronized
    fun append(vector: FloatArray): VectorLocation {
        require(vector.size == dimensions) {
            "Expected $dimensions dimensions but received ${vector.size}"
        }
        require(vector.all(Float::isFinite)) { "Vector contains NaN or infinity" }

        val offset = channel.size()
        val payloadBytes = Math.multiplyExact(dimensions, Float.SIZE_BYTES)
        val buffer = ByteBuffer.allocate(payloadBytes).order(ByteOrder.LITTLE_ENDIAN)
        vector.forEach(buffer::putFloat)
        buffer.flip()

        channel.position(offset)
        writeFully(buffer)
        channel.force(false)

        vectorCount += 1
        writeVectorCount(vectorCount)
        channel.force(true)
        return VectorLocation(offset, payloadBytes, dimensions)
    }

    @Synchronized
    fun read(location: VectorLocation): FloatArray {
        require(location.offset >= HEADER_SIZE) { "Vector offset points into the header" }
        require(location.dimensions == dimensions) { "Vector dimensions do not match the store" }
        val expectedLength = Math.multiplyExact(dimensions, Float.SIZE_BYTES)
        require(location.length == expectedLength) { "Vector byte length is invalid" }
        require(location.offset <= channel.size() - location.length) {
            "Vector payload extends beyond the store"
        }

        val buffer = ByteBuffer.allocate(location.length).order(ByteOrder.LITTLE_ENDIAN)
        channel.position(location.offset)
        readFully(buffer)
        buffer.flip()
        return FloatArray(dimensions) { buffer.float }
    }

    @Synchronized
    fun count(): Long = vectorCount

    @Synchronized
    override fun close() {
        channel.close()
        access.close()
    }

    private fun writeNewHeader() {
        val hashBytes = decodeSha256(modelHash)
        val header = ByteBuffer.allocate(HEADER_SIZE.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        header.put(MAGIC)
        header.putInt(FORMAT_VERSION)
        header.putInt(dimensions)
        header.putInt(SCALAR_FLOAT32)
        header.putLong(0L)
        header.put(hashBytes)
        header.putInt(0)
        header.flip()
        channel.position(0)
        writeFully(header)
        channel.force(true)
    }

    private fun readAndValidateHeader(): Long {
        if (channel.size() < HEADER_SIZE) throw EOFException("Vector store header is truncated")
        val header = ByteBuffer.allocate(HEADER_SIZE.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        channel.position(0)
        readFully(header)
        header.flip()

        val magic = ByteArray(MAGIC.size)
        header.get(magic)
        require(magic.contentEquals(MAGIC)) { "Invalid vector store magic" }
        require(header.int == FORMAT_VERSION) { "Unsupported vector store version" }
        require(header.int == dimensions) { "Vector dimensions do not match the existing store" }
        require(header.int == SCALAR_FLOAT32) { "Unsupported vector scalar type" }
        val count = header.long
        require(count >= 0) { "Invalid vector count" }
        val storedHash = ByteArray(SHA256_BYTES)
        header.get(storedHash)
        require(storedHash.contentEquals(decodeSha256(modelHash))) {
            "Embedding model hash does not match the existing vector store"
        }

        val payloadSize = channel.size() - HEADER_SIZE
        val vectorBytes = dimensions.toLong() * Float.SIZE_BYTES
        require(vectorBytes > 0 && payloadSize % vectorBytes == 0L) {
            "Vector store payload is truncated or corrupt"
        }
        val actualCount = payloadSize / vectorBytes
        require(count <= actualCount) {
            "Vector count exceeds the stored payload"
        }
        if (actualCount > count) {
            // A crash may leave fully written payload bytes before the committed
            // header count is updated. Discard that unreachable tail on reopen.
            channel.truncate(HEADER_SIZE + count * vectorBytes)
            channel.force(true)
        }
        return count
    }

    private fun writeVectorCount(count: Long) {
        val buffer = ByteBuffer.allocate(Long.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(count)
        buffer.flip()
        channel.position(VECTOR_COUNT_OFFSET)
        writeFully(buffer)
    }

    private fun writeFully(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
    }

    private fun readFully(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw EOFException("Unexpected end of vector store")
        }
    }

    private fun decodeSha256(value: String): ByteArray {
        return ByteArray(SHA256_BYTES) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    companion object {
        private val MAGIC = byteArrayOf('M'.code.toByte(), 'N'.code.toByte(), 'N'.code.toByte(), 'R'.code.toByte(), 'A'.code.toByte(), 'G'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
        private const val FORMAT_VERSION = 1
        private const val SCALAR_FLOAT32 = 1
        private const val SHA256_BYTES = 32
        private const val VECTOR_COUNT_OFFSET = 20L
        private const val HEADER_SIZE = 64L
    }
}
