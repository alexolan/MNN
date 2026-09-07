package com.alibaba.mnnllm.android.rag

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class SessionAttachmentParsingPipelineTest {
    private lateinit var context: Context
    private lateinit var database: RagDatabase
    private lateinit var root: File
    private lateinit var pipeline: SessionAttachmentParsingPipeline
    private lateinit var ocrEngine: FakeDocumentOcrEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
        context.deleteDatabase("rag.db")
        database = RagDatabase(context)
        root = File(context.cacheDir, "session-attachment-parsing-test").apply {
            deleteRecursively()
            mkdirs()
        }
        ocrEngine = FakeDocumentOcrEngine()
        pipeline = SessionAttachmentParsingPipeline(
            database = database,
            chunker = DeterministicChunker(
                TokenCounter { text -> text.codePointCount(0, text.length).coerceAtLeast(1) },
                ChunkingConfig(maxTokens = 128)
            ),
            ocrPipeline = ocrEngine,
            now = { 200L }
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase("rag.db")
        root.deleteRecursively()
    }

    @Test
    fun parsesTextPrivateCopyAndStoresSessionScopedChunks() {
        val attachment = insertAttachment(
            name = "notes.txt",
            mime = "text/plain",
            bytes = "first paragraph\n\nsecond paragraph".toByteArray()
        )

        val result = pipeline.parse(attachment)

        assertTrue(result is SessionAttachmentParsingPipeline.Result.Prepared)
        val prepared = result as SessionAttachmentParsingPipeline.Result.Prepared
        assertEquals(2, prepared.chunks.size)
        assertTrue(prepared.chunks.all { it.attachmentId == attachment.id })
        assertTrue(prepared.chunks.all { it.sessionId == attachment.sessionId })
        val stored = database.listSessionAttachments(attachment.sessionId).single()
        assertEquals(RagDocumentStatus.EMBEDDING, stored.status)
        assertEquals(null, stored.errorMessage)
    }

    @Test
    fun markdownPreservesHeadingPathAndDeterministicOrder() {
        val bytes = "# Guide\n\nIntro\n\n## Setup\n\nInstall package".toByteArray()
        val attachment = insertAttachment("guide.md", "text/markdown", bytes)

        val result = pipeline.parse(attachment) as SessionAttachmentParsingPipeline.Result.Prepared

        assertEquals(result.chunks.indices.toList(), result.chunks.map { it.ordinal })
        assertTrue(result.chunks.any { it.headingPath.contains("Guide") })
        assertTrue(result.chunks.any { it.headingPath.contains("Setup") })
    }

    @Test
    fun docxPreservesHeadingParagraphAndTableContent() {
        val document = """
            <?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Guide</w:t></w:r></w:p>
                <w:p><w:r><w:t>Intro paragraph</w:t></w:r></w:p>
                <w:tbl><w:tr><w:tc><w:p><w:r><w:t>Name</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Value</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
              </w:body>
            </w:document>
        """.trimIndent()
        val styles = """
            <?xml version="1.0" encoding="UTF-8"?>
            <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/></w:style>
            </w:styles>
        """.trimIndent()
        val attachment = insertAttachment("office.docx", DOCX_MIME, zip(document, styles))

        val result = pipeline.parse(attachment) as SessionAttachmentParsingPipeline.Result.Prepared

        assertTrue(result.chunks.any { it.text.contains("Guide") })
        assertTrue(result.chunks.any { it.text.contains("Intro paragraph") })
        assertTrue(result.chunks.any { it.text.contains("Name") && it.text.contains("Value") })
    }

    @Test
    fun oneAttachmentFailureIsPersistedWithoutChangingAnotherAttachment() {
        val failed = insertAttachment("missing.txt", "text/plain", "missing".toByteArray())
        File(failed.privatePath).delete()
        val healthy = insertAttachment("healthy.txt", "text/plain", "healthy content".toByteArray(), HASH_B)

        val failedResult = pipeline.parse(failed)

        assertTrue(failedResult is SessionAttachmentParsingPipeline.Result.Failed)
        val failedStored = database.listSessionAttachments(SESSION).first { it.id == failed.id }
        val healthyStored = database.listSessionAttachments(SESSION).first { it.id == healthy.id }
        assertEquals(RagDocumentStatus.FAILED, failedStored.status)
        assertTrue(failedStored.errorMessage!!.contains("missing"))
        assertEquals(RagDocumentStatus.QUEUED, healthyStored.status)
    }

    @Test
    fun rejectsUnsupportedFormatAndPersistsFailure() {
        val attachment = insertAttachment("document.rtf", "application/rtf", "{\\rtf1}".toByteArray())

        val result = pipeline.parse(attachment)

        assertTrue(result is SessionAttachmentParsingPipeline.Result.Failed)
        val stored = database.listSessionAttachments(SESSION).single()
        assertEquals(RagDocumentStatus.FAILED, stored.status)
        assertTrue(stored.errorMessage!!.contains("No indexing parser"))
    }

    @Test
    fun imageAttachmentUsesOcrAndAdvancesToEmbedding() {
        ocrEngine.imageBlocks = listOf(
            LayoutBlock(
                type = LayoutBlockType.OCR_TEXT,
                text = "recognized image text",
                readingOrder = 0
            )
        )
        val attachment = insertAttachment("scan.webp", "image/webp", byteArrayOf(1, 2, 3))

        val result = pipeline.parse(attachment) as SessionAttachmentParsingPipeline.Result.Prepared

        assertEquals(listOf(File(attachment.privatePath).canonicalPath), ocrEngine.imageFiles)
        assertTrue(result.chunks.any { it.text.contains("recognized image text") })
        assertEquals(RagDocumentStatus.EMBEDDING, database.listSessionAttachments(SESSION).single().status)
    }

    @Test
    fun imageOcrFailureIsPersisted() {
        ocrEngine.imageFailure = IllegalStateException("OCR memory budget exceeded")
        val attachment = insertAttachment("scan.jpg", "image/jpeg", byteArrayOf(4, 5, 6))

        val result = pipeline.parse(attachment)

        assertTrue(result is SessionAttachmentParsingPipeline.Result.Failed)
        val stored = database.listSessionAttachments(SESSION).single()
        assertEquals(RagDocumentStatus.FAILED, stored.status)
        assertTrue(stored.errorMessage!!.contains("memory budget"))
    }

    @Test
    fun scannedPdfPageUsesOcrAndPreservesTextPage() {
        ocrEngine.pdfBlocks = listOf(
            LayoutBlock(
                type = LayoutBlockType.OCR_TEXT,
                text = "recognized scanned page",
                readingOrder = 0,
                pageNumber = 2
            )
        )
        val attachment = insertAttachment(
            "mixed.pdf",
            "application/pdf",
            mixedPdf()
        )

        val result = pipeline.parse(attachment) as SessionAttachmentParsingPipeline.Result.Prepared

        assertEquals(
            listOf(File(attachment.privatePath).canonicalPath to listOf(2)),
            ocrEngine.pdfRequests
        )
        assertTrue(result.chunks.any { it.text.contains("text page") && it.startPage == 1 })
        assertTrue(result.chunks.any { it.text.contains("recognized scanned page") && it.startPage == 2 })
        assertEquals(RagDocumentStatus.EMBEDDING, database.listSessionAttachments(SESSION).single().status)
    }

    @Test
    fun scannedPdfOcrFailureIsPersisted() {
        ocrEngine.pdfFailure = IllegalStateException("PDF OCR time budget exceeded")
        val attachment = insertAttachment("scan.pdf", "application/pdf", blankPdf())

        val result = pipeline.parse(attachment)

        assertTrue(result is SessionAttachmentParsingPipeline.Result.Failed)
        val stored = database.listSessionAttachments(SESSION).single()
        assertEquals(RagDocumentStatus.FAILED, stored.status)
        assertTrue(stored.errorMessage!!.contains("time budget"))
        assertEquals(
            listOf(File(attachment.privatePath).canonicalPath to listOf(1)),
            ocrEngine.pdfRequests
        )
    }

    @Test
    fun parserFactorySupportsTextMarkdownDocxAndPdf() {
        assertTrue(DocumentParserFactory.create("a.txt", null) is TextDocumentParser)
        assertTrue(DocumentParserFactory.create("a.md", null) is MarkdownDocumentParser)
        assertTrue(DocumentParserFactory.create("a.docx", null) is DocxDocumentParser)
        assertTrue(DocumentParserFactory.create("a.pdf", null) is PdfDocumentParser)
        assertTrue(DocumentParserFactory.create("unknown", "application/pdf") is PdfDocumentParser)
    }

    private class FakeDocumentOcrEngine : DocumentOcrEngine {
        var imageBlocks: List<LayoutBlock> = emptyList()
        var pdfBlocks: List<LayoutBlock> = emptyList()
        var imageFailure: Throwable? = null
        var pdfFailure: Throwable? = null
        val imageFiles = mutableListOf<String>()
        val pdfRequests = mutableListOf<Pair<String, List<Int>>>()

        override fun recognizeImage(file: File): List<LayoutBlock> {
            imageFiles += file.canonicalPath
            imageFailure?.let { throw it }
            return imageBlocks
        }

        override fun recognizePdfPages(file: File, pageNumbers: List<Int>): List<LayoutBlock> {
            pdfRequests += file.canonicalPath to pageNumbers.toList()
            pdfFailure?.let { throw it }
            return pdfBlocks
        }
    }

    private fun insertAttachment(
        name: String,
        mime: String,
        bytes: ByteArray,
        hash: String = HASH_A
    ): SessionAttachment {
        val file = File(root, "$hash-${name.substringAfterLast('.')}").apply { writeBytes(bytes) }
        val value = SessionAttachment(
            sessionId = SESSION,
            sourceUri = "content://attachments/$name",
            displayName = name,
            mimeType = mime,
            sizeBytes = bytes.size.toLong(),
            sha256 = hash,
            privatePath = file.canonicalPath,
            parserVersion = 1,
            status = RagDocumentStatus.QUEUED,
            createdAt = 100L,
            updatedAt = 100L
        )
        return value.copy(id = database.insertSessionAttachment(value))
    }

    private fun mixedPdf(): ByteArray {
        val output = ByteArrayOutputStream()
        PDDocument().use { document ->
            val textPage = PDPage()
            document.addPage(textPage)
            PDPageContentStream(document, textPage).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font.HELVETICA, 12f)
                stream.newLineAtOffset(72f, 720f)
                stream.showText("text page")
                stream.endText()
            }
            document.addPage(PDPage())
            document.save(output)
        }
        return output.toByteArray()
    }

    private fun blankPdf(): ByteArray {
        val output = ByteArrayOutputStream()
        PDDocument().use { document ->
            document.addPage(PDPage())
            document.save(output)
        }
        return output.toByteArray()
    }

    private fun zip(document: String, styles: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            mapOf("word/document.xml" to document, "word/styles.xml" to styles).forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    companion object {
        private const val SESSION = "session-step14"
        private const val HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}
