package com.alibaba.mnnllm.android.rag

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
class PdfDocumentParserTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun initializePdfBox() {
            PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext<Context>())
        }
    }

    @Test
    fun extractsTextPerPageAndPreservesPageMapping() {
        val bytes = createPdf(listOf("First page text", "Second page text"))

        val blocks = PdfDocumentParser().parse(ByteArrayInputStream(bytes))

        assertEquals(2, blocks.size)
        assertTrue(blocks[0].text.contains("First page text"))
        assertTrue(blocks[1].text.contains("Second page text"))
        assertEquals(listOf(1, 2), blocks.map { it.pageNumber })
        assertEquals(listOf(0, 1), blocks.map { it.readingOrder })
    }

    @Test
    fun blankPdfRoutesAllPagesToOcr() {
        val error = assertThrows(PdfOcrRequiredException::class.java) {
            PdfDocumentParser().parse(ByteArrayInputStream(createPdf(listOf(null, null))))
        }

        assertEquals(listOf(1, 2), error.pageNumbers)
        assertTrue(error.extractedBlocks.isEmpty())
    }

    @Test
    fun mixedPdfPreservesTextBlocksAndRoutesOnlyBlankPagesToOcr() {
        val error = assertThrows(PdfOcrRequiredException::class.java) {
            PdfDocumentParser().parse(
                ByteArrayInputStream(createPdf(listOf("Text layer", null, "Final text")))
            )
        }

        assertEquals(listOf(2), error.pageNumbers)
        assertEquals(listOf(1, 3), error.extractedBlocks.map { it.pageNumber })
        assertTrue(error.extractedBlocks.first().text.contains("Text layer"))
        assertTrue(error.extractedBlocks.last().text.contains("Final text"))
    }

    @Test
    fun rejectsPageCountAboveConfiguredLimit() {
        val parser = PdfDocumentParser(PdfParserLimits(maxPages = 1))

        val error = assertThrows(IllegalArgumentException::class.java) {
            parser.parse(ByteArrayInputStream(createPdf(listOf("one", "two"))))
        }

        assertTrue(error.message.orEmpty().contains("page count"))
    }

    @Test
    fun rejectsMissingPdfHeader() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            PdfDocumentParser().parse(ByteArrayInputStream("not a pdf".toByteArray()))
        }

        assertTrue(error.message.orEmpty().contains("header"))
    }

    @Test
    fun rejectsDocumentAboveByteLimit() {
        val bytes = createPdf(listOf("bounded document"))
        val parser = PdfDocumentParser(PdfParserLimits(maxDocumentBytes = bytes.size.toLong() - 1L))

        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(ByteArrayInputStream(bytes))
        }
    }

    private fun createPdf(pageTexts: List<String?>): ByteArray {
        val output = ByteArrayOutputStream()
        PDDocument().use { document ->
            pageTexts.forEach { text ->
                val page = PDPage()
                document.addPage(page)
                if (text != null) {
                    PDPageContentStream(document, page).use { stream ->
                        stream.beginText()
                        stream.setFont(PDType1Font.HELVETICA, 12f)
                        stream.newLineAtOffset(72f, 720f)
                        stream.showText(text)
                        stream.endText()
                    }
                }
            }
            document.save(output)
        }
        return output.toByteArray()
    }
}
