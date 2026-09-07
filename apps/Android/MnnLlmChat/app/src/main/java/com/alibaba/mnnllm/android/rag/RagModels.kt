package com.alibaba.mnnllm.android.rag

/**
 * Persistent indexing lifecycle for a document.
 * Only READY documents may participate in retrieval.
 */
enum class RagDocumentStatus {
    QUEUED,
    PARSING,
    OCR,
    EMBEDDING,
    READY,
    FAILED
}

data class KnowledgeBase(
    val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long
)

enum class RagSourceType {
    KNOWLEDGE_BASE,
    SESSION_ATTACHMENT
}

data class SessionAttachment(
    val id: Long = 0,
    val sessionId: String,
    val sourceUri: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val sha256: String,
    val privatePath: String,
    val parserVersion: Int,
    val status: RagDocumentStatus,
    val errorMessage: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

data class SessionAttachmentChunk(
    val id: Long = 0,
    val attachmentId: Long,
    val sessionId: String,
    val ordinal: Int,
    val text: String,
    val tokenCount: Int,
    val headingPath: List<String> = emptyList(),
    val startPage: Int? = null,
    val endPage: Int? = null,
    val layoutJson: String? = null,
    val vectorOffset: Long? = null,
    val vectorLength: Int? = null,
    val vectorDimensions: Int? = null
)

data class RagDocument(
    val id: Long = 0,
    val knowledgeBaseId: Long,
    val sourceUri: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val sha256: String,
    val parserVersion: Int,
    val status: RagDocumentStatus,
    val errorMessage: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

data class LayoutBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

enum class LayoutBlockType {
    TITLE,
    PARAGRAPH,
    LIST_ITEM,
    QUOTE,
    CODE_BLOCK,
    TABLE,
    OCR_TEXT
}

data class LayoutBlock(
    val type: LayoutBlockType,
    val text: String,
    val pageNumber: Int? = null,
    val bounds: LayoutBounds? = null,
    val headingPath: List<String> = emptyList(),
    val readingOrder: Int = 0
)

data class RagChunk(
    val id: Long = 0,
    val documentId: Long,
    val ordinal: Int,
    val text: String,
    val tokenCount: Int,
    val headingPath: List<String> = emptyList(),
    val startPage: Int? = null,
    val endPage: Int? = null,
    val layoutJson: String? = null,
    val vectorOffset: Long? = null,
    val vectorLength: Int? = null
)

data class VectorLocation(
    val offset: Long,
    val length: Int,
    val dimensions: Int
)

data class RetrievalHit(
    val chunkId: Long,
    val documentId: Long,
    val documentName: String,
    val text: String,
    val score: Float,
    val headingPath: List<String> = emptyList(),
    val startPage: Int? = null,
    val endPage: Int? = null,
    val sourceType: RagSourceType = RagSourceType.KNOWLEDGE_BASE,
    val sessionId: String? = null
)

data class RagModelTensor(
    val name: String,
    val dtype: String,
    val shape: List<Int>,
    val layout: String? = null
)

data class RagModelFile(
    val relativePath: String,
    val sha256: String,
    val sizeBytes: Long
)

data class RagModelDefinition(
    val id: String,
    val role: String,
    val format: String,
    val files: List<RagModelFile>,
    val inputs: List<RagModelTensor>,
    val outputs: List<RagModelTensor>,
    val threads: Int = 4,
    val options: Map<String, String> = emptyMap()
)

data class RagModelManifest(
    val schemaVersion: Int,
    val bundleVersion: String,
    val models: List<RagModelDefinition>
)
