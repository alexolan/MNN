package com.alibaba.mnnllm.android.rag

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.InputStream

/**
 * Extracts page-scoped text from PDFs that contain a usable text layer.
 * Scanned or text-empty pages are reported for the OCR stage instead of being
 * silently treated as successfully parsed content.
 */
class PdfDocumentParser(
    private val limits: PdfParserLimits = PdfParserLimits(),
    private val nowNanos: () -> Long = System::nanoTime
) : DocumentParser {

    override fun parse(input: InputStream): List<LayoutBlock> {
        val startedAt = nowNanos()
        val bytes = input.readLimited(limits.maxDocumentBytes)
        require(bytes.startsWith(PDF_HEADER)) { "PDF header is missing" }

        PDDocument.load(bytes, "").use { document ->
            if (document.isEncrypted) {
                require(document.currentAccessPermission.canExtractContent()) {
                    "PDF prevents text extraction; remove its password or copy restrictions first"
                }
            }
            val pageCount = document.numberOfPages
            require(pageCount in 1..limits.maxPages) {
                "PDF page count exceeds the configured limit"
            }

            val blocks = mutableListOf<LayoutBlock>()
            val pagesRequiringOcr = mutableListOf<Int>()
            val stripper = PDFTextStripper().apply {
                sortByPosition = true
                addMoreFormatting = false
            }

            for (pageNumber in 1..pageCount) {
                checkTimeBudget(startedAt)
                stripper.startPage = pageNumber
                stripper.endPage = pageNumber
                val text = normalizeText(stripper.getText(document))
                if (text.isBlank()) {
                    pagesRequiringOcr += pageNumber
                    continue
                }
                require(text.length <= limits.maxCharactersPerPage) {
                    "PDF page text exceeds the configured character limit"
                }
                blocks += LayoutBlock(
                    type = LayoutBlockType.PARAGRAPH,
                    text = text,
                    pageNumber = pageNumber,
                    readingOrder = blocks.size
                )
            }

            checkTimeBudget(startedAt)
            if (pagesRequiringOcr.isNotEmpty()) {
                throw PdfOcrRequiredException(
                    pageNumbers = pagesRequiringOcr,
                    extractedBlocks = blocks.toList()
                )
            }
            return blocks
        }
    }

    private fun checkTimeBudget(startedAt: Long) {
        val elapsed = nowNanos() - startedAt
        require(elapsed >= 0L && elapsed <= limits.maxProcessingNanos) {
            "PDF parsing exceeded the configured time limit"
        }
    }

    companion object {
        private val PDF_HEADER = byteArrayOf(
            '%'.code.toByte(),
            'P'.code.toByte(),
            'D'.code.toByte(),
            'F'.code.toByte(),
            '-'.code.toByte()
        )
    }
}

data class PdfParserLimits(
    val maxDocumentBytes: Long = 64L * 1024L * 1024L,
    val maxPages: Int = 200,
    val maxCharactersPerPage: Int = 1_000_000,
    val maxProcessingNanos: Long = 120L * 1_000_000_000L
) {
    init {
        require(maxDocumentBytes > 0L)
        require(maxPages > 0)
        require(maxCharactersPerPage > 0)
        require(maxProcessingNanos > 0L)
    }
}

class PdfOcrRequiredException(
    val pageNumbers: List<Int>,
    val extractedBlocks: List<LayoutBlock> = emptyList()
) : IllegalStateException(
    "PDF requires OCR for page(s): ${pageNumbers.joinToString(",")}"
) {
    init {
        require(pageNumbers.isNotEmpty())
        require(pageNumbers.all { it > 0 })
        require(extractedBlocks.all { it.pageNumber != null && it.pageNumber !in pageNumbers }) {
            "Extracted text blocks must not overlap pages requiring OCR"
        }
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    return prefix.indices.all { index -> this[index] == prefix[index] }
}
