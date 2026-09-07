package com.alibaba.mnnllm.android.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RagResourceGovernanceTest {

    @Test
    fun enforcesSessionAttachmentCountAndByteLimits() {
        val root = Files.createTempDirectory("rag-governance").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                RagResourceGovernance.requireAttachmentImportAllowed(
                    attachmentRoot = root,
                    existingAttachmentCount = RagResourceGovernance.MAX_ATTACHMENTS_PER_SESSION,
                    existingAttachmentBytes = 0L,
                    incomingBytes = 1L
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                RagResourceGovernance.requireAttachmentImportAllowed(
                    attachmentRoot = root,
                    existingAttachmentCount = 1,
                    existingAttachmentBytes = RagResourceGovernance.MAX_TOTAL_ATTACHMENT_BYTES_PER_SESSION,
                    incomingBytes = 1L
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun enforcesPerAttachmentAndChunkLimits() {
        val root = Files.createTempDirectory("rag-governance").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                RagResourceGovernance.requireAttachmentImportAllowed(
                    attachmentRoot = root,
                    existingAttachmentCount = 0,
                    existingAttachmentBytes = 0L,
                    incomingBytes = RagResourceGovernance.MAX_ATTACHMENT_BYTES + 1L
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                RagResourceGovernance.requireChunkCountAllowed(0)
            }
            assertThrows(IllegalArgumentException::class.java) {
                RagResourceGovernance.requireChunkCountAllowed(
                    RagResourceGovernance.MAX_CHUNKS_PER_ATTACHMENT + 1
                )
            }
            RagResourceGovernance.requireChunkCountAllowed(
                RagResourceGovernance.MAX_CHUNKS_PER_ATTACHMENT
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sanitizesUrisAndPrivatePathsFromPersistedErrors() {
        val error = IllegalStateException(
            "Failed content://provider/private/document at " +
                "file:///data/user/0/app/files/secret.txt and /storage/emulated/0/private.txt"
        )

        val sanitized = RagResourceGovernance.sanitizeError(error)

        assertTrue(sanitized.contains("[content-uri]"))
        assertTrue(sanitized.contains("[private-file]"))
        assertTrue(sanitized.contains("[private-path]"))
        assertFalse(sanitized.contains("content://provider"))
        assertFalse(sanitized.contains("/data/user/0"))
        assertFalse(sanitized.contains("/storage/emulated/0"))
    }

    @Test
    fun truncatesPersistedErrorsToBoundedLength() {
        val sanitized = RagResourceGovernance.sanitizeError(
            IllegalStateException("x".repeat(RagResourceGovernance.MAX_ERROR_LENGTH + 100))
        )

        assertEquals(RagResourceGovernance.MAX_ERROR_LENGTH, sanitized.length)
    }

    @Test
    fun deletesOnlyFilesInsideAllowedPrivateRoot() {
        val parent = Files.createTempDirectory("rag-delete").toFile()
        val root = File(parent, "allowed").apply { mkdirs() }
        val privateFile = File(root, "session/document.txt").apply {
            parentFile!!.mkdirs()
            writeText("private")
        }
        val outside = File(parent, "outside.txt").apply { writeText("keep") }
        try {
            assertTrue(RagResourceGovernance.deletePrivateFile(privateFile.path, root))
            assertFalse(privateFile.exists())
            assertThrows(IllegalArgumentException::class.java) {
                RagResourceGovernance.deletePrivateFile(outside.path, root)
            }
            assertTrue(outside.exists())
        } finally {
            parent.deleteRecursively()
        }
    }
}
