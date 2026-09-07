package com.alibaba.mnnllm.android.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkingTest {

    private val wordCounter = TokenCounter { text ->
        text.trim().takeIf(String::isNotEmpty)?.split(Regex("\\s+"))?.size ?: 0
    }

    @Test
    fun producesDeterministicOrderedChunksWithinBudget() {
        val chunker = DeterministicChunker(
            wordCounter,
            ChunkingConfig(maxTokens = 4, includeHeadingPrefix = false)
        )
        val blocks = listOf(
            LayoutBlock(
                type = LayoutBlockType.PARAGRAPH,
                text = "third block",
                readingOrder = 2
            ),
            LayoutBlock(
                type = LayoutBlockType.PARAGRAPH,
                text = "first block",
                readingOrder = 0
            ),
            LayoutBlock(
                type = LayoutBlockType.PARAGRAPH,
                text = "second block",
                readingOrder = 1
            )
        )

        val first = chunker.chunk(11L, blocks)
        val second = chunker.chunk(11L, blocks)

        assertEquals(first, second)
        assertEquals(listOf(0, 1), first.map(RagChunk::ordinal))
        assertEquals("first block\n\nsecond block\n\nthird block", first.joinToString("\n\n") { it.text })
        assertTrue(first.all { it.tokenCount in 1..6 })
    }

    @Test
    fun splitsLargeBlockWithoutBreakingSurrogatePair() {
        val codePointCounter = TokenCounter { text -> text.codePointCount(0, text.length) }
        val chunker = DeterministicChunker(
            codePointCounter,
            ChunkingConfig(maxTokens = 3, includeHeadingPrefix = false)
        )

        val chunks = chunker.chunk(
            12L,
            listOf(
                LayoutBlock(
                    type = LayoutBlockType.PARAGRAPH,
                    text = "A😀BC😀D",
                    readingOrder = 0
                )
            )
        )

        assertEquals("A😀BC😀D", chunks.joinToString("") { it.text })
        assertTrue(chunks.all { it.tokenCount <= 3 })
        assertTrue(chunks.none { chunk ->
            chunk.text.firstOrNull()?.let(Character::isLowSurrogate) == true ||
                chunk.text.lastOrNull()?.let(Character::isHighSurrogate) == true
        })
    }

    @Test
    fun flushesWhenHeadingPathChangesAndPrefixesHeading() {
        val chunker = DeterministicChunker(
            wordCounter,
            ChunkingConfig(maxTokens = 10)
        )
        val blocks = listOf(
            LayoutBlock(
                type = LayoutBlockType.PARAGRAPH,
                text = "alpha text",
                headingPath = listOf("Guide", "Alpha"),
                readingOrder = 0
            ),
            LayoutBlock(
                type = LayoutBlockType.PARAGRAPH,
                text = "beta text",
                headingPath = listOf("Guide", "Beta"),
                readingOrder = 1
            )
        )

        val chunks = chunker.chunk(13L, blocks)

        assertEquals(2, chunks.size)
        assertEquals(listOf("Guide", "Alpha"), chunks[0].headingPath)
        assertEquals("Guide > Alpha\nalpha text", chunks[0].text)
        assertEquals(listOf("Guide", "Beta"), chunks[1].headingPath)
        assertEquals("Guide > Beta\nbeta text", chunks[1].text)
    }

    @Test
    fun inheritsMinimumAndMaximumPageNumbers() {
        val chunker = DeterministicChunker(
            wordCounter,
            ChunkingConfig(maxTokens = 20, includeHeadingPrefix = false)
        )
        val blocks = listOf(
            LayoutBlock(LayoutBlockType.PARAGRAPH, "page three", pageNumber = 3, readingOrder = 0),
            LayoutBlock(LayoutBlockType.PARAGRAPH, "page one", pageNumber = 1, readingOrder = 1),
            LayoutBlock(LayoutBlockType.PARAGRAPH, "page two", pageNumber = 2, readingOrder = 2)
        )

        val chunk = chunker.chunk(14L, blocks).single()

        assertEquals(1, chunk.startPage)
        assertEquals(3, chunk.endPage)
    }

    @Test
    fun addsBoundedOverlapWithoutExceedingMaximum() {
        val chunker = DeterministicChunker(
            wordCounter,
            ChunkingConfig(maxTokens = 5, overlapTokens = 2, includeHeadingPrefix = false)
        )
        val blocks = listOf(
            LayoutBlock(LayoutBlockType.PARAGRAPH, "one two three four five", readingOrder = 0),
            LayoutBlock(LayoutBlockType.PARAGRAPH, "six seven", readingOrder = 1)
        )

        val chunks = chunker.chunk(15L, blocks)

        assertEquals(2, chunks.size)
        assertEquals("one two three four five", chunks[0].text)
        assertEquals("four five\nsix seven", chunks[1].text)
        assertTrue(chunks.all { it.tokenCount <= 5 })
    }

    @Test
    fun normalizerProducesStableEmbeddingInput() {
        val input = "  alpha\t beta \r\n \r\n\r\n gamma\u0000  "

        assertEquals(
            "alpha beta\n\ngamma",
            EmbeddingInputNormalizer.normalize(input)
        )
    }
}
