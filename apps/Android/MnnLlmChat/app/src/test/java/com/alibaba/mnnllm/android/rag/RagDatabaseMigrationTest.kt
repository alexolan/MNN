package com.alibaba.mnnllm.android.rag

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class RagDatabaseMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        deleteDatabaseFiles()
    }

    @After
    fun tearDown() {
        deleteDatabaseFiles()
    }

    @Test
    fun migratesVersionOneToVersionTwoWithoutLosingExistingRagData() {
        createVersionOneDatabase()

        RagDatabase(context).use { database ->
            assertEquals(2, database.readableDatabase.version)

            val document = database.findDocumentByHash(1L, HASH)
            assertNotNull(document)
            assertEquals("legacy.txt", document!!.displayName)
            assertEquals(RagDocumentStatus.READY, document.status)

            database.readableDatabase.rawQuery(
                "SELECT text FROM chunk WHERE document_id=1",
                null
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("legacy content", cursor.getString(0))
            }

            val attachmentId = database.insertSessionAttachment(
                SessionAttachment(
                    sessionId = "migration-session",
                    sourceUri = "content://migration/new.txt",
                    displayName = "new.txt",
                    mimeType = "text/plain",
                    sizeBytes = 3,
                    sha256 = SECOND_HASH,
                    privatePath = File(context.filesDir, "rag/session-attachments/new.txt").path,
                    parserVersion = 1,
                    status = RagDocumentStatus.QUEUED,
                    createdAt = 200,
                    updatedAt = 200
                )
            )
            assertTrue(attachmentId > 0)
            assertEquals(1, database.listSessionAttachments("migration-session").size)
        }
    }

    private fun createVersionOneDatabase() {
        val path = context.getDatabasePath(DB_NAME)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { database ->
            database.execSQL("PRAGMA foreign_keys=ON")
            database.execSQL(
                "CREATE TABLE knowledge_base (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)"
            )
            database.execSQL(
                "CREATE TABLE document (_id INTEGER PRIMARY KEY AUTOINCREMENT, knowledge_base_id INTEGER NOT NULL, source_uri TEXT NOT NULL, display_name TEXT NOT NULL, mime_type TEXT, size_bytes INTEGER NOT NULL CHECK(size_bytes >= 0), sha256 TEXT NOT NULL CHECK(length(sha256) = 64), parser_version INTEGER NOT NULL, status TEXT NOT NULL CHECK(status IN ('QUEUED','PARSING','OCR','EMBEDDING','READY','FAILED')), error_message TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, UNIQUE(knowledge_base_id, sha256), FOREIGN KEY(knowledge_base_id) REFERENCES knowledge_base(_id) ON DELETE CASCADE)"
            )
            database.execSQL(
                "CREATE TABLE chunk (_id INTEGER PRIMARY KEY AUTOINCREMENT, document_id INTEGER NOT NULL, ordinal INTEGER NOT NULL CHECK(ordinal >= 0), text TEXT NOT NULL, token_count INTEGER NOT NULL CHECK(token_count >= 0), heading_path TEXT NOT NULL DEFAULT '', start_page INTEGER, end_page INTEGER, layout_json TEXT, vector_offset INTEGER, vector_length INTEGER, vector_dimensions INTEGER, UNIQUE(document_id, ordinal), FOREIGN KEY(document_id) REFERENCES document(_id) ON DELETE CASCADE)"
            )
            database.execSQL(
                "CREATE TABLE model_state (model_id TEXT PRIMARY KEY, role TEXT NOT NULL, manifest_hash TEXT NOT NULL, local_path TEXT NOT NULL, status TEXT NOT NULL, error_message TEXT, validated_at INTEGER NOT NULL)"
            )
            database.execSQL("INSERT INTO knowledge_base(_id,name,created_at,updated_at) VALUES(1,'Legacy',100,100)")
            database.execSQL(
                "INSERT INTO document(_id,knowledge_base_id,source_uri,display_name,mime_type,size_bytes,sha256,parser_version,status,error_message,created_at,updated_at) VALUES(1,1,'content://legacy/source','legacy.txt','text/plain',14,?,1,'READY',NULL,100,100)",
                arrayOf(HASH)
            )
            database.execSQL(
                "INSERT INTO chunk(_id,document_id,ordinal,text,token_count,heading_path) VALUES(1,1,0,'legacy content',2,'')"
            )
            database.version = 1
        }
    }

    private fun deleteDatabaseFiles() {
        context.deleteDatabase(DB_NAME)
        val path = context.getDatabasePath(DB_NAME).path
        File("$path-wal").delete()
        File("$path-shm").delete()
    }

    companion object {
        private const val DB_NAME = "rag.db"
        private const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        private const val SECOND_HASH = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
    }
}
