package com.alibaba.mnnllm.android.rag

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionAttachmentIndexingOrchestratorTest {

    private lateinit var context: Context
    private lateinit var database: RagDatabase
    private lateinit var executor: ManualExecutor

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("rag.db")
        database = RagDatabase(context)
        executor = ManualExecutor()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase("rag.db")
    }

    @Test
    fun boundedQueueRejectsExcessAndDeduplicatesJobs() {
        val processed = mutableListOf<Long>()
        val orchestrator = orchestrator(maxQueuedJobs = 2) { attachment, _ ->
            processed += attachment.id
        }
        val first = insertAttachment("session-a", 1)
        val second = insertAttachment("session-a", 2)
        val third = insertAttachment("session-a", 3)

        assertEquals(SessionAttachmentIndexingOrchestrator.EnqueueResult.Queued, orchestrator.enqueue(first))
        assertEquals(SessionAttachmentIndexingOrchestrator.EnqueueResult.AlreadyQueued, orchestrator.enqueue(first))
        assertEquals(SessionAttachmentIndexingOrchestrator.EnqueueResult.Queued, orchestrator.enqueue(second))
        assertEquals(SessionAttachmentIndexingOrchestrator.EnqueueResult.QueueFull, orchestrator.enqueue(third))
        assertEquals(2, orchestrator.queuedCount())

        executor.runAll()

        assertEquals(listOf(first.id, second.id), processed)
        assertEquals(RagDocumentStatus.READY, stored(first).status)
        assertEquals(RagDocumentStatus.READY, stored(second).status)
        assertEquals(RagDocumentStatus.QUEUED, stored(third).status)
    }

    @Test
    fun cancelledQueuedAttachmentNeverRuns() {
        val processed = mutableListOf<Long>()
        val orchestrator = orchestrator { attachment, _ -> processed += attachment.id }
        val attachment = insertAttachment("session-a", 4)

        orchestrator.enqueue(attachment)
        assertTrue(orchestrator.cancelAttachment(attachment.id, attachment.sessionId))
        executor.runAll()

        assertTrue(processed.isEmpty())
        assertEquals(RagDocumentStatus.QUEUED, stored(attachment).status)
    }

    @Test
    fun sessionCancellationRemovesOnlyThatSessionsJobs() {
        val processed = mutableListOf<Long>()
        val orchestrator = orchestrator { attachment, _ -> processed += attachment.id }
        val first = insertAttachment("session-a", 5)
        val second = insertAttachment("session-a", 6)
        val other = insertAttachment("session-b", 7)
        orchestrator.enqueue(first)
        orchestrator.enqueue(second)
        orchestrator.enqueue(other)

        assertEquals(2, orchestrator.cancelSession("session-a"))
        executor.runAll()

        assertEquals(listOf(other.id), processed)
        assertEquals(RagDocumentStatus.QUEUED, stored(first).status)
        assertEquals(RagDocumentStatus.QUEUED, stored(second).status)
        assertEquals(RagDocumentStatus.READY, stored(other).status)
    }

    @Test
    fun runningCancellationIsObservedBeforeReadyPublication() {
        lateinit var orchestrator: SessionAttachmentIndexingOrchestrator
        val attachment = insertAttachment("session-a", 8)
        orchestrator = orchestrator { current, isCancelled ->
            assertEquals(attachment.id, current.id)
            assertFalse(isCancelled())
            assertTrue(orchestrator.cancelAttachment(current.id, current.sessionId))
            assertTrue(isCancelled())
        }

        orchestrator.enqueue(attachment)
        executor.runAll()

        val stored = stored(attachment)
        assertEquals(RagDocumentStatus.QUEUED, stored.status)
        assertEquals("Attachment indexing was cancelled", stored.errorMessage)
    }

    @Test
    fun restartRecoveryRequeuesInterruptedStatesInStableOrder() {
        val queued = insertAttachment("session-a", 9, RagDocumentStatus.QUEUED, updatedAt = 40)
        val parsing = insertAttachment("session-a", 10, RagDocumentStatus.PARSING, updatedAt = 10)
        val ocr = insertAttachment("session-b", 11, RagDocumentStatus.OCR, updatedAt = 20)
        val embedding = insertAttachment("session-b", 12, RagDocumentStatus.EMBEDDING, updatedAt = 30)
        insertAttachment("session-c", 13, RagDocumentStatus.READY, updatedAt = 1)
        insertAttachment("session-c", 14, RagDocumentStatus.FAILED, updatedAt = 2)
        val processed = mutableListOf<Long>()
        val orchestrator = orchestrator { attachment, _ -> processed += attachment.id }

        val recovery = orchestrator.recoverInterrupted()

        assertEquals(4, recovery.recovered)
        assertEquals(0, recovery.alreadyQueued)
        assertEquals(0, recovery.queueFull)
        assertEquals("Indexing was interrupted and queued for retry", stored(parsing).errorMessage)
        assertEquals("Indexing was interrupted and queued for retry", stored(ocr).errorMessage)
        assertEquals("Indexing was interrupted and queued for retry", stored(embedding).errorMessage)
        executor.runAll()
        assertEquals(listOf(parsing.id, ocr.id, embedding.id, queued.id), processed)
    }

    @Test
    fun processorFailureIsIsolatedAndNextJobContinues() {
        val failed = insertAttachment("session-a", 15)
        val healthy = insertAttachment("session-a", 16)
        val orchestrator = orchestrator { attachment, _ ->
            if (attachment.id == failed.id) error("embedding failed")
        }

        orchestrator.enqueue(failed)
        orchestrator.enqueue(healthy)
        executor.runAll()

        assertEquals(RagDocumentStatus.FAILED, stored(failed).status)
        assertEquals("embedding failed", stored(failed).errorMessage)
        assertEquals(RagDocumentStatus.READY, stored(healthy).status)
    }

    @Test
    fun closeShutsDownOwnedExecutorService() {
        val service = TrackingExecutorService()
        val orchestrator = SessionAttachmentIndexingOrchestrator(
            database = database,
            processor = SessionAttachmentIndexingOrchestrator.SessionAttachmentIndexProcessor { _, _ -> },
            executor = service
        )

        orchestrator.close()

        assertTrue(service.isShutdown)
        assertTrue(service.shutdownNowCalled)
    }

    private fun orchestrator(
        maxQueuedJobs: Int = 16,
        processor: SessionAttachmentIndexingOrchestrator.SessionAttachmentIndexProcessor
    ) = SessionAttachmentIndexingOrchestrator(
        database = database,
        processor = processor,
        executor = executor,
        maxQueuedJobs = maxQueuedJobs,
        now = { 100L }
    )

    private fun insertAttachment(
        sessionId: String,
        seed: Int,
        status: RagDocumentStatus = RagDocumentStatus.QUEUED,
        updatedAt: Long = seed.toLong()
    ): SessionAttachment {
        val file = File(context.cacheDir, "step17-$sessionId-$seed.txt").apply {
            writeText("attachment $seed")
        }
        val attachment = SessionAttachment(
            sessionId = sessionId,
            sourceUri = "content://attachments/$seed",
            displayName = "$seed.txt",
            mimeType = "text/plain",
            sizeBytes = file.length(),
            sha256 = seed.toString(16).padStart(64, '0'),
            privatePath = file.canonicalPath,
            parserVersion = 1,
            status = status,
            createdAt = 1L,
            updatedAt = updatedAt
        )
        return attachment.copy(id = database.insertSessionAttachment(attachment))
    }

    private fun stored(attachment: SessionAttachment): SessionAttachment =
        requireNotNull(database.getSessionAttachment(attachment.id, attachment.sessionId))

    private class TrackingExecutorService : AbstractExecutorService() {
        private var shutdown = false
        var shutdownNowCalled = false
            private set

        override fun shutdown() {
            shutdown = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shutdown = true
            shutdownNowCalled = true
            return mutableListOf()
        }

        override fun isShutdown(): Boolean = shutdown

        override fun isTerminated(): Boolean = shutdown

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = shutdown

        override fun execute(command: Runnable) {
            command.run()
        }
    }

    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }
}
