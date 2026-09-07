package com.alibaba.mnnllm.android.rag

import java.util.PriorityQueue
import kotlin.math.sqrt

fun interface RagReranker {
    fun rerank(query: String, candidates: List<RetrievalHit>): List<RetrievalHit>
}

data class RetrievalConfig(
    val topK: Int = 5,
    val candidateMultiplier: Int = 4,
    val minimumScore: Float = -1f,
    val maxKnowledgeBaseHits: Int = topK,
    val maxSessionAttachmentHits: Int = topK
) {
    init {
        require(topK > 0) { "Top K must be positive" }
        require(candidateMultiplier > 0) { "Candidate multiplier must be positive" }
        require(maxKnowledgeBaseHits >= 0) { "Knowledge base hit budget must not be negative" }
        require(maxSessionAttachmentHits >= 0) { "Session attachment hit budget must not be negative" }
        require(maxKnowledgeBaseHits + maxSessionAttachmentHits > 0) {
            "At least one retrieval source must have a positive hit budget"
        }
        require(minimumScore.isFinite() && minimumScore in -1f..1f) {
            "Minimum score must be finite and between -1 and 1"
        }
    }
}

class RagRetriever(
    private val database: RagDatabase,
    private val vectorStore: VectorStore,
    private val embeddingEngine: EmbeddingEngine,
    private val reranker: RagReranker? = null,
    private val config: RetrievalConfig = RetrievalConfig()
) {
    init {
        require(vectorStore.dimensions == embeddingEngine.dimensions) {
            "Embedding engine and vector store dimensions do not match"
        }
    }

    fun retrieve(knowledgeBaseId: Long, query: String): List<RetrievalHit> =
        retrieve(knowledgeBaseId, sessionId = null, query = query)

    fun retrieve(
        knowledgeBaseId: Long?,
        sessionId: String?,
        query: String
    ): List<RetrievalHit> {
        require(knowledgeBaseId == null || knowledgeBaseId > 0) {
            "Knowledge base id must be positive"
        }
        val normalizedSessionId = sessionId?.trim()?.takeIf(String::isNotEmpty)
        require(knowledgeBaseId != null || normalizedSessionId != null) {
            "A knowledge base or session scope is required"
        }
        val normalizedQuery = EmbeddingInputNormalizer.normalize(query)
        require(normalizedQuery.isNotEmpty()) { "Retrieval query must not be blank" }

        val queryVector = embeddingEngine.embed(normalizedQuery)
        validateVector(queryVector)
        val sourceCandidateLimit = Math.multiplyExact(config.topK, config.candidateMultiplier)
        val knowledgeBaseQueue = candidateQueue()
        val sessionQueue = candidateQueue()

        if (knowledgeBaseId != null && config.maxKnowledgeBaseHits > 0) {
            database.forEachReadyVectorChunk(knowledgeBaseId) { candidate ->
                offer(
                    knowledgeBaseQueue,
                    RetrievalHit(
                        chunkId = candidate.chunk.id,
                        documentId = candidate.chunk.documentId,
                        documentName = candidate.documentName,
                        text = candidate.chunk.text,
                        score = cosineSimilarity(queryVector, vectorStore.read(candidate.location)),
                        headingPath = candidate.chunk.headingPath,
                        startPage = candidate.chunk.startPage,
                        endPage = candidate.chunk.endPage,
                        sourceType = RagSourceType.KNOWLEDGE_BASE
                    ),
                    sourceCandidateLimit
                )
            }
        }

        if (normalizedSessionId != null && config.maxSessionAttachmentHits > 0) {
            database.forEachReadySessionAttachmentVectorChunk(normalizedSessionId) { candidate ->
                check(candidate.chunk.sessionId == normalizedSessionId) {
                    "Session attachment retrieval escaped session scope"
                }
                offer(
                    sessionQueue,
                    RetrievalHit(
                        chunkId = candidate.chunk.id,
                        documentId = candidate.chunk.attachmentId,
                        documentName = candidate.attachmentName,
                        text = candidate.chunk.text,
                        score = cosineSimilarity(queryVector, vectorStore.read(candidate.location)),
                        headingPath = candidate.chunk.headingPath,
                        startPage = candidate.chunk.startPage,
                        endPage = candidate.chunk.endPage,
                        sourceType = RagSourceType.SESSION_ATTACHMENT,
                        sessionId = normalizedSessionId
                    ),
                    sourceCandidateLimit
                )
            }
        }

        val candidates = knowledgeBaseQueue.toList()
            .sortedWith(HIT_ORDER)
            .take(config.maxKnowledgeBaseHits)
            .plus(
                sessionQueue.toList()
                    .sortedWith(HIT_ORDER)
                    .take(config.maxSessionAttachmentHits)
            )
            .sortedWith(HIT_ORDER)
        val ranked = reranker?.rerank(normalizedQuery, candidates) ?: candidates
        return ranked
            .filter { it.score >= config.minimumScore }
            .distinctBy { Triple(it.sourceType, it.documentId, it.chunkId) }
            .take(config.topK)
    }

    private fun candidateQueue(): PriorityQueue<RetrievalHit> = PriorityQueue(
        compareBy<RetrievalHit> { it.score }
            .thenByDescending { it.sourceType.ordinal }
            .thenByDescending { it.documentId }
            .thenByDescending { it.chunkId }
    )

    private fun offer(
        queue: PriorityQueue<RetrievalHit>,
        hit: RetrievalHit,
        candidateLimit: Int
    ) {
        if (hit.score < config.minimumScore) return
        queue += hit
        if (queue.size > candidateLimit) queue.poll()
    }

    private fun validateVector(vector: FloatArray) {
        require(vector.size == embeddingEngine.dimensions) {
            "Embedding vector dimensions do not match"
        }
        require(vector.all(Float::isFinite)) { "Embedding vector contains NaN or infinity" }
    }

    private fun cosineSimilarity(left: FloatArray, right: FloatArray): Float {
        require(left.size == right.size && left.isNotEmpty()) { "Vector dimensions do not match" }
        var dot = 0.0
        var leftSquared = 0.0
        var rightSquared = 0.0
        for (index in left.indices) {
            val a = left[index].toDouble()
            val b = right[index].toDouble()
            require(a.isFinite() && b.isFinite()) { "Vector contains NaN or infinity" }
            dot += a * b
            leftSquared += a * a
            rightSquared += b * b
        }
        if (leftSquared == 0.0 || rightSquared == 0.0) return 0f
        return (dot / (sqrt(leftSquared) * sqrt(rightSquared))).toFloat().coerceIn(-1f, 1f)
    }

    companion object {
        private val HIT_ORDER = compareByDescending<RetrievalHit> { it.score }
            .thenBy { it.sourceType.ordinal }
            .thenBy { it.documentId }
            .thenBy { it.chunkId }
    }
}

