package com.alibaba.mnnllm.android.rag

/**
 * Counts model tokens without coupling chunking to a specific tokenizer.
 * The embedding model integration should provide the production implementation.
 */
fun interface TokenCounter {
    fun count(text: String): Int
}

data class ChunkingConfig(
    val maxTokens: Int,
    val overlapTokens: Int = 0,
    val headingSeparator: String = " > ",
    val includeHeadingPrefix: Boolean = true
) {
    init {
        require(maxTokens > 0) { "Maximum token count must be positive" }
        require(overlapTokens >= 0) { "Overlap token count must not be negative" }
        require(overlapTokens < maxTokens) { "Overlap must be smaller than the maximum token count" }
    }
}

/**
 * Deterministically transforms ordered layout blocks into token-bounded chunks.
 * Blocks are never reordered. Large blocks are split at paragraph, line, sentence,
 * whitespace, and finally code-point boundaries.
 */
class DeterministicChunker(
    private val tokenCounter: TokenCounter,
    private val config: ChunkingConfig
) {
    fun chunk(documentId: Long, blocks: List<LayoutBlock>): List<RagChunk> {
        require(documentId > 0) { "Document id must be positive" }
        val ordered = blocks
            .filter { it.text.isNotBlank() }
            .sortedWith(compareBy<LayoutBlock> { it.readingOrder }.thenBy { it.pageNumber ?: Int.MAX_VALUE })

        val chunks = mutableListOf<RagChunk>()
        var current = MutableChunk()

        fun flush() {
            val text = current.text()
            if (text.isBlank()) {
                current = MutableChunk()
                return
            }
            val normalized = EmbeddingInputNormalizer.normalize(text)
            val count = tokenCounter.count(normalized)
            require(count in 1..config.maxTokens) { "Chunk token count exceeds the configured budget" }
            chunks += RagChunk(
                documentId = documentId,
                ordinal = chunks.size,
                text = normalized,
                tokenCount = count,
                headingPath = current.headingPath,
                startPage = current.startPage,
                endPage = current.endPage
            )
            current = MutableChunk()
        }

        ordered.forEach { block ->
            val pieces = splitBlock(block)
            pieces.forEach { piece ->
                if (current.isNotEmpty() && current.headingPath != piece.headingPath) flush()
                val candidate = current.preview(piece)
                if (current.isNotEmpty() && tokenCounter.count(candidate) > config.maxTokens) flush()
                current.add(piece)
                if (tokenCounter.count(current.text()) >= config.maxTokens) flush()
            }
        }
        flush()
        return addOverlap(chunks)
    }

    private fun splitBlock(block: LayoutBlock): List<BlockPiece> {
        val headingPath = block.headingPath.filter(String::isNotBlank)
        val prefix = if (config.includeHeadingPrefix && headingPath.isNotEmpty()) {
            headingPath.joinToString(config.headingSeparator) + "\n"
        } else {
            ""
        }
        val normalized = EmbeddingInputNormalizer.normalize(block.text)
        if (normalized.isBlank()) return emptyList()
        if (tokenCounter.count(prefix + normalized) <= config.maxTokens) {
            return listOf(BlockPiece(normalized, headingPath, block.pageNumber))
        }

        val output = mutableListOf<BlockPiece>()
        var remaining = normalized
        while (remaining.isNotBlank()) {
            val cut = largestFittingPrefix(prefix, remaining)
            require(cut > 0) { "Unable to split text within the token budget" }
            val piece = remaining.substring(0, cut).trim()
            if (piece.isNotEmpty()) output += BlockPiece(piece, headingPath, block.pageNumber)
            remaining = remaining.substring(cut).trimStart()
        }
        return output
    }

    private fun largestFittingPrefix(prefix: String, text: String): Int {
        var low = 1
        var high = text.length
        var best = 0
        while (low <= high) {
            val middle = (low + high) ushr 1
            val safeMiddle = safeBoundary(text, middle)
            val candidate = text.substring(0, safeMiddle).trimEnd()
            if (candidate.isNotEmpty() && tokenCounter.count(prefix + candidate) <= config.maxTokens) {
                best = safeMiddle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        if (best == 0) return 0
        return preferredBoundary(text, best)
    }

    private fun preferredBoundary(text: String, maximum: Int): Int {
        val candidates = charArrayOf('\n', '。', '！', '？', '.', '!', '?', ';', '；', ' ', '\t')
        val floor = maximum / 2
        for (index in maximum - 1 downTo floor) {
            if (text[index] in candidates) return safeBoundary(text, index + 1)
        }
        return safeBoundary(text, maximum)
    }

    private fun safeBoundary(text: String, requested: Int): Int {
        var boundary = requested.coerceIn(1, text.length)
        if (boundary < text.length && Character.isLowSurrogate(text[boundary]) && Character.isHighSurrogate(text[boundary - 1])) {
            boundary--
        }
        return boundary.coerceAtLeast(1)
    }

    private fun addOverlap(chunks: List<RagChunk>): List<RagChunk> {
        if (config.overlapTokens == 0 || chunks.size < 2) return chunks
        return chunks.mapIndexed { index, chunk ->
            if (index == 0) return@mapIndexed chunk
            val previous = chunks[index - 1]
            val overlap = suffixWithinBudget(previous.text, config.overlapTokens)
            if (overlap.isBlank()) return@mapIndexed chunk
            val combined = EmbeddingInputNormalizer.normalize(overlap + "\n" + chunk.text)
            if (tokenCounter.count(combined) > config.maxTokens) chunk
            else chunk.copy(text = combined, tokenCount = tokenCounter.count(combined))
        }
    }

    private fun suffixWithinBudget(text: String, budget: Int): String {
        if (budget <= 0) return ""
        var low = 0
        var high = text.length
        var best = text.length
        while (low <= high) {
            val middle = (low + high) ushr 1
            val boundary = safeSuffixBoundary(text, middle)
            val candidate = text.substring(boundary).trimStart()
            if (tokenCounter.count(candidate) <= budget) {
                best = boundary
                high = middle - 1
            } else {
                low = middle + 1
            }
        }
        return text.substring(best).trim()
    }

    private fun safeSuffixBoundary(text: String, requested: Int): Int {
        var boundary = requested.coerceIn(0, text.length)
        if (boundary in 1 until text.length && Character.isLowSurrogate(text[boundary]) && Character.isHighSurrogate(text[boundary - 1])) {
            boundary++
        }
        return boundary.coerceAtMost(text.length)
    }

    private data class BlockPiece(
        val text: String,
        val headingPath: List<String>,
        val pageNumber: Int?
    )

    private inner class MutableChunk {
        private val parts = mutableListOf<String>()
        var headingPath: List<String> = emptyList()
            private set
        var startPage: Int? = null
            private set
        var endPage: Int? = null
            private set

        fun isNotEmpty(): Boolean = parts.isNotEmpty()

        fun add(piece: BlockPiece) {
            if (parts.isEmpty()) headingPath = piece.headingPath
            parts += piece.text
            piece.pageNumber?.let { page ->
                startPage = startPage?.let { minOf(it, page) } ?: page
                endPage = endPage?.let { maxOf(it, page) } ?: page
            }
        }

        fun preview(piece: BlockPiece): String {
            val values = parts + piece.text
            return compose(values, if (parts.isEmpty()) piece.headingPath else headingPath)
        }

        fun text(): String = compose(parts, headingPath)

        private fun compose(values: List<String>, headings: List<String>): String {
            val body = values.joinToString("\n\n")
            if (!config.includeHeadingPrefix || headings.isEmpty()) return body
            return headings.joinToString(config.headingSeparator) + "\n" + body
        }
    }
}

object EmbeddingInputNormalizer {
    fun normalize(value: String): String {
        return value
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("[\\t ]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .filter { it == '\n' || it.code >= 0x20 }
            .trim()
    }
}
