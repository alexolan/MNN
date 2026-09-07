package com.alibaba.mnnllm.android.rag

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class DocumentImporterTest {

    private lateinit var context: Context
    private lateinit var database: RagDatabase
    private lateinit var importer: DocumentImporter

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("rag.db")
        database = RagDatabase(context)
        importer = DocumentImporter(context, database)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase("rag.db")
    }

    @Test
    fun importsSupportedContentUriAndCalculatesHash() {
        val knowledgeBaseId = database.createKnowledgeBase("Documents")
        val uri = Uri.parse("content://rag-test/guide.txt")
        val bytes = "hello local knowledge".toByteArray(Charsets.UTF_8)
        register(uri, bytes, "text/plain")

        val result = importer.importDocuments(
            knowledgeBaseId = knowledgeBaseId,
            uris = listOf(uri),
            parserVersion = 1,
            now = 100L
        ).single()

        assertTrue(result is DocumentImporter.ImportResult.Imported)
        val document = (result as DocumentImporter.ImportResult.Imported).document
        assertEquals(uri.toString(), document.sourceUri)
        assertEquals("guide.txt", document.displayName)
        assertEquals(bytes.size.toLong(), document.sizeBytes)
        assertEquals(RagDocumentStatus.QUEUED, document.status)
        assertEquals(64, document.sha256.length)
    }

    @Test
    fun reportsDuplicateWithinSameKnowledgeBase() {
        val knowledgeBaseId = database.createKnowledgeBase("Documents")
        val first = Uri.parse("content://rag-test/first.txt")
        val second = Uri.parse("content://rag-test/second.txt")
        val bytes = "same content".toByteArray(Charsets.UTF_8)
        register(first, bytes, "text/plain")
        register(second, bytes, "text/plain")

        val results = importer.importDocuments(
            knowledgeBaseId,
            listOf(first, second),
            parserVersion = 1
        )

        assertTrue(results[0] is DocumentImporter.ImportResult.Imported)
        assertTrue(results[1] is DocumentImporter.ImportResult.Duplicate)
    }

    @Test
    fun rejectsUnsupportedDocumentTypeWithoutDatabaseInsert() {
        val knowledgeBaseId = database.createKnowledgeBase("Documents")
        val uri = Uri.parse("content://rag-test/archive.bin")
        register(uri, byteArrayOf(1, 2, 3), "application/octet-stream")

        val result = importer.importDocuments(
            knowledgeBaseId,
            listOf(uri),
            parserVersion = 1
        ).single()

        assertTrue(result is DocumentImporter.ImportResult.Failed)
        assertEquals(
            "Unsupported document type",
            (result as DocumentImporter.ImportResult.Failed).reason
        )
        database.readableDatabase.rawQuery("SELECT COUNT(*) FROM document", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun rejectsNonContentUri() {
        val knowledgeBaseId = database.createKnowledgeBase("Documents")
        val uri = Uri.parse("file:///tmp/guide.txt")

        val result = importer.importDocuments(
            knowledgeBaseId,
            listOf(uri),
            parserVersion = 1
        ).single()

        assertTrue(result is DocumentImporter.ImportResult.Failed)
        assertEquals(
            "Only content URIs are accepted",
            (result as DocumentImporter.ImportResult.Failed).reason
        )
    }

    @Test
    fun deDuplicatesRepeatedUriInSingleRequest() {
        val knowledgeBaseId = database.createKnowledgeBase("Documents")
        val uri = Uri.parse("content://rag-test/repeated.md")
        register(uri, "# Heading".toByteArray(), "text/markdown")

        val results = importer.importDocuments(
            knowledgeBaseId,
            listOf(uri, uri),
            parserVersion = 1
        )

        assertEquals(1, results.size)
        assertTrue(results.single() is DocumentImporter.ImportResult.Imported)
    }

    private fun register(uri: Uri, bytes: ByteArray, mimeType: String) {
        require(mimeType.isNotBlank())
        val resolver = context.contentResolver
        shadowOf(resolver).registerInputStream(uri, bytes.inputStream())
    }
}
