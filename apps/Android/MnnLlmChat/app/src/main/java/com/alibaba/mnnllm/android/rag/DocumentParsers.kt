package com.alibaba.mnnllm.android.rag

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

interface DocumentParser {
    fun parse(input: InputStream): List<LayoutBlock>
}

/**
 * Parses plain text without loading unbounded input into memory.
 * UTF-8 and UTF-16 BOMs are recognized explicitly.
 */
class TextDocumentParser(
    private val limits: ParserLimits = ParserLimits()
) : DocumentParser {

    override fun parse(input: InputStream): List<LayoutBlock> {
        val bytes = input.readLimited(limits.maxDocumentBytes)
        val text = decodeText(bytes)
        return paragraphs(normalizeText(text))
    }

    internal fun decodeText(bytes: ByteArray): String {
        val charset: Charset
        val offset: Int
        when {
            bytes.startsWith(UTF8_BOM) -> {
                charset = Charsets.UTF_8
                offset = UTF8_BOM.size
            }
            bytes.startsWith(UTF16_LE_BOM) -> {
                charset = Charsets.UTF_16LE
                offset = UTF16_LE_BOM.size
            }
            bytes.startsWith(UTF16_BE_BOM) -> {
                charset = Charsets.UTF_16BE
                offset = UTF16_BE_BOM.size
            }
            looksLikeUtf16(bytes, littleEndian = true) -> {
                charset = Charsets.UTF_16LE
                offset = 0
            }
            looksLikeUtf16(bytes, littleEndian = false) -> {
                charset = Charsets.UTF_16BE
                offset = 0
            }
            else -> {
                charset = Charsets.UTF_8
                offset = 0
            }
        }
        return charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
            .toString()
    }

    private fun paragraphs(text: String): List<LayoutBlock> {
        if (text.isBlank()) return emptyList()
        val blocks = mutableListOf<LayoutBlock>()
        val paragraph = StringBuilder()

        fun flush() {
            val value = paragraph.toString().trim()
            if (value.isNotEmpty()) {
                blocks += LayoutBlock(
                    type = LayoutBlockType.PARAGRAPH,
                    text = value,
                    readingOrder = blocks.size
                )
            }
            paragraph.setLength(0)
        }

        text.lineSequence().forEach { line ->
            if (line.isBlank()) {
                flush()
            } else {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line.trimEnd())
            }
        }
        flush()
        return blocks
    }

    private fun looksLikeUtf16(bytes: ByteArray, littleEndian: Boolean): Boolean {
        if (bytes.size < 4) return false
        val sample = minOf(bytes.size, 512)
        var zeroes = 0
        var inspected = 0
        var index = if (littleEndian) 1 else 0
        while (index < sample) {
            if (bytes[index].toInt() == 0) zeroes++
            inspected++
            index += 2
        }
        return inspected > 0 && zeroes * 100 / inspected >= 60
    }

    companion object {
        private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
    }
}

/**
 * Lightweight Markdown structure parser for indexing. It intentionally avoids
 * coupling the index format to Markwon spans or a UI-oriented AST.
 */
class MarkdownDocumentParser(
    private val limits: ParserLimits = ParserLimits()
) : DocumentParser {

    override fun parse(input: InputStream): List<LayoutBlock> {
        val text = TextDocumentParser(limits).decodeText(input.readLimited(limits.maxDocumentBytes))
        return parseText(normalizeText(text))
    }

    internal fun parseText(text: String): List<LayoutBlock> {
        val blocks = mutableListOf<LayoutBlock>()
        val headingStack = mutableListOf<String>()
        val paragraph = StringBuilder()
        val code = StringBuilder()
        var fence: String? = null

        fun add(type: LayoutBlockType, value: String) {
            val cleaned = value.trim()
            if (cleaned.isNotEmpty()) {
                blocks += LayoutBlock(
                    type = type,
                    text = cleaned,
                    headingPath = headingStack.toList(),
                    readingOrder = blocks.size
                )
            }
        }

        fun flushParagraph() {
            add(LayoutBlockType.PARAGRAPH, paragraph.toString())
            paragraph.setLength(0)
        }

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            val trimmed = line.trimStart()
            val activeFence = fence
            if (activeFence != null) {
                if (trimmed.startsWith(activeFence)) {
                    add(LayoutBlockType.CODE_BLOCK, code.toString())
                    code.setLength(0)
                    fence = null
                } else {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(line)
                }
                return@forEach
            }

            val openingFence = when {
                trimmed.startsWith("```") -> "```"
                trimmed.startsWith("~~~") -> "~~~"
                else -> null
            }
            if (openingFence != null) {
                flushParagraph()
                fence = openingFence
                return@forEach
            }

            val heading = HEADING.matchEntire(trimmed)
            if (heading != null) {
                flushParagraph()
                val level = heading.groupValues[1].length
                val title = heading.groupValues[2].trim()
                if (title.isNotEmpty()) {
                    while (headingStack.size >= level) headingStack.removeAt(headingStack.lastIndex)
                    while (headingStack.size < level - 1) headingStack += ""
                    headingStack += title
                    add(LayoutBlockType.TITLE, title)
                }
                return@forEach
            }

            when {
                trimmed.isBlank() -> flushParagraph()
                LIST_ITEM.matches(trimmed) -> {
                    flushParagraph()
                    add(LayoutBlockType.LIST_ITEM, LIST_ITEM.matchEntire(trimmed)!!.groupValues[1])
                }
                QUOTE.matches(trimmed) -> {
                    flushParagraph()
                    add(LayoutBlockType.QUOTE, QUOTE.matchEntire(trimmed)!!.groupValues[1])
                }
                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append('\n')
                    paragraph.append(line)
                }
            }
        }

        if (fence != null) add(LayoutBlockType.CODE_BLOCK, code.toString())
        flushParagraph()
        return blocks
    }

    companion object {
        private val HEADING = Regex("^(#{1,6})\\s+(.+?)\\s*#*\\s*$")
        private val LIST_ITEM = Regex("^(?:[-+*]|\\d+[.)])\\s+(.+)$")
        private val QUOTE = Regex("^>\\s?(.*)$")
    }
}

data class ParserLimits(
    val maxDocumentBytes: Long = 64L * 1024L * 1024L,
    val maxZipEntries: Int = 4_096,
    val maxZipEntryBytes: Long = 32L * 1024L * 1024L,
    val maxZipTotalBytes: Long = 128L * 1024L * 1024L,
    val maxCompressionRatio: Long = 200L
) {
    init {
        require(maxDocumentBytes > 0)
        require(maxZipEntries > 0)
        require(maxZipEntryBytes > 0)
        require(maxZipTotalBytes >= maxZipEntryBytes)
        require(maxCompressionRatio > 0)
    }
}

internal fun normalizeText(value: String): String {
    return value
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .filter { character ->
            character == '\n' || character == '\t' || character.code >= 0x20
        }
        .trim()
}

internal fun InputStream.readLimited(maxBytes: Long): ByteArray {
    require(maxBytes > 0)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (count == 0) continue
        total = Math.addExact(total, count.toLong())
        require(total <= maxBytes) { "Document exceeds the configured size limit" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    return prefix.indices.all { index -> this[index] == prefix[index] }
}
