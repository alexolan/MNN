package com.alibaba.mnnllm.android.rag

import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class RagDatabaseTest {

    private lateinit var database: RagDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase("rag.db")
        database = RagDatabase(context)
    }

    @After
    fun tearDown() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database.close()
        context.deleteDatabase("rag.db")
        File(context.getDatabasePath("rag.db").path + "-wal").delete()
        File(context.getDatabasePath("rag.db").path + "-shm").delete()
    }

    @Test
    fun createsKnowledgeBaseAndFindsDocumentByHash() {
        val knowledgeBaseId = database.createKnowledgeBase("Local docs", now = 100L)
        assertTrue(knowledgeBaseId > 0)

        val documentId = database.insertDocument(document(knowledgeBaseId))
        assertTrue(documentId > 0)

        val stored = database.findDocumentByHash(knowledgeBaseId, HASH.uppercase())
        assertNotNull(stored)
        assertEquals(documentId, stored!!.id)
        assertEquals(HASH, stored.sha256)
        assertEquals(RagDocumentStatus.QUEUED, stored.status)
    }

    @Test
    fun rejectsDuplicateDocumentHashWithinKnowledgeBase() {
        val knowledgeBaseId = database.createKnowledgeBase("Local docs")
        database.insertDocument(document(knowledgeBaseId))

        assertThrows(SQLiteConstraintException::class.java) {
            database.insertDocument(document(knowledgeBaseId, sourceUri = "content://docs/copy"))
        }
    }

    @Test
    fun sameHashIsAllowedInDifferentKnowledgeBases() {
        val firstBase = database.createKnowledgeBase("First")
        val secondBase = database.createKnowledgeBase("Second")

        assertTrue(database.insertDocument(document(firstBase)) > 0)
        assertTrue(database.insertDocument(document(secondBase)) > 0)
    }

    @Test
    fun updatesStatusAndClearsPreviousError() {
        val knowledgeBaseId = database.createKnowledgeBase("Local docs")
        val documentId = database.insertDocument(document(knowledgeBaseId))

        assertTrue(database.updateDocumentStatus(documentId, RagDocumentStatus.FAILED, "parse failed", 200L))
        assertEquals("parse failed", database.findDocumentByHash(knowledgeBaseId, HASH)?.errorMessage)

        assertTrue(database.updateDocumentStatus(documentId, RagDocumentStatus.EMBEDDING, now = 300L))
        val updated = database.findDocumentByHash(knowledgeBaseId, HASH)
        assertEquals(RagDocumentStatus.EMBEDDING, updated?.status)
        assertNull(updated?.errorMessage)
        assertEquals(300L, updated?.updatedAt)
    }

    @Test
    fun deletingKnowledgeBaseCascadesToDocumentsAndChunks() {
        val knowledgeBaseId = database.createKnowledgeBase("Local docs")
        val documentId = database.insertDocument(document(knowledgeBaseId))
        database.replaceChunks(
            documentId,
            listOf(
                RagChunk(documentId = documentId, ordinal = 0, text = "alpha", tokenCount = 1),
                RagChunk(documentId = documentId, ordinal = 1, text = "beta", tokenCount = 1)
            )
        )

        assertTrue(database.deleteKnowledgeBase(knowledgeBaseId))
        assertNull(database.findDocumentByHash(knowledgeBaseId, HASH))

        database.readableDatabase.rawQuery("SELECT COUNT(*) FROM chunk", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun replacingChunksIsAtomicWhenOneChunkIsInvalid() {
        val knowledgeBaseId = database.createKnowledgeBase("Local docs")
        val documentId = database.insertDocument(document(knowledgeBaseId))
        database.replaceChunks(
            documentId,
            listOf(RagChunk(documentId = documentId, ordinal = 0, text = "original", tokenCount = 1))
        )

        assertThrows(IllegalArgumentException::class.java) {
            database.replaceChunks(
                documentId,
                listOf(
                    RagChunk(documentId = documentId, ordinal = 0, text = "replacement", tokenCount = 1),
                    RagChunk(documentId = documentId + 1, ordinal = 1, text = "wrong owner", tokenCount = 1)
                )
            )
        }

        database.readableDatabase.rawQuery(
            "SELECT text FROM chunk WHERE document_id=? ORDER BY ordinal",
            arrayOf(documentId.toString())
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("original", cursor.getString(0))
            assertFalse(cursor.moveToNext())
        }
    }

    @Test
    fun attachesValidatedVectorLocationToChunk() {
        val knowledgeBaseId = database.createKnowledgeBase("Local docs")
        val documentId = database.insertDocument(document(knowledgeBaseId))
        database.replaceChunks(
            documentId,
            listOf(RagChunk(documentId = documentId, ordinal = 0, text = "alpha", tokenCount = 1))
        )

        val chunkId = database.readableDatabase.rawQuery(
            "SELECT _id FROM chunk WHERE document_id=?",
            arrayOf(documentId.toString())
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

        assertTrue(database.attachVector(chunkId, VectorLocation(offset = 64L, length = 12, dimensions = 3)))
        database.readableDatabase.rawQuery(
            "SELECT vector_offset, vector_length, vector_dimensions FROM chunk WHERE _id=?",
            arrayOf(chunkId.toString())
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(64L, cursor.getLong(0))
            assertEquals(12, cursor.getInt(1))
            assertEquals(3, cursor.getInt(2))
        }
    }

    @Test
    fun sessionAttachmentsAreIsolatedAndDeduplicatedWithinSession() {
        val first = database.insertSessionAttachment(attachment("session-a", HASH, "/private/a.txt"))
        assertTrue(first > 0)
        assertNotNull(database.findSessionAttachmentByHash("session-a", HASH.uppercase()))
        assertNull(database.findSessionAttachmentByHash("session-b", HASH))
        assertTrue(database.insertSessionAttachment(attachment("session-b", HASH, "/private/b.txt")) > 0)
        assertThrows(SQLiteConstraintException::class.java) {
            database.insertSessionAttachment(attachment("session-a", HASH, "/private/c.txt"))
        }
    }

    @Test
    fun deletingSessionAttachmentsCascadesChunksAndReturnsPrivateFiles() {
        val attachmentId = database.insertSessionAttachment(
            attachment("session-a", HASH, "/private/a.txt", RagDocumentStatus.READY)
        )
        database.replaceSessionAttachmentChunks(
            attachmentId,
            "session-a",
            listOf(
                SessionAttachmentChunk(
                    attachmentId = attachmentId,
                    sessionId = "session-a",
                    ordinal = 0,
                    text = "alpha",
                    tokenCount = 1,
                    vectorOffset = 64,
                    vectorLength = 2048,
                    vectorDimensions = 512
                )
            )
        )
        assertEquals(1, database.listReadySessionAttachmentChunks("session-a").size)
        assertTrue(database.listReadySessionAttachmentChunks("session-b").isEmpty())
        assertEquals(listOf("/private/a.txt"), database.deleteSessionAttachments("session-a"))
        assertTrue(database.listSessionAttachments("session-a").isEmpty())
        database.readableDatabase.rawQuery("SELECT COUNT(*) FROM session_attachment_chunk", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun rejectsCrossSessionChunkOwnership() {
        val attachmentId = database.insertSessionAttachment(attachment("session-a", HASH, "/private/a.txt"))
        assertThrows(IllegalArgumentException::class.java) {
            database.replaceSessionAttachmentChunks(
                attachmentId,
                "session-b",
                listOf(
                    SessionAttachmentChunk(
                        attachmentId = attachmentId,
                        sessionId = "session-b",
                        ordinal = 0,
                        text = "leak",
                        tokenCount = 1
                    )
                )
            )
        }
    }

    private fun attachment(
        sessionId: String,
        sha256: String,
        privatePath: String,
        status: RagDocumentStatus = RagDocumentStatus.QUEUED
    ) = SessionAttachment(
        sessionId = sessionId,
        sourceUri = "content://attachments/source",
        displayName = "source.txt",
        mimeType = "text/plain",
        sizeBytes = 12,
        sha256 = sha256,
        privatePath = privatePath,
        parserVersion = 1,
        status = status,
        createdAt = 100,
        updatedAt = 100
    )

    private fun document(
        knowledgeBaseId: Long,
        sourceUri: String = "content://docs/source"
    ): RagDocument {
        return RagDocument(
            knowledgeBaseId = knowledgeBaseId,
            sourceUri = sourceUri,
            displayName = "source.txt",
            mimeType = "text/plain",
            sizeBytes = 12L,
            sha256 = HASH,
            parserVersion = 1,
            status = RagDocumentStatus.QUEUED,
            createdAt = 100L,
            updatedAt = 100L
        )
    }

    companion object {
        private const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
