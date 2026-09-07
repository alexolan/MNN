package com.alibaba.mnnllm.android.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class DocumentParsersTest {

    @Test
    fun textParserRecognizesUtf8BomAndParagraphs() {
        val input = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "first line\nsecond line\n\nthird paragraph".toByteArray(Charsets.UTF_8)

        val blocks = TextDocumentParser().parse(ByteArrayInputStream(input))

        assertEquals(2, blocks.size)
        assertEquals(LayoutBlockType.PARAGRAPH, blocks[0].type)
        assertEquals("first line\nsecond line", blocks[0].text)
        assertEquals("third paragraph", blocks[1].text)
        assertEquals(0, blocks[0].readingOrder)
        assertEquals(1, blocks[1].readingOrder)
    }

    @Test
    fun textParserRecognizesUtf16LittleEndianBom() {
        val input = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "hello\n\nworld".toByteArray(Charsets.UTF_16LE)

        val blocks = TextDocumentParser().parse(ByteArrayInputStream(input))

        assertEquals(listOf("hello", "world"), blocks.map(LayoutBlock::text))
    }

    @Test
    fun textParserRejectsDocumentsAboveLimit() {
        val parser = TextDocumentParser(ParserLimits(maxDocumentBytes = 4))

        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(ByteArrayInputStream("12345".toByteArray()))
        }
    }

    @Test
    fun markdownParserPreservesStructureAndHeadingPath() {
        val markdown = """
            # Guide

            Intro paragraph.

            ## Setup
            - Install package
            1. Run command
            > Important note

            ```kotlin
            println("ok")
            ```
        """.trimIndent()

        val blocks = MarkdownDocumentParser().parse(
            ByteArrayInputStream(markdown.toByteArray(Charsets.UTF_8))
        )

        assertEquals(
            listOf(
                LayoutBlockType.TITLE,
                LayoutBlockType.PARAGRAPH,
                LayoutBlockType.TITLE,
                LayoutBlockType.LIST_ITEM,
                LayoutBlockType.LIST_ITEM,
                LayoutBlockType.QUOTE,
                LayoutBlockType.CODE_BLOCK
            ),
            blocks.map(LayoutBlock::type)
        )
        assertEquals(listOf("Guide"), blocks[1].headingPath)
        assertEquals(listOf("Guide", "Setup"), blocks[3].headingPath)
        assertEquals("Install package", blocks[3].text)
        assertEquals("Run command", blocks[4].text)
        assertEquals("Important note", blocks[5].text)
        assertEquals("println(\"ok\")", blocks[6].text)
        assertEquals(blocks.indices.toList(), blocks.map(LayoutBlock::readingOrder))
    }

    @Test
    fun markdownParserFlushesUnclosedFenceAsCodeBlock() {
        val markdown = """
            # Notes
            ```text
            unfinished code
        """.trimIndent()

        val blocks = MarkdownDocumentParser().parse(
            ByteArrayInputStream(markdown.toByteArray(Charsets.UTF_8))
        )

        assertEquals(2, blocks.size)
        assertEquals(LayoutBlockType.CODE_BLOCK, blocks[1].type)
        assertEquals("unfinished code", blocks[1].text)
        assertEquals(listOf("Notes"), blocks[1].headingPath)
    }
}
