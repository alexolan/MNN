package com.alibaba.mnnllm.android.rag

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

class VectorStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val modelHash = "a".repeat(64)

    @Test
    fun appendReadAndReopenPreservesVectors() {
        val file = temporaryFolder.newFile("vectors.bin")
        val first = floatArrayOf(0.1f, 0.2f, 0.3f)
        val second = floatArrayOf(-1.0f, 0.0f, 1.0f)

        val firstLocation: VectorLocation
        val secondLocation: VectorLocation
        VectorStore(file, dimensions = 3, modelHash = modelHash).use { store ->
            firstLocation = store.append(first)
            secondLocation = store.append(second)
            assertEquals(2L, store.count())
            assertArrayEquals(first, store.read(firstLocation), 0.0f)
            assertArrayEquals(second, store.read(secondLocation), 0.0f)
        }

        VectorStore(file, dimensions = 3, modelHash = modelHash).use { reopened ->
            assertEquals(2L, reopened.count())
            assertArrayEquals(first, reopened.read(firstLocation), 0.0f)
            assertArrayEquals(second, reopened.read(secondLocation), 0.0f)
        }
    }

    @Test
    fun rejectsWrongDimensionsAndNonFiniteValues() {
        val file = temporaryFolder.newFile("invalid-vectors.bin")

        VectorStore(file, dimensions = 2, modelHash = modelHash).use { store ->
            assertThrows(IllegalArgumentException::class.java) {
                store.append(floatArrayOf(1.0f))
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.append(floatArrayOf(Float.NaN, 1.0f))
            }
            assertEquals(0L, store.count())
        }
    }

    @Test
    fun rejectsExistingStoreFromAnotherEmbeddingModel() {
        val file = temporaryFolder.newFile("model-mismatch.bin")
        VectorStore(file, dimensions = 2, modelHash = modelHash).use { store ->
            store.append(floatArrayOf(1.0f, 2.0f))
        }

        assertThrows(IllegalArgumentException::class.java) {
            VectorStore(file, dimensions = 2, modelHash = "b".repeat(64)).close()
        }
    }

    @Test
    fun discardsUncommittedCompleteVectorTailOnReopen() {
        val file = temporaryFolder.newFile("uncommitted-tail.bin")
        VectorStore(file, dimensions = 2, modelHash = modelHash).use { store ->
            store.append(floatArrayOf(1.0f, 2.0f))
        }

        val committedLength = file.length()
        RandomAccessFile(file, "rw").use { access ->
            access.seek(access.length())
            access.write(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0))
        }

        VectorStore(file, dimensions = 2, modelHash = modelHash).use { reopened ->
            assertEquals(1L, reopened.count())
        }
        assertEquals(committedLength, file.length())
    }

    @Test
    fun rejectsTruncatedPayload() {
        val file = temporaryFolder.newFile("truncated.bin")
        VectorStore(file, dimensions = 2, modelHash = modelHash).use { store ->
            store.append(floatArrayOf(1.0f, 2.0f))
        }

        RandomAccessFile(file, "rw").use { access ->
            access.setLength(access.length() - 1L)
        }

        assertThrows(IllegalArgumentException::class.java) {
            VectorStore(file, dimensions = 2, modelHash = modelHash).close()
        }
    }
}
