package com.alibaba.mnnllm.android.rag

import java.io.Closeable
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Runs session attachment indexing through a bounded, single-consumer queue.
 *
 * The single consumer bounds parser, OCR and embedding memory usage. Persistent
 * attachment status remains the source of truth, so interrupted work can be
 * requeued after application restart.
 */
class SessionAttachmentIndexingOrchestrator(
    private val database: RagDatabase,
    private val processor: SessionAttachmentIndexProcessor,
    private val executor: Executor = Executors.newSingleThreadExecutor(),
    private val maxQueuedJobs: Int = DEFAULT_MAX_QUEUED_JOBS,
    private val now: () -> Long = System::currentTimeMillis
) : Closeable {

    init {
        require(maxQueuedJobs > 0) { "Maximum queued jobs must be positive" }
    }

    private val lock = Any()
    private val queue = ArrayDeque<Job>()
    private val queuedKeys = mutableSetOf<JobKey>()
    private val cancelledAttachments = mutableSetOf<JobKey>()
    private val cancelledSessions = mutableSetOf<String>()
    private var running: Job? = null
    private var drainScheduled = false
    private var closed = false

    fun enqueue(attachment: SessionAttachment): EnqueueResult {
        require(attachment.id > 0) { "Attachment id must be positive" }
        require(attachment.sessionId.isNotBlank()) { "Session id must not be blank" }
        val key = JobKey(attachment.id, attachment.sessionId)
        synchronized(lock) {
            check(!closed) { "Attachment indexing orchestrator is closed" }
            if (attachment.status == RagDocumentStatus.READY) return EnqueueResult.AlreadyReady
            if (running?.key == key || key in queuedKeys) return EnqueueResult.AlreadyQueued
            if (queue.size >= maxQueuedJobs) return EnqueueResult.QueueFull
            cancelledAttachments.remove(key)
            cancelledSessions.remove(attachment.sessionId)
            queue.addLast(Job(key))
            queuedKeys += key
            scheduleDrainLocked()
            return EnqueueResult.Queued
        }
    }

    fun recoverInterrupted(): RecoveryResult {
        val candidates = database.listSessionAttachmentsForRecovery()
        var recovered = 0
        var alreadyQueued = 0
        var queueFull = 0
        candidates.forEach { attachment ->
            if (attachment.status in INTERRUPTED_STATES) {
                database.updateSessionAttachmentStatus(
                    attachmentId = attachment.id,
                    sessionId = attachment.sessionId,
                    status = RagDocumentStatus.QUEUED,
                    errorMessage = RECOVERY_MESSAGE,
                    now = now()
                )
            }
            when (enqueue(attachment.copy(status = RagDocumentStatus.QUEUED))) {
                EnqueueResult.Queued -> recovered++
                EnqueueResult.AlreadyQueued -> alreadyQueued++
                EnqueueResult.QueueFull -> queueFull++
                EnqueueResult.AlreadyReady -> Unit
            }
        }
        return RecoveryResult(recovered, alreadyQueued, queueFull)
    }

    fun cancelAttachment(attachmentId: Long, sessionId: String): Boolean {
        require(attachmentId > 0) { "Attachment id must be positive" }
        require(sessionId.isNotBlank()) { "Session id must not be blank" }
        val key = JobKey(attachmentId, sessionId)
        synchronized(lock) {
            cancelledAttachments += key
            val removed = queue.removeAll { it.key == key }
            if (removed) queuedKeys.remove(key)
            return removed || running?.key == key
        }
    }

    fun cancelSession(sessionId: String): Int {
        require(sessionId.isNotBlank()) { "Session id must not be blank" }
        synchronized(lock) {
            cancelledSessions += sessionId
            val removedKeys = queue.filter { it.key.sessionId == sessionId }.map(Job::key)
            queue.removeAll { it.key.sessionId == sessionId }
            queuedKeys.removeAll(removedKeys.toSet())
            return removedKeys.size + if (running?.key?.sessionId == sessionId) 1 else 0
        }
    }

    fun queuedCount(): Int = synchronized(lock) { queue.size }

    fun isRunning(attachmentId: Long, sessionId: String): Boolean =
        synchronized(lock) { running?.key == JobKey(attachmentId, sessionId) }

    override fun close() {
        synchronized(lock) {
            closed = true
            queue.clear()
            queuedKeys.clear()
            running?.let { cancelledAttachments += it.key }
        }
        (executor as? ExecutorService)?.shutdownNow()
    }

    private fun scheduleDrainLocked() {
        if (drainScheduled || closed) return
        drainScheduled = true
        executor.execute(::drain)
    }

    private fun drain() {
        while (true) {
            val job = synchronized(lock) {
                if (closed) {
                    drainScheduled = false
                    return
                }
                queue.pollFirst()?.also {
                    queuedKeys.remove(it.key)
                    running = it
                } ?: run {
                    running = null
                    drainScheduled = false
                    return
                }
            }
            process(job)
            synchronized(lock) {
                if (running?.key == job.key) running = null
                cancelledAttachments.remove(job.key)
            }
        }
    }

    private fun process(job: Job) {
        if (isCancelled(job.key)) return
        val attachment = database.getSessionAttachment(job.key.attachmentId, job.key.sessionId) ?: return
        if (attachment.status == RagDocumentStatus.READY) return
        try {
            processor.process(attachment) { isCancelled(job.key) }
            if (isCancelled(job.key)) throw CancellationException(CANCELLED_MESSAGE)
            check(
                database.updateSessionAttachmentStatus(
                    attachmentId = attachment.id,
                    sessionId = attachment.sessionId,
                    status = RagDocumentStatus.READY,
                    errorMessage = null,
                    now = now()
                )
            ) { "Attachment no longer exists" }
        } catch (cancelled: CancellationException) {
            database.updateSessionAttachmentStatus(
                attachmentId = attachment.id,
                sessionId = attachment.sessionId,
                status = RagDocumentStatus.QUEUED,
                errorMessage = CANCELLED_MESSAGE,
                now = now()
            )
        } catch (error: Throwable) {
            database.updateSessionAttachmentStatus(
                attachmentId = attachment.id,
                sessionId = attachment.sessionId,
                status = RagDocumentStatus.FAILED,
                errorMessage = readableError(error),
                now = now()
            )
        }
    }

    private fun isCancelled(key: JobKey): Boolean = synchronized(lock) {
        closed || key in cancelledAttachments || key.sessionId in cancelledSessions
    }

    private fun readableError(error: Throwable): String =
        RagResourceGovernance.sanitizeError(error)

    fun interface SessionAttachmentIndexProcessor {
        fun process(attachment: SessionAttachment, isCancelled: () -> Boolean)
    }

    enum class EnqueueResult {
        Queued,
        AlreadyQueued,
        AlreadyReady,
        QueueFull
    }

    data class RecoveryResult(
        val recovered: Int,
        val alreadyQueued: Int,
        val queueFull: Int
    )

    private data class JobKey(val attachmentId: Long, val sessionId: String)
    private data class Job(val key: JobKey)

    companion object {
        const val DEFAULT_MAX_QUEUED_JOBS = 16
        private const val RECOVERY_MESSAGE = "Indexing was interrupted and queued for retry"
        private const val CANCELLED_MESSAGE = "Attachment indexing was cancelled"
        private val INTERRUPTED_STATES = setOf(
            RagDocumentStatus.PARSING,
            RagDocumentStatus.OCR,
            RagDocumentStatus.EMBEDDING
        )
    }
}
