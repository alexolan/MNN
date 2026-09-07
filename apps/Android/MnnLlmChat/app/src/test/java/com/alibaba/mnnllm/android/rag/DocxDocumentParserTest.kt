package com.alibaba.mnnllm.android.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class DocxDocumentParserTest {

    @Test
    fun parsesHeadingsParagraphsListsAndTables() {
        val styles = """
            <?xml version="1.0" encoding="UTF-8"?>
            <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/></w:style>
            </w:styles>
        """.trimIndent()
        val document = """
            <?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Guide</w:t></w:r></w:p>
                <w:p><w:r><w:t>Intro paragraph</w:t></w:r></w:p>
                <w:p><w:pPr><w:numPr><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>First item</w:t></w:r></w:p>
                <w:tbl><w:tr>
                  <w:tc><w:p><w:r><w:t>Name</w:t></w:r></w:p></w:tc>
                  <w:tc><w:p><w:r><w:t>Value</w:t></w:r></w:p></w:tc>
                </w:tr></w:tbl>
              </w:body>
            </w:document>
        """.trimIndent()

        val blocks = DocxDocumentParser().parse(docx(document, styles))

        assertEquals(
            listOf(
                LayoutBlockType.TITLE,
                LayoutBlockType.PARAGRAPH,
                LayoutBlockType.LIST_ITEM,
                LayoutBlockType.TABLE
            ),
            blocks.map(LayoutBlock::type)
        )
        assertEquals("Guide", blocks[0].text)
        assertEquals(listOf("Guide"), blocks[1].headingPath)
        assertEquals("First item", blocks[2].text)
        assertEquals("Name\tValue", blocks[3].text)
        assertEquals(blocks.indices.toList(), blocks.map(LayoutBlock::readingOrder))
    }

    @Test
    fun rejectsArchiveWithoutMainDocumentPart() {
        val bytes = zip(mapOf("word/styles.xml" to "<styles/>"))

        assertThrows(IllegalArgumentException::class.java) {
            DocxDocumentParser().parse(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun rejectsPathTraversalEntry() {
        val bytes = zip(
            linkedMapOf(
                "../word/document.xml" to "<document/>",
                "word/document.xml" to minimalDocument("safe")
            )
        )

        assertThrows(IllegalArgumentException::class.java) {
            DocxDocumentParser().parse(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun rejectsTooManyZipEntries() {
        val limits = ParserLimits(maxZipEntries = 1)
        val bytes = zip(
            linkedMapOf(
                "word/document.xml" to minimalDocument("one"),
                "word/styles.xml" to "<styles/>"
            )
        )

        assertThrows(IllegalArgumentException::class.java) {
            DocxDocumentParser(limits).parse(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun rejectsOversizedRequiredPart() {
        val limits = ParserLimits(
            maxZipEntryBytes = 64,
            maxZipTotalBytes = 128
        )
        val bytes = zip(mapOf("word/document.xml" to minimalDocument("x".repeat(256))))

        assertThrows(IllegalArgumentException::class.java) {
            DocxDocumentParser(limits).parse(ByteArrayInputStream(bytes))
        }
    }

    private fun docx(documentXml: String, stylesXml: String): ByteArrayInputStream {
        return ByteArrayInputStream(
            zip(
                linkedMapOf(
                    "word/document.xml" to documentXml,
                    "word/styles.xml" to stylesXml
                )
            )
        )
    }

    private fun minimalDocument(text: String): String {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body><w:p><w:r><w:t>$text</w:t></w:r></w:p></w:body>
            </w:document>
        """.trimIndent()
    }

    private fun zip(entries: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
