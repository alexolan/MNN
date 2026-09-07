package com.alibaba.mnnllm.android.rag

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class SessionAttachmentImporterTest {

    private lateinit var context: Context
    private lateinit var database: RagDatabase
    private lateinit var attachmentRoot: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("rag.db")
        database = RagDatabase(context)
        attachmentRoot = File(context.cacheDir, "session-attachment-importer-test")
        attachmentRoot.deleteRecursively()
        assertTrue(attachmentRoot.mkdirs())
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase("rag.db")
        File(context.getDatabasePath("rag.db").path + "-wal").delete()
        File(context.getDatabasePath("rag.db").path + "-shm").delete()
        attachmentRoot.deleteRecursively()
    }

    @Test
    fun copiesHashesAndPersistsAttachment() {
        val uri = Uri.parse("content://attachment-test/guide.txt")
        val bytes = "hello session attachment".toByteArray(Charsets.UTF_8)
        register(uri, bytes, "text/plain")

        val result = importer().importAttachments(
            sessionId = "session-a",
            uris = listOf(uri),
            parserVersion = 1,
            now = 100L
        ).single()

        assertTrue(result is SessionAttachmentImporter.ImportResult.Imported)
        val attachment = (result as SessionAttachmentImporter.ImportResult.Imported).attachment
        assertTrue(attachment.id > 0)
        assertEquals("session-a", attachment.sessionId)
        assertEquals(uri.toString(), attachment.sourceUri)
        assertEquals("guide.txt", attachment.displayName)
        assertEquals("text/plain", attachment.mimeType)
        assertEquals(bytes.size.toLong(), attachment.sizeBytes)
        assertEquals(sha256(bytes), attachment.sha256)
        assertEquals(RagDocumentStatus.QUEUED, attachment.status)
        assertEquals(100L, attachment.createdAt)

        val privateCopy = File(attachment.privatePath)
        assertTrue(privateCopy.isFile)
        assertTrue(privateCopy.readBytes().contentEquals(bytes))
        assertEquals(attachment, database.findSessionAttachmentByHash("session-a", attachment.sha256))
    }

    @Test
    fun deDuplicatesSameContentWithinSessionAndKeepsOnePrivateCopy() {
        val first = Uri.parse("content://attachment-test/first.txt")
        val second = Uri.parse("content://attachment-test/second.txt")
        val bytes = "same attachment content".toByteArray(Charsets.UTF_8)
        register(first, bytes, "text/plain")
        register(second, bytes, "text/plain")

        val results = importer().importAttachments("session-a", listOf(first, second), parserVersion = 1)

        assertTrue(results[0] is SessionAttachmentImporter.ImportResult.Imported)
        assertTrue(results[1] is SessionAttachmentImporter.ImportResult.Duplicate)
        val imported = (results[0] as SessionAttachmentImporter.ImportResult.Imported).attachment
        val duplicate = (results[1] as SessionAttachmentImporter.ImportResult.Duplicate).existing
        assertEquals(imported.id, duplicate.id)
        assertEquals(1, database.listSessionAttachments("session-a").size)
        assertEquals(1, attachmentRoot.walkTopDown().count { it.isFile && !it.name.startsWith(".import-") })
        assertFalse(attachmentRoot.walkTopDown().any { it.name.startsWith(".import-") })
    }

    @Test
    fun sameContentInDifferentSessionsRemainsIsolated() {
        val first = Uri.parse("content://attachment-test/session-a.txt")
        val second = Uri.parse("content://attachment-test/session-b.txt")
        val bytes = "shared bytes".toByteArray(Charsets.UTF_8)
        register(first, bytes, "text/plain")
        register(second, bytes, "text/plain")

        val firstResult = importer().importAttachments("session-a", listOf(first), 1).single()
        val secondResult = importer().importAttachments("session-b", listOf(second), 1).single()

        assertTrue(firstResult is SessionAttachmentImporter.ImportResult.Imported)
        assertTrue(secondResult is SessionAttachmentImporter.ImportResult.Imported)
        val firstAttachment = (firstResult as SessionAttachmentImporter.ImportResult.Imported).attachment
        val secondAttachment = (secondResult as SessionAttachmentImporter.ImportResult.Imported).attachment
        assertNotEquals(firstAttachment.id, secondAttachment.id)
        assertEquals(firstAttachment.sha256, secondAttachment.sha256)
        assertNotEquals(firstAttachment.privatePath, secondAttachment.privatePath)
        assertEquals(1, database.listSessionAttachments("session-a").size)
        assertEquals(1, database.listSessionAttachments("session-b").size)
    }

    @Test
    fun rejectsOversizedAttachmentAndRemovesTemporaryCopy() {
        val uri = Uri.parse("content://attachment-test/large.txt")
        register(uri, ByteArray(9) { it.toByte() }, "text/plain")

        val result = importer(maxBytes = 8).importAttachments("session-a", listOf(uri), 1).single()

        assertTrue(result is SessionAttachmentImporter.ImportResult.Failed)
        assertEquals(
            "Attachment exceeds the size limit",
            (result as SessionAttachmentImporter.ImportResult.Failed).reason
        )
        assertTrue(database.listSessionAttachments("session-a").isEmpty())
        assertFalse(attachmentRoot.walkTopDown().any { it.isFile })
    }

    @Test
    fun rejectsUnsupportedMimeAndNonContentUriWithoutPersistentState() {
        val unsupported = Uri.parse("content://attachment-test/archive.bin")
        register(unsupported, byteArrayOf(1, 2, 3), "application/octet-stream")
        val fileUri = Uri.parse("file:///tmp/guide.txt")

        val results = importer().importAttachments("session-a", listOf(unsupported, fileUri), 1)

        assertEquals(2, results.size)
        assertEquals(
            "Unsupported attachment type",
            (results[0] as SessionAttachmentImporter.ImportResult.Failed).reason
        )
        assertEquals(
            "Only content URIs are accepted",
            (results[1] as SessionAttachmentImporter.ImportResult.Failed).reason
        )
        assertTrue(database.listSessionAttachments("session-a").isEmpty())
        assertFalse(attachmentRoot.walkTopDown().any { it.isFile })
    }

    @Test
    fun deDuplicatesRepeatedUriWithinSingleRequest() {
        val uri = Uri.parse("content://attachment-test/repeated.md")
        register(uri, "# Heading".toByteArray(Charsets.UTF_8), "text/markdown")

        val results = importer().importAttachments("session-a", listOf(uri, uri), 1)

        assertEquals(1, results.size)
        assertTrue(results.single() is SessionAttachmentImporter.ImportResult.Imported)
    }

    private fun importer(maxBytes: Long = SessionAttachmentImporter.DEFAULT_MAX_ATTACHMENT_BYTES) =
        SessionAttachmentImporter(
            context = context,
            database = database,
            attachmentRoot = attachmentRoot,
            maxAttachmentBytes = maxBytes
        )

    private fun register(uri: Uri, bytes: ByteArray, mimeType: String) {
        require(mimeType.isNotBlank())
        shadowOf(context.contentResolver).registerInputStream(uri, bytes.inputStream())
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
