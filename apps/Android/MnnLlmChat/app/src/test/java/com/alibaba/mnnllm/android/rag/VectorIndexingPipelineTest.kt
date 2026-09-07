package com.alibaba.mnnllm.android.rag

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VectorIndexingPipelineTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: RagDatabase
    private lateinit var vectorStore: VectorStore
    private lateinit var orchestrator: IndexingOrchestrator
    private var knowledgeBaseId: Long = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("rag.db")
        database = RagDatabase(context)
        knowledgeBaseId = database.createKnowledgeBase("Documents", now = 1L)
        vectorStore = VectorStore(
            temporaryFolder.newFile("vectors.bin"),
            dimensions = 2,
            modelHash = "a".repeat(64)
        )
        orchestrator = IndexingOrchestrator(
            database,
            DeterministicChunker(
                TokenCounter { text -> text.split(Regex("\\s+")).size },
                ChunkingConfig(maxTokens = 16, includeHeadingPrefix = false)
            )
        )
    }

    @After
    fun tearDown() {
        vectorStore.close()
        database.close()
        context.deleteDatabase("rag.db")
    }

    @Test
    fun embedsInBoundedBatchesBindsVectorsAndMarksReady() {
        val document = insertDocument(1)
        insertChunks(document.id, 5)
        val engine = RecordingEngine()
        val pipeline = VectorIndexingPipeline(
            database,
            vectorStore,
            engine,
            orchestrator,
            batchSize = 2
        )

        val result = pipeline.indexDocument(document.id) { 100L }

        assertTrue(result is VectorIndexingPipeline.Result.Completed)
        assertEquals(5, (result as VectorIndexingPipeline.Result.Completed).indexedChunks)
        assertEquals(listOf(2, 2, 1), engine.batchSizes)
        assertEquals(5L, vectorStore.count())
        assertEquals(RagDocumentStatus.READY, stored(document).status)
        assertTrue(database.listChunksWithoutVectors(document.id).isEmpty())
    }

    @Test
    fun embeddingFailureMarksDocumentFailedWithoutPublishingBindings() {
        val document = insertDocument(2)
        insertChunks(document.id, 3)
        val engine = object : EmbeddingEngine {
            override val dimensions: Int = 2
            override fun embed(text: String): FloatArray {
                throw IllegalStateException("model inference failed")
            }
            override fun close() = Unit
        }
        val pipeline = VectorIndexingPipeline(
            database,
            vectorStore,
            engine,
            orchestrator,
            batchSize = 2
        )

        val result = pipeline.indexDocument(document.id) { 200L }

        assertTrue(result is VectorIndexingPipeline.Result.Failed)
        assertEquals(RagDocumentStatus.FAILED, stored(document).status)
        assertEquals("model inference failed", stored(document).errorMessage)
        assertEquals(3, database.listChunksWithoutVectors(document.id).size)
    }

    @Test
    fun invalidVectorDimensionsFailBeforeDatabasePublication() {
        val document = insertDocument(3)
        insertChunks(document.id, 1)
        val engine = object : EmbeddingEngine {
            override val dimensions: Int = 2
            override fun embed(text: String): FloatArray = floatArrayOf(1f)
            override fun close() = Unit
        }
        val pipeline = VectorIndexingPipeline(
            database,
            vectorStore,
            engine,
            orchestrator
        )

        val result = pipeline.indexDocument(document.id) { 300L }

        assertTrue(result is VectorIndexingPipeline.Result.Failed)
        assertEquals(RagDocumentStatus.FAILED, stored(document).status)
        assertEquals(1, database.listChunksWithoutVectors(document.id).size)
    }

    @Test
    fun databaseBatchBindingRollsBackWhenAnyChunkIsInvalid() {
        val document = insertDocument(4)
        insertChunks(document.id, 2)
        val chunks = database.listChunksWithoutVectors(document.id)

        runCatching {
            database.attachVectors(
                listOf(
                    chunks[0].id to VectorLocation(64L, 8, 2),
                    Long.MAX_VALUE to VectorLocation(72L, 8, 2)
                )
            )
        }

        assertEquals(2, database.listChunksWithoutVectors(document.id).size)
    }

    private inner class RecordingEngine : EmbeddingEngine {
        override val dimensions: Int = 2
        val batchSizes = mutableListOf<Int>()

        override fun embed(text: String): FloatArray {
            return floatArrayOf(text.length.toFloat(), 1f)
        }

        override fun embedBatch(texts: List<String>): List<FloatArray> {
            batchSizes += texts.size
            return texts.map(::embed)
        }

        override fun close() = Unit
    }

    private fun insertDocument(seed: Int): RagDocument {
        val document = RagDocument(
            knowledgeBaseId = knowledgeBaseId,
            sourceUri = "content://documents/$seed.txt",
            displayName = "$seed.txt",
            mimeType = "text/plain",
            sizeBytes = 10,
            sha256 = seed.toString(16).padStart(64, '0'),
            parserVersion = 1,
            status = RagDocumentStatus.EMBEDDING,
            createdAt = 1,
            updatedAt = 1
        )
        return document.copy(id = database.insertDocument(document))
    }

    private fun insertChunks(documentId: Long, count: Int) {
        database.replaceChunks(
            documentId,
            List(count) { index ->
                RagChunk(
                    documentId = documentId,
                    ordinal = index,
                    text = "chunk $index",
                    tokenCount = 2
                )
            }
        )
    }

    private fun stored(document: RagDocument): RagDocument {
        return requireNotNull(database.findDocumentByHash(document.knowledgeBaseId, document.sha256))
    }
}
