package com.alibaba.mnnllm.android.chat.input

import com.alibaba.mnnllm.android.rag.RagDocumentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAttachmentUiPolicyTest {

    @Test
    fun `persistent states map to attachment card states`() {
        assertEquals(SessionAttachmentUiStatus.PROCESSING, SessionAttachmentUiPolicy.status(RagDocumentStatus.QUEUED))
        assertEquals(SessionAttachmentUiStatus.PROCESSING, SessionAttachmentUiPolicy.status(RagDocumentStatus.PARSING))
        assertEquals(SessionAttachmentUiStatus.PROCESSING, SessionAttachmentUiPolicy.status(RagDocumentStatus.OCR))
        assertEquals(SessionAttachmentUiStatus.PROCESSING, SessionAttachmentUiPolicy.status(RagDocumentStatus.EMBEDDING))
        assertEquals(SessionAttachmentUiStatus.READY, SessionAttachmentUiPolicy.status(RagDocumentStatus.READY))
        assertEquals(SessionAttachmentUiStatus.FAILED, SessionAttachmentUiPolicy.status(RagDocumentStatus.FAILED))
    }

    @Test
    fun `known failures include actionable recovery advice`() {
        val cases = mapOf(
            "Unsupported attachment type" to "Choose TXT",
            "Attachment exceeds the size limit" to "32 MB",
            "No space left on device" to "Free device storage",
            "Attachment permission was denied or revoked" to "Select the file again",
            "OCR memory budget exceeded" to "lower-resolution",
            "PDF OCR time budget exceeded" to "Split the document",
            "OCR recognition failed" to "Check that the document is clear",
            "embedding model inference failed" to "configure a valid model",
            "Attachment indexing queue is full" to "Wait for another attachment",
            "Attachment indexing was cancelled" to "Retry when you are ready",
            "Unsupported or damaged image" to "valid copy"
        )
        cases.forEach { (failure, expectedAdvice) ->
            assertTrue(
                "Expected recovery advice for: $failure",
                SessionAttachmentUiPolicy.actionableError(failure).contains(expectedAdvice)
            )
        }
    }

    @Test
    fun `unknown and empty failures still provide retry guidance`() {
        assertTrue(SessionAttachmentUiPolicy.actionableError("Unexpected parser failure").contains("Retry"))
        assertTrue(SessionAttachmentUiPolicy.actionableError(null).contains("Retry"))
    }
}
