package com.alibaba.mnnllm.android.rag

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Minimal, bounded OOXML parser for DOCX indexing.
 * Extracts paragraphs, headings, list items, and tables without Apache POI.
 */
class DocxDocumentParser(
    private val limits: ParserLimits = ParserLimits()
) : DocumentParser {

    override fun parse(input: InputStream): List<LayoutBlock> {
        val parts = readParts(input)
        val documentXml = parts[DOCUMENT_XML]
            ?: throw IllegalArgumentException("DOCX is missing word/document.xml")
        val styles = parts[STYLES_XML]?.let(::parseHeadingStyles).orEmpty()
        return parseDocument(documentXml, styles)
    }

    private fun readParts(input: InputStream): Map<String, ByteArray> {
        val wanted = setOf(DOCUMENT_XML, STYLES_XML, NUMBERING_XML)
        val parts = mutableMapOf<String, ByteArray>()
        var entryCount = 0
        var totalBytes = 0L

        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                require(entryCount <= limits.maxZipEntries) { "DOCX contains too many ZIP entries" }
                validateEntry(entry)

                if (!entry.isDirectory && entry.name in wanted) {
                    val bytes = readEntry(zip, entry)
                    totalBytes = Math.addExact(totalBytes, bytes.size.toLong())
                    require(totalBytes <= limits.maxZipTotalBytes) {
                        "DOCX exceeds the total decompressed size limit"
                    }
                    parts[entry.name] = bytes
                }
                zip.closeEntry()
            }
        }
        return parts
    }

    private fun validateEntry(entry: ZipEntry) {
        val name = entry.name.replace('\\', '/')
        require(!name.startsWith('/')) { "DOCX contains an absolute ZIP path" }
        require(name.split('/').none { it == ".." }) { "DOCX contains a path traversal entry" }
        require(entry.size <= limits.maxZipEntryBytes || entry.size < 0) {
            "DOCX ZIP entry exceeds the size limit"
        }
        if (entry.size > 0 && entry.compressedSize > 0) {
            require(entry.size / entry.compressedSize <= limits.maxCompressionRatio) {
                "DOCX ZIP entry exceeds the compression ratio limit"
            }
        }
    }

    private fun readEntry(zip: ZipInputStream, entry: ZipEntry): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total = Math.addExact(total, count.toLong())
            require(total <= limits.maxZipEntryBytes) { "DOCX ZIP entry exceeds the size limit" }
            output.write(buffer, 0, count)
        }
        if (entry.compressedSize > 0) {
            require(total / entry.compressedSize.coerceAtLeast(1) <= limits.maxCompressionRatio) {
                "DOCX ZIP entry exceeds the compression ratio limit"
            }
        }
        return output.toByteArray()
    }

    private fun parseHeadingStyles(xml: ByteArray): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        val parser = newParser(xml)
        var styleId: String? = null
        var styleName: String? = null

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name.localName()) {
                    "style" -> {
                        styleId = parser.attribute("styleId")
                        styleName = null
                    }
                    "name" -> styleName = parser.attribute("val")
                }
            } else if (parser.eventType == XmlPullParser.END_TAG && parser.name.localName() == "style") {
                val id = styleId
                val level = styleName?.let(::headingLevel)
                if (id != null && level != null) result[id] = level
                styleId = null
                styleName = null
            }
            parser.next()
        }
        return result
    }

    private fun parseDocument(xml: ByteArray, headingStyles: Map<String, Int>): List<LayoutBlock> {
        val parser = newParser(xml)
        val blocks = mutableListOf<LayoutBlock>()
        val headings = mutableListOf<String>()
        var paragraphText: StringBuilder? = null
        var paragraphStyle: String? = null
        var numbered = false
        var tableDepth = 0
        var row: MutableList<String>? = null
        var cell: StringBuilder? = null

        fun addBlock(type: LayoutBlockType, text: String, headingPath: List<String> = headings.toList()) {
            val clean = normalizeText(text)
            if (clean.isNotEmpty()) {
                blocks += LayoutBlock(
                    type = type,
                    text = clean,
                    headingPath = headingPath.filter(String::isNotEmpty),
                    readingOrder = blocks.size
                )
            }
        }

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name.localName()) {
                    "tbl" -> tableDepth++
                    "tr" -> if (tableDepth > 0) row = mutableListOf()
                    "tc" -> if (tableDepth > 0) cell = StringBuilder()
                    "p" -> {
                        paragraphText = StringBuilder()
                        paragraphStyle = null
                        numbered = false
                    }
                    "pStyle" -> paragraphStyle = parser.attribute("val")
                    "numPr" -> numbered = true
                    "tab" -> paragraphText?.append('\t')
                    "br", "cr" -> paragraphText?.append('\n')
                    "t" -> {
                        val value = parser.nextText()
                        paragraphText?.append(value)
                    }
                }

                XmlPullParser.END_TAG -> when (parser.name.localName()) {
                    "p" -> {
                        val text = paragraphText?.toString().orEmpty().trim()
                        if (text.isNotEmpty()) {
                            if (tableDepth > 0) {
                                val target = cell
                                if (target != null) {
                                    if (target.isNotEmpty()) target.append('\n')
                                    target.append(text)
                                }
                            } else {
                                val level = paragraphStyle?.let { headingStyles[it] ?: headingLevel(it) }
                                if (level != null) {
                                    while (headings.size >= level) headings.removeAt(headings.lastIndex)
                                    while (headings.size < level - 1) headings += ""
                                    headings += text
                                    addBlock(LayoutBlockType.TITLE, text)
                                } else {
                                    addBlock(
                                        if (numbered) LayoutBlockType.LIST_ITEM else LayoutBlockType.PARAGRAPH,
                                        text
                                    )
                                }
                            }
                        }
                        paragraphText = null
                    }
                    "tc" -> {
                        if (tableDepth > 0) row?.add(cell?.toString()?.trim().orEmpty())
                        cell = null
                    }
                    "tr" -> {
                        val values = row.orEmpty()
                        if (tableDepth > 0 && values.any(String::isNotEmpty)) {
                            addBlock(LayoutBlockType.TABLE, values.joinToString("\t"))
                        }
                        row = null
                    }
                    "tbl" -> tableDepth = (tableDepth - 1).coerceAtLeast(0)
                }
            }
            parser.next()
        }
        return blocks
    }

    private fun newParser(xml: ByteArray): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
        }
        return factory.newPullParser().apply {
            try {
                setFeature("http://xmlpull.org/v1/doc/features.html#process-docdecl", false)
            } catch (_: Exception) {
                // Parser implementations may not expose this optional feature.
            }
            setInput(ByteArrayInputStream(xml), "UTF-8")
        }
    }

    private fun headingLevel(value: String): Int? {
        val match = Regex("(?i)^(?:heading|title)[ _-]?([1-6])$").matchEntire(value.trim())
        return match?.groupValues?.get(1)?.toInt()
    }

    private fun XmlPullParser.attribute(localName: String): String? {
        for (index in 0 until attributeCount) {
            if (getAttributeName(index).localName() == localName) return getAttributeValue(index)
        }
        return null
    }

    private fun String.localName(): String = substringAfter(':')

    companion object {
        private const val DOCUMENT_XML = "word/document.xml"
        private const val STYLES_XML = "word/styles.xml"
        private const val NUMBERING_XML = "word/numbering.xml"
    }
}
