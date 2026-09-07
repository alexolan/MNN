package com.alibaba.mnnllm.android.rag

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RagRetrievalTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: RagDatabase
    private lateinit var vectorStore: VectorStore
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
    }

    @After
    fun tearDown() {
        vectorStore.close()
        database.close()
        context.deleteDatabase("rag.db")
    }

    @Test
    fun ranksReadyChunksByCosineSimilarityAndTopK() {
        insertVectorDocument(1, RagDocumentStatus.READY, listOf(
            "east" to floatArrayOf(1f, 0f),
            "north east" to floatArrayOf(0.8f, 0.2f),
            "north" to floatArrayOf(0f, 1f)
        ))
        insertVectorDocument(2, RagDocumentStatus.FAILED, listOf(
            "failed but close" to floatArrayOf(1f, 0f)
        ))
        val retriever = RagRetriever(
            database,
            vectorStore,
            FixedEngine(floatArrayOf(1f, 0f)),
            config = RetrievalConfig(topK = 2, candidateMultiplier = 2)
        )

        val hits = retriever.retrieve(knowledgeBaseId, "direction")

        assertEquals(listOf("east", "north east"), hits.map(RetrievalHit::text))
        assertTrue(hits[0].score >= hits[1].score)
        assertFalse(hits.any { it.text == "failed but close" })
    }

    @Test
    fun appliesMinimumScoreAndOptionalReranker() {
        insertVectorDocument(3, RagDocumentStatus.READY, listOf(
            "first" to floatArrayOf(1f, 0f),
            "second" to floatArrayOf(0.9f, 0.1f),
            "opposite" to floatArrayOf(-1f, 0f)
        ))
        var candidateCount = 0
        val reranker = RagReranker { _, candidates ->
            candidateCount = candidates.size
            candidates.reversed()
        }
        val retriever = RagRetriever(
            database,
            vectorStore,
            FixedEngine(floatArrayOf(1f, 0f)),
            reranker,
            RetrievalConfig(topK = 2, candidateMultiplier = 3, minimumScore = 0f)
        )

        val hits = retriever.retrieve(knowledgeBaseId, "query")

        assertEquals(2, hits.size)
        assertEquals(2, candidateCount)
        assertEquals("second", hits.first().text)
        assertFalse(hits.any { it.text == "opposite" })
    }

    @Test
    fun zeroVectorProducesFiniteZeroScore() {
        insertVectorDocument(4, RagDocumentStatus.READY, listOf(
            "zero" to floatArrayOf(0f, 0f)
        ))
        val retriever = RagRetriever(
            database,
            vectorStore,
            FixedEngine(floatArrayOf(1f, 0f)),
            config = RetrievalConfig(topK = 1)
        )

        val hit = retriever.retrieve(knowledgeBaseId, "query").single()

        assertEquals(0f, hit.score, 0f)
        assertTrue(hit.score.isFinite())
    }

    @Test
    fun mergesKnowledgeBaseAndCurrentSessionAttachmentsByScore() {
        insertVectorDocument(5, RagDocumentStatus.READY, listOf(
            "knowledge result" to floatArrayOf(0.8f, 0.2f)
        ))
        insertSessionAttachmentVector(
            sessionId = "session-a",
            seed = 6,
            text = "session result",
            vector = floatArrayOf(1f, 0f)
        )
        val retriever = RagRetriever(
            database,
            vectorStore,
            FixedEngine(floatArrayOf(1f, 0f)),
            config = RetrievalConfig(
                topK = 2,
                candidateMultiplier = 2,
                maxKnowledgeBaseHits = 1,
                maxSessionAttachmentHits = 1
            )
        )

        val hits = retriever.retrieve(knowledgeBaseId, "session-a", "query")

        assertEquals(listOf("session result", "knowledge result"), hits.map(RetrievalHit::text))
        assertEquals(
            listOf(RagSourceType.SESSION_ATTACHMENT, RagSourceType.KNOWLEDGE_BASE),
            hits.map(RetrievalHit::sourceType)
        )
        assertEquals("session-a", hits.first().sessionId)
    }

    @Test
    fun sessionAttachmentRetrievalNeverLeaksAcrossSessions() {
        insertSessionAttachmentVector(
            sessionId = "session-a",
            seed = 7,
            text = "allowed",
            vector = floatArrayOf(0.9f, 0.1f)
        )
        insertSessionAttachmentVector(
            sessionId = "session-b",
            seed = 8,
            text = "must not leak",
            vector = floatArrayOf(1f, 0f)
        )
        val retriever = RagRetriever(
            database,
            vectorStore,
            FixedEngine(floatArrayOf(1f, 0f)),
            config = RetrievalConfig(topK = 4, maxKnowledgeBaseHits = 0, maxSessionAttachmentHits = 4)
        )

        val hits = retriever.retrieve(null, "session-a", "query")

        assertEquals(listOf("allowed"), hits.map(RetrievalHit::text))
        assertTrue(hits.all { it.sessionId == "session-a" })
        assertTrue(hits.all { it.sourceType == RagSourceType.SESSION_ATTACHMENT })
    }

    @Test
    fun sourceBudgetsAreAppliedBeforeUnifiedRanking() {
        insertVectorDocument(9, RagDocumentStatus.READY, listOf(
            "kb strongest" to floatArrayOf(1f, 0f),
            "kb second" to floatArrayOf(0.95f, 0.05f)
        ))
        insertSessionAttachmentVector(
            sessionId = "session-a",
            seed = 10,
            text = "session reserved",
            vector = floatArrayOf(0.7f, 0.3f)
        )
        val retriever = RagRetriever(
            database,
            vectorStore,
            FixedEngine(floatArrayOf(1f, 0f)),
            config = RetrievalConfig(
                topK = 2,
                candidateMultiplier = 3,
                maxKnowledgeBaseHits = 1,
                maxSessionAttachmentHits = 1
            )
        )

        val hits = retriever.retrieve(knowledgeBaseId, "session-a", "query")

        assertEquals(2, hits.size)
        assertEquals(1, hits.count { it.sourceType == RagSourceType.KNOWLEDGE_BASE })
        assertEquals(1, hits.count { it.sourceType == RagSourceType.SESSION_ATTACHMENT })
        assertEquals(listOf("kb strongest", "session reserved"), hits.map(RetrievalHit::text))
    }

    @Test
    fun contextAssemblerAddsStableCitationsAndLocationMetadata() {
        val hits = listOf(
            RetrievalHit(10, 20, "guide.md", "Install the package.", 0.9f, listOf("Setup"), 2, 3),
            RetrievalHit(11, 21, "faq.txt", "Restart the application.", 0.8f)
        )
        val context = RagContextAssembler(
            RagContextConfig(maxCharacters = 1_000, maxHits = 2)
        ).assemble("How do I set it up?", hits)

        assertTrue(context.prompt.contains("[1] guide.md | Setup, pages 2-3"))
        assertTrue(context.prompt.contains("[2] faq.txt"))
        assertTrue(context.prompt.contains("User question:\nHow do I set it up?"))
        assertEquals(listOf(1, 2), context.citations.map(RagCitation::index))
        assertEquals(listOf(10L, 11L), context.citations.map(RagCitation::chunkId))
    }

    @Test
    fun contextAssemblerHonorsHitAndCharacterBudgets() {
        val hits = List(4) { index ->
            RetrievalHit(
                chunkId = index + 1L,
                documentId = 100L,
                documentName = "document.txt",
                text = "x".repeat(200),
                score = 1f - index * 0.1f
            )
        }
        val context = RagContextAssembler(
            RagContextConfig(maxCharacters = 150, maxHits = 2)
        ).assemble("question", hits)

        assertTrue(context.citations.size <= 2)
        assertTrue(context.prompt.contains("User question:\nquestion"))
        assertTrue(context.prompt.contains("..."))
    }

    @Test
    fun emptyRetrievalFallsBackToOriginalNormalizedQuestion() {
        val context = RagContextAssembler().assemble("  plain\tquestion  ", emptyList())

        assertEquals("plain question", context.prompt)
        assertTrue(context.citations.isEmpty())
    }

    private fun insertVectorDocument(
        seed: Int,
        status: RagDocumentStatus,
        entries: List<Pair<String, FloatArray>>
    ) {
        val document = RagDocument(
            knowledgeBaseId = knowledgeBaseId,
            sourceUri = "content://documents/$seed.txt",
            displayName = "$seed.txt",
            mimeType = "text/plain",
            sizeBytes = 10,
            sha256 = seed.toString(16).padStart(64, '0'),
            parserVersion = 1,
            status = status,
            createdAt = 1,
            updatedAt = 1
        )
        val documentId = database.insertDocument(document)
        database.replaceChunks(
            documentId,
            entries.mapIndexed { index, entry ->
                RagChunk(
                    documentId = documentId,
                    ordinal = index,
                    text = entry.first,
                    tokenCount = 1,
                    headingPath = listOf("Section $index"),
                    startPage = index + 1,
                    endPage = index + 1
                )
            }
        )
        val chunks = database.listChunksWithoutVectors(documentId)
        database.attachVectors(
            chunks.zip(entries).map { (chunk, entry) ->
                chunk.id to vectorStore.append(entry.second)
            }
        )
    }

    private fun insertSessionAttachmentVector(
        sessionId: String,
        seed: Int,
        text: String,
        vector: FloatArray
    ) {
        val attachmentId = database.insertSessionAttachment(
            SessionAttachment(
                sessionId = sessionId,
                sourceUri = "content://attachments/$seed.txt",
                displayName = "$seed.txt",
                mimeType = "text/plain",
                sizeBytes = text.length.toLong(),
                sha256 = seed.toString(16).padStart(64, '0'),
                privatePath = temporaryFolder.newFile("attachment-$seed.txt").absolutePath,
                parserVersion = 1,
                status = RagDocumentStatus.READY,
                createdAt = 1,
                updatedAt = 1
            )
        )
        database.replaceSessionAttachmentChunks(
            attachmentId,
            sessionId,
            listOf(
                SessionAttachmentChunk(
                    attachmentId = attachmentId,
                    sessionId = sessionId,
                    ordinal = 0,
                    text = text,
                    tokenCount = 1,
                    headingPath = listOf("Attachment")
                )
            )
        )
        val chunk = database.listSessionAttachmentChunksWithoutVectors(
            attachmentId,
            sessionId
        ).single()
        database.attachSessionAttachmentVectors(
            attachmentId,
            sessionId,
            listOf(chunk.id to vectorStore.append(vector))
        )
    }

    private class FixedEngine(private val queryVector: FloatArray) : EmbeddingEngine {
        override val dimensions: Int = queryVector.size
        override fun embed(text: String): FloatArray = queryVector.copyOf()
        override fun close() = Unit
    }
}
