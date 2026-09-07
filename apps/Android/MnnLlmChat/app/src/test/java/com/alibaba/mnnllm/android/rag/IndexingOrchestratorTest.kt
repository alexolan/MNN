package com.alibaba.mnnllm.android.rag

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IndexingOrchestratorTest {

    private lateinit var context: Context
    private lateinit var database: RagDatabase
    private lateinit var orchestrator: IndexingOrchestrator
    private var knowledgeBaseId: Long = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("rag.db")
        database = RagDatabase(context)
        knowledgeBaseId = database.createKnowledgeBase("Documents", now = 1L)
        val chunker = DeterministicChunker(
            TokenCounter { text ->
                text.trim().takeIf(String::isNotEmpty)?.split(Regex("\\s+"))?.size ?: 0
            },
            ChunkingConfig(maxTokens = 16, includeHeadingPrefix = false)
        )
        orchestrator = IndexingOrchestrator(database, chunker)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase("rag.db")
    }

    @Test
    fun preparesChunksAndMovesDocumentToEmbedding() {
        val document = insertDocument(RagDocumentStatus.QUEUED)
        var clock = 10L

        val result = orchestrator.prepareForEmbedding(
            document = document,
            parse = {
                listOf(
                    LayoutBlock(
                        type = LayoutBlockType.PARAGRAPH,
                        text = "alpha beta",
                        readingOrder = 0
                    )
                )
            },
            now = { clock++ }
        )

        assertTrue(result is IndexingOrchestrator.IndexingResult.Prepared)
        val prepared = result as IndexingOrchestrator.IndexingResult.Prepared
        assertEquals(1, prepared.chunks.size)
        assertEquals("alpha beta", prepared.chunks.single().text)
        assertEquals(RagDocumentStatus.EMBEDDING, stored(document).status)
        database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM chunk WHERE document_id=?",
            arrayOf(document.id.toString())
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test
    fun parseFailureMovesDocumentToFailedAndStoresReason() {
        val document = insertDocument(RagDocumentStatus.QUEUED)

        val result = orchestrator.prepareForEmbedding(
            document = document,
            parse = { throw IllegalArgumentException("broken document") },
            now = { 20L }
        )

        assertTrue(result is IndexingOrchestrator.IndexingResult.Failed)
        assertEquals(RagDocumentStatus.FAILED, stored(document).status)
        assertEquals("broken document", stored(document).errorMessage)
    }

    @Test
    fun emptyParseResultMovesDocumentToFailed() {
        val document = insertDocument(RagDocumentStatus.QUEUED)

        val result = orchestrator.prepareForEmbedding(
            document = document,
            parse = { emptyList() },
            now = { 30L }
        )

        assertTrue(result is IndexingOrchestrator.IndexingResult.Failed)
        assertEquals(RagDocumentStatus.FAILED, stored(document).status)
        assertEquals("Document did not contain indexable text", stored(document).errorMessage)
    }

    @Test
    fun recoverInterruptedStatesQueuesDocumentForRetry() {
        listOf(
            RagDocumentStatus.PARSING,
            RagDocumentStatus.OCR,
            RagDocumentStatus.EMBEDDING
        ).forEachIndexed { index, status ->
            val document = insertDocument(status, hashSeed = index + 2)

            assertTrue(orchestrator.recoverInterrupted(document, now = 40L + index))
            val recovered = stored(document)
            assertEquals(RagDocumentStatus.QUEUED, recovered.status)
            assertEquals("Indexing was interrupted and queued for retry", recovered.errorMessage)
        }
    }

    @Test
    fun doesNotRecoverTerminalOrQueuedStates() {
        listOf(
            RagDocumentStatus.QUEUED,
            RagDocumentStatus.READY,
            RagDocumentStatus.FAILED
        ).forEachIndexed { index, status ->
            val document = insertDocument(status, hashSeed = index + 10)
            assertFalse(orchestrator.recoverInterrupted(document, now = 50L))
            assertEquals(status, stored(document).status)
        }
    }

    @Test
    fun markReadyAndEmbeddingFailurePersistTerminalStates() {
        val readyDocument = insertDocument(RagDocumentStatus.EMBEDDING, hashSeed = 20)
        orchestrator.markReady(readyDocument.id, now = 60L)
        assertEquals(RagDocumentStatus.READY, stored(readyDocument).status)

        val failedDocument = insertDocument(RagDocumentStatus.EMBEDDING, hashSeed = 21)
        orchestrator.markEmbeddingFailed(
            failedDocument.id,
            IllegalStateException("embedding failed"),
            now = 61L
        )
        assertEquals(RagDocumentStatus.FAILED, stored(failedDocument).status)
        assertEquals("embedding failed", stored(failedDocument).errorMessage)
    }

    private fun insertDocument(
        status: RagDocumentStatus,
        hashSeed: Int = 1
    ): RagDocument {
        val hash = hashSeed.toString(16).padStart(64, '0')
        val document = RagDocument(
            knowledgeBaseId = knowledgeBaseId,
            sourceUri = "content://documents/$hashSeed.txt",
            displayName = "$hashSeed.txt",
            mimeType = "text/plain",
            sizeBytes = 10L,
            sha256 = hash,
            parserVersion = 1,
            status = status,
            createdAt = 1L,
            updatedAt = 1L
        )
        return document.copy(id = database.insertDocument(document))
    }

    private fun stored(document: RagDocument): RagDocument {
        return requireNotNull(database.findDocumentByHash(document.knowledgeBaseId, document.sha256))
    }
}
