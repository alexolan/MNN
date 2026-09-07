package com.alibaba.mnnllm.android.rag

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Stores RAG metadata and chunk text. Vector payloads remain in VectorStore files.
 */
class RagDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_KNOWLEDGE_BASE)
        db.execSQL(CREATE_DOCUMENT)
        db.execSQL(CREATE_CHUNK)
        db.execSQL(CREATE_MODEL_STATE)
        db.execSQL(CREATE_SESSION_ATTACHMENT)
        db.execSQL(CREATE_SESSION_ATTACHMENT_CHUNK)
        createIndexes(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.beginTransaction()
            try {
                db.execSQL(CREATE_SESSION_ATTACHMENT)
                db.execSQL(CREATE_SESSION_ATTACHMENT_CHUNK)
                createIndexes(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    fun createKnowledgeBase(name: String, now: Long = System.currentTimeMillis()): Long {
        require(name.isNotBlank()) { "Knowledge base name must not be blank" }
        return writableDatabase.insertOrThrow(
            TABLE_KNOWLEDGE_BASE,
            null,
            ContentValues().apply {
                put(COLUMN_NAME, name.trim())
                put(COLUMN_CREATED_AT, now)
                put(COLUMN_UPDATED_AT, now)
            }
        )
    }

    fun listKnowledgeBases(): List<KnowledgeBase> {
        readableDatabase.query(
            TABLE_KNOWLEDGE_BASE,
            null,
            null,
            null,
            null,
            null,
            "$COLUMN_UPDATED_AT DESC, $COLUMN_ID DESC"
        ).use { cursor ->
            val items = mutableListOf<KnowledgeBase>()
            while (cursor.moveToNext()) {
                items += KnowledgeBase(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
                    name = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NAME)),
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_CREATED_AT)),
                    updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_UPDATED_AT))
                )
            }
            return items
        }
    }

    fun listDocuments(knowledgeBaseId: Long): List<RagDocument> {
        require(knowledgeBaseId > 0) { "Knowledge base id must be positive" }
        readableDatabase.query(
            TABLE_DOCUMENT,
            null,
            "$COLUMN_KNOWLEDGE_BASE_ID=?",
            arrayOf(knowledgeBaseId.toString()),
            null,
            null,
            "$COLUMN_UPDATED_AT DESC, $COLUMN_ID DESC"
        ).use { cursor ->
            val items = mutableListOf<RagDocument>()
            while (cursor.moveToNext()) items += cursor.toDocument()
            return items
        }
    }

    fun getKnowledgeBase(knowledgeBaseId: Long): KnowledgeBase? {
        require(knowledgeBaseId > 0) { "Knowledge base id must be positive" }
        readableDatabase.query(
            TABLE_KNOWLEDGE_BASE,
            null,
            "$COLUMN_ID=?",
            arrayOf(knowledgeBaseId.toString()),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return KnowledgeBase(
                id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
                name = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NAME)),
                createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_CREATED_AT)),
                updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_UPDATED_AT))
            )
        }
    }

    fun insertDocument(document: RagDocument): Long {
        return writableDatabase.insertOrThrow(
            TABLE_DOCUMENT,
            null,
            documentValues(document, includeId = false)
        )
    }

    fun findDocumentByHash(knowledgeBaseId: Long, sha256: String): RagDocument? {
        readableDatabase.query(
            TABLE_DOCUMENT,
            null,
            "$COLUMN_KNOWLEDGE_BASE_ID=? AND $COLUMN_SHA256=?",
            arrayOf(knowledgeBaseId.toString(), sha256.lowercase()),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toDocument() else null
        }
    }

    fun updateDocumentStatus(
        documentId: Long,
        status: RagDocumentStatus,
        errorMessage: String? = null,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val values = ContentValues().apply {
            put(COLUMN_STATUS, status.name)
            if (errorMessage == null) putNull(COLUMN_ERROR_MESSAGE) else put(COLUMN_ERROR_MESSAGE, errorMessage)
            put(COLUMN_UPDATED_AT, now)
        }
        return writableDatabase.update(
            TABLE_DOCUMENT,
            values,
            "$COLUMN_ID=?",
            arrayOf(documentId.toString())
        ) == 1
    }

    fun replaceChunks(documentId: Long, chunks: List<RagChunk>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_CHUNK, "$COLUMN_DOCUMENT_ID=?", arrayOf(documentId.toString()))
            chunks.forEach { chunk ->
                require(chunk.documentId == documentId) { "Chunk belongs to another document" }
                db.insertOrThrow(TABLE_CHUNK, null, chunkValues(chunk))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun attachVector(chunkId: Long, location: VectorLocation): Boolean {
        require(location.offset >= 0) { "Vector offset must not be negative" }
        require(location.length > 0) { "Vector length must be positive" }
        require(location.dimensions > 0) { "Vector dimensions must be positive" }
        return writableDatabase.update(
            TABLE_CHUNK,
            vectorValues(location),
            "$COLUMN_ID=?",
            arrayOf(chunkId.toString())
        ) == 1
    }

    fun listChunksWithoutVectors(documentId: Long): List<RagChunk> {
        require(documentId > 0) { "Document id must be positive" }
        readableDatabase.query(
            TABLE_CHUNK,
            null,
            "$COLUMN_DOCUMENT_ID=? AND $COLUMN_VECTOR_OFFSET IS NULL",
            arrayOf(documentId.toString()),
            null,
            null,
            "$COLUMN_ORDINAL ASC"
        ).use { cursor ->
            val chunks = mutableListOf<RagChunk>()
            while (cursor.moveToNext()) chunks += cursor.toChunk()
            return chunks
        }
    }

    fun attachVectors(bindings: List<Pair<Long, VectorLocation>>) {
        if (bindings.isEmpty()) return
        require(bindings.map { it.first }.distinct().size == bindings.size) {
            "Chunk vector bindings must be unique"
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            bindings.forEach { (chunkId, location) ->
                require(chunkId > 0) { "Chunk id must be positive" }
                require(location.offset >= 0) { "Vector offset must not be negative" }
                require(location.length > 0) { "Vector length must be positive" }
                require(location.dimensions > 0) { "Vector dimensions must be positive" }
                check(
                    db.update(
                        TABLE_CHUNK,
                        vectorValues(location),
                        "$COLUMN_ID=? AND $COLUMN_VECTOR_OFFSET IS NULL",
                        arrayOf(chunkId.toString())
                    ) == 1
                ) { "Chunk does not exist or already has a vector" }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun forEachReadyVectorChunk(
        knowledgeBaseId: Long,
        consumer: (ReadyVectorChunk) -> Unit
    ) {
        require(knowledgeBaseId > 0) { "Knowledge base id must be positive" }
        val sql = """
            SELECT c.*, d.$COLUMN_DISPLAY_NAME AS retrieval_document_name
            FROM $TABLE_CHUNK c
            INNER JOIN $TABLE_DOCUMENT d ON d.$COLUMN_ID = c.$COLUMN_DOCUMENT_ID
            WHERE d.$COLUMN_KNOWLEDGE_BASE_ID = ?
              AND d.$COLUMN_STATUS = ?
              AND c.$COLUMN_VECTOR_OFFSET IS NOT NULL
              AND c.$COLUMN_VECTOR_LENGTH IS NOT NULL
              AND c.$COLUMN_VECTOR_DIMENSIONS IS NOT NULL
            ORDER BY d.$COLUMN_ID ASC, c.$COLUMN_ORDINAL ASC
        """.trimIndent()
        readableDatabase.rawQuery(
            sql,
            arrayOf(knowledgeBaseId.toString(), RagDocumentStatus.READY.name)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val chunk = cursor.toChunk()
                val dimensions = cursor.getInt(
                    cursor.getColumnIndexOrThrow(COLUMN_VECTOR_DIMENSIONS)
                )
                val location = VectorLocation(
                    offset = requireNotNull(chunk.vectorOffset),
                    length = requireNotNull(chunk.vectorLength),
                    dimensions = dimensions
                )
                consumer(
                    ReadyVectorChunk(
                        chunk = chunk,
                        documentName = cursor.getString(
                            cursor.getColumnIndexOrThrow("retrieval_document_name")
                        ),
                        location = location
                    )
                )
            }
        }
    }

    private fun vectorValues(location: VectorLocation): ContentValues {
        return ContentValues().apply {
            put(COLUMN_VECTOR_OFFSET, location.offset)
            put(COLUMN_VECTOR_LENGTH, location.length)
            put(COLUMN_VECTOR_DIMENSIONS, location.dimensions)
        }
    }

    data class ReadyVectorChunk(
        val chunk: RagChunk,
        val documentName: String,
        val location: VectorLocation
    )

    fun insertSessionAttachment(attachment: SessionAttachment): Long {
        require(attachment.sessionId.isNotBlank()) { "Session id must not be blank" }
        require(attachment.displayName.isNotBlank()) { "Attachment display name must not be blank" }
        require(attachment.sizeBytes >= 0) { "Attachment size must not be negative" }
        require(attachment.sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Attachment SHA-256 must contain 64 hexadecimal characters" }
        require(attachment.privatePath.isNotBlank()) { "Attachment private path must not be blank" }
        return writableDatabase.insertOrThrow(
            TABLE_SESSION_ATTACHMENT,
            null,
            ContentValues().apply {
                put(COLUMN_SESSION_ID, attachment.sessionId)
                put(COLUMN_SOURCE_URI, attachment.sourceUri)
                put(COLUMN_DISPLAY_NAME, attachment.displayName)
                if (attachment.mimeType == null) putNull(COLUMN_MIME_TYPE) else put(COLUMN_MIME_TYPE, attachment.mimeType)
                put(COLUMN_SIZE_BYTES, attachment.sizeBytes)
                put(COLUMN_SHA256, attachment.sha256.lowercase())
                put(COLUMN_PRIVATE_PATH, attachment.privatePath)
                put(COLUMN_PARSER_VERSION, attachment.parserVersion)
                put(COLUMN_STATUS, attachment.status.name)
                if (attachment.errorMessage == null) putNull(COLUMN_ERROR_MESSAGE) else put(COLUMN_ERROR_MESSAGE, attachment.errorMessage)
                put(COLUMN_CREATED_AT, attachment.createdAt)
                put(COLUMN_UPDATED_AT, attachment.updatedAt)
            }
        )
    }

    fun updateSessionAttachmentStatus(
        attachmentId: Long,
        sessionId: String,
        status: RagDocumentStatus,
        errorMessage: String? = null,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        require(attachmentId > 0) { "Attachment id must be positive" }
        require(sessionId.isNotBlank()) { "Session id must not be blank" }
        val values = ContentValues().apply {
            put(COLUMN_STATUS, status.name)
            if (errorMessage == null) putNull(COLUMN_ERROR_MESSAGE) else put(COLUMN_ERROR_MESSAGE, errorMessage)
            put(COLUMN_UPDATED_AT, now)
        }
        return writableDatabase.update(
            TABLE_SESSION_ATTACHMENT,
            values,
            "$COLUMN_ID=? AND $COLUMN_SESSION_ID=?",
            arrayOf(attachmentId.toString(), sessionId)
        ) == 1
    }

    fun listSessionAttachments(sessionId: String): List<SessionAttachment> {
        require(sessionId.isNotBlank()) { "Session id must not be blank" }
        readableDatabase.query(
            TABLE_SESSION_ATTACHMENT,
            null,
            "$COLUMN_SESSION_ID=?",
            arrayOf(sessionId),
            null,
            null,
            "$COLUMN_UPDATED_AT DESC, $COLUMN_ID DESC"
        ).use { cursor ->
            val result = mutableListOf<SessionAttachment>()
            while (cursor.moveToNext()) result += cursor.toSessionAttachment()
            return result
        }
    }

    fun getSessionAttachment(attachmentId: Long, sessionId: String): SessionAttachment? {
        require(attachmentId > 0) { "Attachment id must be positive" }
        require(sessionId.isNotBlank()) { "Session id must not be blank" }
        readableDatabase.query(
            TABLE_SESSION_ATTACHMENT,
            null,
            "$COLUMN_ID=? AND $COLUMN_SESSION_ID=?",
            arrayOf(attachmentId.toString(), sessionId),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toSessionAttachment() else null
        }
    }

    fun listSessionAttachmentsForRecovery(): List<SessionAttachment> {
        val recoverable = listOf(
            RagDocumentStatus.QUEUED,
            RagDocumentStatus.PARSING,
            RagDocumentStatus.OCR,
            RagDocumentStatus.EMBEDDING
        )
        val placeholders = recoverable.joinToString(",") { "?" }
        readableDatabase.query(
            TABLE_SESSION_ATTACHMENT,
            null,
            "$COLUMN_STATUS IN ($placeholders)",
            recoverable.map { it.name }.toTypedArray(),
            null,
            null,
            "$COLUMN_UPDATED_AT ASC, $COLUMN_ID ASC"
        ).use { cursor ->
            val result = mutableListOf<SessionAttachment>()
            while (cursor.moveToNext()) result += cursor.toSessionAttachment()
            return result
        }
    }

    fun findSessionAttachmentByHash(sessionId: String, sha256: String): SessionAttachment? {
        require(sessionId.isNotBlank()) { "Session id must not be blank" }
        readableDatabase.query(
            TABLE_SESSION_ATTACHMENT,
            null,
            "$COLUMN_SESSION_ID=? AND $COLUMN_SHA256=?",
            arrayOf(sessionId, sha256.lowercase()),
            null,
            null,
            null,
            "1"
        ).use { cursor -> return if (cursor.moveToFirst()) cursor.toSessionAttachment() else null }
    }

    fun replaceSessionAttachmentChunks(attachmentId: Long, sessionId: String, chunks: List<SessionAttachmentChunk>) {
        require(attachmentId > 0) { "Attachment id must be positive" }
        require(sessionId.isNotBlank()) { "Session id must not be blank" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val owner = db.query(
                TABLE_SESSION_ATTACHMENT,
                arrayOf(COLUMN_SESSION_ID),
                "$COLUMN_ID=?",
                arrayOf(attachmentId.toString()),
                null,
                null,
                null,
                "1"
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            require(owner == sessionId) { "Attachment does not belong to the requested session" }
            db.delete(TABLE_SESSION_ATTACHMENT_CHUNK, "$COLUMN_ATTACHMENT_ID=?", arrayOf(attachmentId.toString()))
            chunks.forEach { chunk ->
                require(chunk.attachmentId == attachmentId) { "Chunk belongs to another attachment" }
                require(chunk.sessionId == sessionId) { "Chunk belongs to another session" }
                db.insertOrThrow(
                    TABLE_SESSION_ATTACHMENT_CHUNK,
                    null,
                    ContentValues().apply {
                        put(COLUMN_ATTACHMENT_ID, chunk.attachmentId)
                        put(COLUMN_SESSION_ID, chunk.sessionId)
                        put(COLUMN_ORDINAL, chunk.ordinal)
                        put(COLUMN_TEXT, chunk.text)
                        put(COLUMN_TOKEN_COUNT, chunk.tokenCount)
                        put(COLUMN_HEADING_PATH, chunk.headingPath.joinToString(HEADING_SEPARATOR))
                        if (chunk.startPage == null) putNull(COLUMN_START_PAGE) else put(COLUMN_START_PAGE, chunk.startPage)
                        if (chunk.endPage == null) putNull(COLUMN_END_PAGE) else put(COLUMN_END_PAGE, chunk.endPage)
                        if (chunk.layoutJson == null) putNull(COLUMN_LAYOUT_JSON) else put(COLUMN_LAYOUT_JSON, chunk.layoutJson)
                        if (chunk.vectorOffset == null) putNull(COLUMN_VECTOR_OFFSET) else put(COLUMN_VECTOR_OFFSET, chunk.vectorOffset)
                        if (chunk.vectorLength == null) putNull(COLUMN_VECTOR_LENGTH) else put(COLUMN_VECTOR_LENGTH, chunk.vectorLength)
                        if (chunk.vectorDimensions == null) putNull(COLUMN_VECTOR_DIMENSIONS) else put(COLUMN_VECTOR_DIMENSIONS, chunk.vectorDimensions)
                    }
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun listSessionAttachmentChunksWithoutVectors(
        attachmentId: Long,
        sessionId: String
    ): List<SessionAttachmentChunk> {
        require(attachmentId > 0) { "Attachment id must be positive" }
        require(sessionId.isNotBlank()) { "Session id must not be blank" }
        readableDatabase.query(
            TABLE_SESSION_ATTACHMENT_CHUNK,
            null,
            "$COLUMN_ATTACHMENT_ID=? AND $COLUMN_SESSION_ID=? AND $COLUMN_VECTOR_OFFSET IS NULL",
            arrayOf(attachmentId.toString(), sessionId),
            null,
            null,
            "$COLUMN_ORDINAL ASC"
        ).use { cursor ->
            val result = mutableListOf<SessionAttachmentChunk>()
            while (cursor.moveToNext()) result += cursor.toSessionAttachmentChunk()
            return result
        }
    }

    fun attachSessionAttachmentVectors(
        attachmentId: Long,
        sessionId: String,
        bindings: List<Pair<Long, VectorLocation>>
    ) {
        require(attachmentId > 0) { "Attachment id must be positive" }
        require(sessionId.isNotBlank()) { "Session id must not be blank" }
        if (bindings.isEmpty()) return
        require(bindings.map { it.first }.distinct().size == bindings.size) {
            "Attachment chunk vector bindings must be unique"
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            bindings.forEach { (chunkId, location) ->
                require(chunkId > 0) { "Chunk id must be positive" }
                require(location.offset >= 0) { "Vector offset must not be negative" }
                require(location.length > 0) { "Vector length must be positive" }
                require(location.dimensions > 0) { "Vector dimensions must be positive" }
                check(
                    db.update(
                        TABLE_SESSION_ATTACHMENT_CHUNK,
                        vectorValues(location),
                        "$COLUMN_ID=? AND $COLUMN_ATTACHMENT_ID=? AND $COLUMN_SESSION_ID=? AND $COLUMN_VECTOR_OFFSET IS NULL",
                        arrayOf(chunkId.toString(), attachmentId.toString(), sessionId)
                    ) == 1
                ) { "Attachment chunk does not exist, belongs to another session, or already has a vector" }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun listReadySessionAttachmentChunks(sessionId: String): List<SessionAttachmentChunk> {
        require(sessionId.isNotBlank()) { "Session id must not be blank" }
        val sql = """
            SELECT c.* FROM $TABLE_SESSION_ATTACHMENT_CHUNK c
            INNER JOIN $TABLE_SESSION_ATTACHMENT a ON a.$COLUMN_ID = c.$COLUMN_ATTACHMENT_ID
            WHERE c.$COLUMN_SESSION_ID = ? AND a.$COLUMN_SESSION_ID = ? AND a.$COLUMN_STATUS = ?
            ORDER BY a.$COLUMN_ID ASC, c.$COLUMN_ORDINAL ASC
        """.trimIndent()
        readableDatabase.rawQuery(sql, arrayOf(sessionId, sessionId, RagDocumentStatus.READY.name)).use { cursor ->
            val result = mutableListOf<SessionAttachmentChunk>()
            while (cursor.moveToNext()) result += cursor.toSessionAttachmentChunk()
            return result
        }
    }

    fun forEachReadySessionAttachmentVectorChunk(
        sessionId: String,
        consumer: (ReadySessionAttachmentVectorChunk) -> Unit
    ) {
        require(sessionId.isNotBlank()) { "Session id must not be blank" }
        val sql = """
            SELECT c.*, a.$COLUMN_DISPLAY_NAME AS retrieval_document_name
            FROM $TABLE_SESSION_ATTACHMENT_CHUNK c
            INNER JOIN $TABLE_SESSION_ATTACHMENT a ON a.$COLUMN_ID = c.$COLUMN_ATTACHMENT_ID
            WHERE c.$COLUMN_SESSION_ID = ?
              AND a.$COLUMN_SESSION_ID = ?
              AND a.$COLUMN_STATUS = ?
              AND c.$COLUMN_VECTOR_OFFSET IS NOT NULL
              AND c.$COLUMN_VECTOR_LENGTH IS NOT NULL
              AND c.$COLUMN_VECTOR_DIMENSIONS IS NOT NULL
            ORDER BY a.$COLUMN_ID ASC, c.$COLUMN_ORDINAL ASC
        """.trimIndent()
        readableDatabase.rawQuery(
            sql,
            arrayOf(sessionId, sessionId, RagDocumentStatus.READY.name)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val chunk = cursor.toSessionAttachmentChunk()
                check(chunk.sessionId == sessionId) { "Session attachment chunk escaped session scope" }
                consumer(
                    ReadySessionAttachmentVectorChunk(
                        chunk = chunk,
                        attachmentName = cursor.getString(
                            cursor.getColumnIndexOrThrow("retrieval_document_name")
                        ),
                        location = VectorLocation(
                            offset = requireNotNull(chunk.vectorOffset),
                            length = requireNotNull(chunk.vectorLength),
                            dimensions = requireNotNull(chunk.vectorDimensions)
                        )
                    )
                )
            }
        }
    }

    data class ReadySessionAttachmentVectorChunk(
        val chunk: SessionAttachmentChunk,
        val attachmentName: String,
        val location: VectorLocation
    )

    fun deleteSessionAttachment(attachmentId: Long, sessionId: String): String? {
        require(attachmentId > 0) { "Attachment id must be positive" }
        require(sessionId.isNotBlank()) { "Session id must not be blank" }
        val database = writableDatabase
        val path = database.query(
            TABLE_SESSION_ATTACHMENT,
            arrayOf(COLUMN_PRIVATE_PATH),
            "$COLUMN_ID=? AND $COLUMN_SESSION_ID=?",
            arrayOf(attachmentId.toString(), sessionId),
            null,
            null,
            null,
            "1"
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        if (path != null) {
            database.delete(
                TABLE_SESSION_ATTACHMENT,
                "$COLUMN_ID=? AND $COLUMN_SESSION_ID=?",
                arrayOf(attachmentId.toString(), sessionId)
            )
        }
        return path
    }

    fun deleteSessionAttachments(sessionId: String): List<String> {
        require(sessionId.isNotBlank()) { "Session id must not be blank" }
        val database = writableDatabase
        val paths = database.query(
            TABLE_SESSION_ATTACHMENT,
            arrayOf(COLUMN_PRIVATE_PATH),
            "$COLUMN_SESSION_ID=?",
            arrayOf(sessionId),
            null,
            null,
            "$COLUMN_ID ASC"
        ).use { cursor ->
            val result = mutableListOf<String>()
            while (cursor.moveToNext()) result += cursor.getString(0)
            result
        }
        database.delete(TABLE_SESSION_ATTACHMENT, "$COLUMN_SESSION_ID=?", arrayOf(sessionId))
        return paths
    }

    private fun Cursor.toSessionAttachment(): SessionAttachment = SessionAttachment(
        id = getLong(getColumnIndexOrThrow(COLUMN_ID)),
        sessionId = getString(getColumnIndexOrThrow(COLUMN_SESSION_ID)),
        sourceUri = getString(getColumnIndexOrThrow(COLUMN_SOURCE_URI)),
        displayName = getString(getColumnIndexOrThrow(COLUMN_DISPLAY_NAME)),
        mimeType = getNullableString(COLUMN_MIME_TYPE),
        sizeBytes = getLong(getColumnIndexOrThrow(COLUMN_SIZE_BYTES)),
        sha256 = getString(getColumnIndexOrThrow(COLUMN_SHA256)),
        privatePath = getString(getColumnIndexOrThrow(COLUMN_PRIVATE_PATH)),
        parserVersion = getInt(getColumnIndexOrThrow(COLUMN_PARSER_VERSION)),
        status = RagDocumentStatus.valueOf(getString(getColumnIndexOrThrow(COLUMN_STATUS))),
        errorMessage = getNullableString(COLUMN_ERROR_MESSAGE),
        createdAt = getLong(getColumnIndexOrThrow(COLUMN_CREATED_AT)),
        updatedAt = getLong(getColumnIndexOrThrow(COLUMN_UPDATED_AT))
    )

    private fun Cursor.toSessionAttachmentChunk(): SessionAttachmentChunk {
        val heading = getString(getColumnIndexOrThrow(COLUMN_HEADING_PATH))
        fun nullableLong(column: String): Long? {
            val index = getColumnIndexOrThrow(column)
            return if (isNull(index)) null else getLong(index)
        }
        return SessionAttachmentChunk(
            id = getLong(getColumnIndexOrThrow(COLUMN_ID)),
            attachmentId = getLong(getColumnIndexOrThrow(COLUMN_ATTACHMENT_ID)),
            sessionId = getString(getColumnIndexOrThrow(COLUMN_SESSION_ID)),
            ordinal = getInt(getColumnIndexOrThrow(COLUMN_ORDINAL)),
            text = getString(getColumnIndexOrThrow(COLUMN_TEXT)),
            tokenCount = getInt(getColumnIndexOrThrow(COLUMN_TOKEN_COUNT)),
            headingPath = heading.takeIf(String::isNotEmpty)?.split(HEADING_SEPARATOR).orEmpty(),
            startPage = getNullableInt(COLUMN_START_PAGE),
            endPage = getNullableInt(COLUMN_END_PAGE),
            layoutJson = getNullableString(COLUMN_LAYOUT_JSON),
            vectorOffset = nullableLong(COLUMN_VECTOR_OFFSET),
            vectorLength = getNullableInt(COLUMN_VECTOR_LENGTH),
            vectorDimensions = getNullableInt(COLUMN_VECTOR_DIMENSIONS)
        )
    }

    fun deleteDocument(documentId: Long): Boolean {
        return writableDatabase.delete(
            TABLE_DOCUMENT,
            "$COLUMN_ID=?",
            arrayOf(documentId.toString())
        ) == 1
    }

    fun deleteKnowledgeBase(knowledgeBaseId: Long): Boolean {
        return writableDatabase.delete(
            TABLE_KNOWLEDGE_BASE,
            "$COLUMN_ID=?",
            arrayOf(knowledgeBaseId.toString())
        ) == 1
    }

    private fun documentValues(document: RagDocument, includeId: Boolean): ContentValues {
        return ContentValues().apply {
            if (includeId && document.id > 0) put(COLUMN_ID, document.id)
            put(COLUMN_KNOWLEDGE_BASE_ID, document.knowledgeBaseId)
            put(COLUMN_SOURCE_URI, document.sourceUri)
            put(COLUMN_DISPLAY_NAME, document.displayName)
            if (document.mimeType == null) putNull(COLUMN_MIME_TYPE) else put(COLUMN_MIME_TYPE, document.mimeType)
            put(COLUMN_SIZE_BYTES, document.sizeBytes)
            put(COLUMN_SHA256, document.sha256.lowercase())
            put(COLUMN_PARSER_VERSION, document.parserVersion)
            put(COLUMN_STATUS, document.status.name)
            if (document.errorMessage == null) putNull(COLUMN_ERROR_MESSAGE) else put(COLUMN_ERROR_MESSAGE, document.errorMessage)
            put(COLUMN_CREATED_AT, document.createdAt)
            put(COLUMN_UPDATED_AT, document.updatedAt)
        }
    }

    private fun chunkValues(chunk: RagChunk): ContentValues {
        return ContentValues().apply {
            put(COLUMN_DOCUMENT_ID, chunk.documentId)
            put(COLUMN_ORDINAL, chunk.ordinal)
            put(COLUMN_TEXT, chunk.text)
            put(COLUMN_TOKEN_COUNT, chunk.tokenCount)
            put(COLUMN_HEADING_PATH, chunk.headingPath.joinToString(HEADING_SEPARATOR))
            if (chunk.startPage == null) putNull(COLUMN_START_PAGE) else put(COLUMN_START_PAGE, chunk.startPage)
            if (chunk.endPage == null) putNull(COLUMN_END_PAGE) else put(COLUMN_END_PAGE, chunk.endPage)
            if (chunk.layoutJson == null) putNull(COLUMN_LAYOUT_JSON) else put(COLUMN_LAYOUT_JSON, chunk.layoutJson)
            if (chunk.vectorOffset == null) putNull(COLUMN_VECTOR_OFFSET) else put(COLUMN_VECTOR_OFFSET, chunk.vectorOffset)
            if (chunk.vectorLength == null) putNull(COLUMN_VECTOR_LENGTH) else put(COLUMN_VECTOR_LENGTH, chunk.vectorLength)
        }
    }

    private fun Cursor.toDocument(): RagDocument {
        return RagDocument(
            id = getLong(getColumnIndexOrThrow(COLUMN_ID)),
            knowledgeBaseId = getLong(getColumnIndexOrThrow(COLUMN_KNOWLEDGE_BASE_ID)),
            sourceUri = getString(getColumnIndexOrThrow(COLUMN_SOURCE_URI)),
            displayName = getString(getColumnIndexOrThrow(COLUMN_DISPLAY_NAME)),
            mimeType = getNullableString(COLUMN_MIME_TYPE),
            sizeBytes = getLong(getColumnIndexOrThrow(COLUMN_SIZE_BYTES)),
            sha256 = getString(getColumnIndexOrThrow(COLUMN_SHA256)),
            parserVersion = getInt(getColumnIndexOrThrow(COLUMN_PARSER_VERSION)),
            status = RagDocumentStatus.valueOf(getString(getColumnIndexOrThrow(COLUMN_STATUS))),
            errorMessage = getNullableString(COLUMN_ERROR_MESSAGE),
            createdAt = getLong(getColumnIndexOrThrow(COLUMN_CREATED_AT)),
            updatedAt = getLong(getColumnIndexOrThrow(COLUMN_UPDATED_AT))
        )
    }

    private fun Cursor.toChunk(): RagChunk {
        val headingValue = getString(getColumnIndexOrThrow(COLUMN_HEADING_PATH))
        val vectorOffsetIndex = getColumnIndexOrThrow(COLUMN_VECTOR_OFFSET)
        val vectorLengthIndex = getColumnIndexOrThrow(COLUMN_VECTOR_LENGTH)
        return RagChunk(
            id = getLong(getColumnIndexOrThrow(COLUMN_ID)),
            documentId = getLong(getColumnIndexOrThrow(COLUMN_DOCUMENT_ID)),
            ordinal = getInt(getColumnIndexOrThrow(COLUMN_ORDINAL)),
            text = getString(getColumnIndexOrThrow(COLUMN_TEXT)),
            tokenCount = getInt(getColumnIndexOrThrow(COLUMN_TOKEN_COUNT)),
            headingPath = headingValue.takeIf(String::isNotEmpty)?.split(HEADING_SEPARATOR).orEmpty(),
            startPage = getNullableInt(COLUMN_START_PAGE),
            endPage = getNullableInt(COLUMN_END_PAGE),
            layoutJson = getNullableString(COLUMN_LAYOUT_JSON),
            vectorOffset = if (isNull(vectorOffsetIndex)) null else getLong(vectorOffsetIndex),
            vectorLength = if (isNull(vectorLengthIndex)) null else getInt(vectorLengthIndex)
        )
    }

    private fun Cursor.getNullableInt(column: String): Int? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getInt(index)
    }

    private fun Cursor.getNullableString(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_document_kb_status ON $TABLE_DOCUMENT($COLUMN_KNOWLEDGE_BASE_ID, $COLUMN_STATUS)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_document_hash ON $TABLE_DOCUMENT($COLUMN_KNOWLEDGE_BASE_ID, $COLUMN_SHA256)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chunk_document_ordinal ON $TABLE_CHUNK($COLUMN_DOCUMENT_ID, $COLUMN_ORDINAL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chunk_vector ON $TABLE_CHUNK($COLUMN_VECTOR_OFFSET) WHERE $COLUMN_VECTOR_OFFSET IS NOT NULL")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_session_attachment_status ON $TABLE_SESSION_ATTACHMENT($COLUMN_SESSION_ID, $COLUMN_STATUS)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_session_attachment_hash ON $TABLE_SESSION_ATTACHMENT($COLUMN_SESSION_ID, $COLUMN_SHA256)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_session_attachment_chunk_scope ON $TABLE_SESSION_ATTACHMENT_CHUNK($COLUMN_SESSION_ID, $COLUMN_ATTACHMENT_ID, $COLUMN_ORDINAL)")
    }

    companion object {
        private const val DB_NAME = "rag.db"
        private const val DB_VERSION = 2
        private const val HEADING_SEPARATOR = "\u001F"

        const val TABLE_KNOWLEDGE_BASE = "knowledge_base"
        const val TABLE_DOCUMENT = "document"
        const val TABLE_CHUNK = "chunk"
        const val TABLE_MODEL_STATE = "model_state"
        const val TABLE_SESSION_ATTACHMENT = "session_attachment"
        const val TABLE_SESSION_ATTACHMENT_CHUNK = "session_attachment_chunk"

        const val COLUMN_ID = "_id"
        const val COLUMN_NAME = "name"
        const val COLUMN_KNOWLEDGE_BASE_ID = "knowledge_base_id"
        const val COLUMN_DOCUMENT_ID = "document_id"
        const val COLUMN_ATTACHMENT_ID = "attachment_id"
        const val COLUMN_SESSION_ID = "session_id"
        const val COLUMN_PRIVATE_PATH = "private_path"
        const val COLUMN_SOURCE_URI = "source_uri"
        const val COLUMN_DISPLAY_NAME = "display_name"
        const val COLUMN_MIME_TYPE = "mime_type"
        const val COLUMN_SIZE_BYTES = "size_bytes"
        const val COLUMN_SHA256 = "sha256"
        const val COLUMN_PARSER_VERSION = "parser_version"
        const val COLUMN_STATUS = "status"
        const val COLUMN_ERROR_MESSAGE = "error_message"
        const val COLUMN_CREATED_AT = "created_at"
        const val COLUMN_UPDATED_AT = "updated_at"
        const val COLUMN_ORDINAL = "ordinal"
        const val COLUMN_TEXT = "text"
        const val COLUMN_TOKEN_COUNT = "token_count"
        const val COLUMN_HEADING_PATH = "heading_path"
        const val COLUMN_START_PAGE = "start_page"
        const val COLUMN_END_PAGE = "end_page"
        const val COLUMN_LAYOUT_JSON = "layout_json"
        const val COLUMN_VECTOR_OFFSET = "vector_offset"
        const val COLUMN_VECTOR_LENGTH = "vector_length"
        const val COLUMN_VECTOR_DIMENSIONS = "vector_dimensions"
        const val COLUMN_MODEL_ID = "model_id"
        const val COLUMN_ROLE = "role"
        const val COLUMN_MANIFEST_HASH = "manifest_hash"
        const val COLUMN_LOCAL_PATH = "local_path"
        const val COLUMN_VALIDATED_AT = "validated_at"

        private const val CREATE_KNOWLEDGE_BASE = """
            CREATE TABLE knowledge_base (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """

        private const val CREATE_DOCUMENT = """
            CREATE TABLE document (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                knowledge_base_id INTEGER NOT NULL,
                source_uri TEXT NOT NULL,
                display_name TEXT NOT NULL,
                mime_type TEXT,
                size_bytes INTEGER NOT NULL CHECK(size_bytes >= 0),
                sha256 TEXT NOT NULL CHECK(length(sha256) = 64),
                parser_version INTEGER NOT NULL,
                status TEXT NOT NULL CHECK(status IN ('QUEUED','PARSING','OCR','EMBEDDING','READY','FAILED')),
                error_message TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                UNIQUE(knowledge_base_id, sha256),
                FOREIGN KEY(knowledge_base_id) REFERENCES knowledge_base(_id) ON DELETE CASCADE
            )
        """

        private const val CREATE_CHUNK = """
            CREATE TABLE chunk (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                document_id INTEGER NOT NULL,
                ordinal INTEGER NOT NULL CHECK(ordinal >= 0),
                text TEXT NOT NULL,
                token_count INTEGER NOT NULL CHECK(token_count >= 0),
                heading_path TEXT NOT NULL DEFAULT '',
                start_page INTEGER,
                end_page INTEGER,
                layout_json TEXT,
                vector_offset INTEGER,
                vector_length INTEGER,
                vector_dimensions INTEGER,
                UNIQUE(document_id, ordinal),
                FOREIGN KEY(document_id) REFERENCES document(_id) ON DELETE CASCADE
            )
        """

        private const val CREATE_SESSION_ATTACHMENT = """
            CREATE TABLE IF NOT EXISTS session_attachment (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL CHECK(length(trim(session_id)) > 0),
                source_uri TEXT NOT NULL,
                display_name TEXT NOT NULL CHECK(length(trim(display_name)) > 0),
                mime_type TEXT,
                size_bytes INTEGER NOT NULL CHECK(size_bytes >= 0),
                sha256 TEXT NOT NULL CHECK(length(sha256) = 64),
                private_path TEXT NOT NULL CHECK(length(trim(private_path)) > 0),
                parser_version INTEGER NOT NULL CHECK(parser_version > 0),
                status TEXT NOT NULL CHECK(status IN ('QUEUED','PARSING','OCR','EMBEDDING','READY','FAILED')),
                error_message TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                UNIQUE(session_id, sha256),
                UNIQUE(private_path)
            )
        """

        private const val CREATE_SESSION_ATTACHMENT_CHUNK = """
            CREATE TABLE IF NOT EXISTS session_attachment_chunk (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                attachment_id INTEGER NOT NULL,
                session_id TEXT NOT NULL CHECK(length(trim(session_id)) > 0),
                ordinal INTEGER NOT NULL CHECK(ordinal >= 0),
                text TEXT NOT NULL,
                token_count INTEGER NOT NULL CHECK(token_count >= 0),
                heading_path TEXT NOT NULL DEFAULT '',
                start_page INTEGER,
                end_page INTEGER,
                layout_json TEXT,
                vector_offset INTEGER,
                vector_length INTEGER,
                vector_dimensions INTEGER,
                UNIQUE(attachment_id, ordinal),
                FOREIGN KEY(attachment_id) REFERENCES session_attachment(_id) ON DELETE CASCADE
            )
        """

        private const val CREATE_MODEL_STATE = """
            CREATE TABLE model_state (
                model_id TEXT PRIMARY KEY,
                role TEXT NOT NULL,
                manifest_hash TEXT NOT NULL,
                local_path TEXT NOT NULL,
                status TEXT NOT NULL,
                error_message TEXT,
                validated_at INTEGER NOT NULL
            )
        """
    }
}