data class RagContextConfig(
    val maxCharacters: Int = 8_000,
    val maxHits: Int = 5
) {
    init {
        require(maxCharacters > 0) { "Context character budget must be positive" }
        require(maxHits > 0) { "Context hit limit must be positive" }
    }
}

data class RagContext(
    val prompt: String,
    val citations: List<RagCitation>
)

data class RagCitation(
    val index: Int,
    val chunkId: Long,
    val documentId: Long,
    val documentName: String,
    val headingPath: List<String>,
    val startPage: Int?,
    val endPage: Int?,
    val score: Float,
    val sourceType: RagSourceType = RagSourceType.KNOWLEDGE_BASE,
    val sessionId: String? = null
)

class RagContextAssembler(
    private val config: RagContextConfig = RagContextConfig()
) {
    fun assemble(question: String, hits: List<RetrievalHit>): RagContext {
        val normalizedQuestion = EmbeddingInputNormalizer.normalize(question)
        require(normalizedQuestion.isNotEmpty()) { "Question must not be blank" }
        if (hits.isEmpty()) return RagContext(normalizedQuestion, emptyList())

        val selected = mutableListOf<Pair<RetrievalHit, String>>()
        var usedCharacters = 0
        for (hit in hits.distinctBy { Triple(it.sourceType, it.documentId, it.chunkId) }.take(config.maxHits)) {
            val index = selected.size + 1
            val block = formatSource(index, hit)
            if (usedCharacters + block.length > config.maxCharacters) {
                val remaining = config.maxCharacters - usedCharacters
                if (remaining > MIN_TRUNCATED_SOURCE_LENGTH) {
                    selected += hit to truncate(block, remaining)
                }
                break
            }
            selected += hit to block
            usedCharacters += block.length
        }

        if (selected.isEmpty()) return RagContext(normalizedQuestion, emptyList())
        val contextBody = selected.joinToString("\n\n") { it.second }
        val prompt = listOf(
            "Answer the question using only the local knowledge excerpts below.",
            "If the excerpts do not contain enough information, say so clearly.",
            "Cite supporting excerpts with source markers such as [1] or [2].",
            "",
            "Local knowledge excerpts:",
            contextBody,
            "",
            "User question:",
            normalizedQuestion
        ).joinToString("\n")
        val citations = selected.mapIndexed { index, (hit, _) ->
            RagCitation(
                index = index + 1,
                chunkId = hit.chunkId,
                documentId = hit.documentId,
                documentName = hit.documentName,
                headingPath = hit.headingPath,
                startPage = hit.startPage,
                endPage = hit.endPage,
                score = hit.score,
                sourceType = hit.sourceType,
                sessionId = hit.sessionId
            )
        }
        return RagContext(prompt, citations)
    }

    private fun formatSource(index: Int, hit: RetrievalHit): String {
        val location = buildList {
            if (hit.headingPath.isNotEmpty()) add(hit.headingPath.joinToString(" > "))
            pageLabel(hit.startPage, hit.endPage)?.let(::add)
        }.joinToString(", ")
        val suffix = location.takeIf(String::isNotEmpty)?.let { " | $it" }.orEmpty()
        return "[$index] ${hit.documentName}$suffix\n${hit.text.trim()}"
    }

    private fun pageLabel(start: Int?, end: Int?): String? = when {
        start == null && end == null -> null
        start != null && end != null && start != end -> "pages $start-$end"
        else -> "page ${start ?: end}"
    }

    private fun truncate(value: String, maximum: Int): String {
        if (value.length <= maximum) return value
        if (maximum <= ELLIPSIS.length) return ELLIPSIS.take(maximum)
        var boundary = maximum - ELLIPSIS.length
        if (boundary in 1 until value.length && Character.isLowSurrogate(value[boundary]) &&
            Character.isHighSurrogate(value[boundary - 1])) {
            boundary--
        }
        return value.substring(0, boundary).trimEnd() + ELLIPSIS
    }

    companion object {
        private const val MIN_TRUNCATED_SOURCE_LENGTH = 64
        private const val ELLIPSIS = "..."
    }
}

fun interface RagPromptProvider {
    fun augment(userPrompt: String): RagContext
}
