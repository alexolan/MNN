package com.alibaba.mnnllm.android.rag

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Copies a user-authorized SAF model bundle into app-private storage, validates
 * its manifest and files, then atomically promotes it by manifest hash.
 */
class ModelBundleImporter(
    context: Context,
    private val validator: ModelManifestValidator = ModelManifestValidator()
) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val modelRoot = File(appContext.filesDir, "rag/models")

    fun import(treeUri: Uri): ImportResult {
        require(DocumentsContract.isTreeUri(treeUri)) { "A document tree URI is required" }
        persistReadPermission(treeUri)
        modelRoot.mkdirs()
        val staging = File(modelRoot, ".staging-${UUID.randomUUID()}")
        require(staging.mkdirs()) { "Unable to create model staging directory" }

        return try {
            copyTree(treeUri, staging)
            val manifest = findManifest(staging)
            val bundle = manifest.parentFile ?: staging
            val validated = validator.validate(bundle, manifest)
            val destination = File(modelRoot, validated.manifestHash)
            val manifestRelativePath = manifest.relativeTo(staging).path
            if (destination.isDirectory) {
                val activeManifest = File(destination, manifestRelativePath)
                val activeBundle = activeManifest.parentFile ?: destination
                val active = validator.validate(activeBundle, activeManifest)
                staging.deleteRecursively()
                ImportResult.Reused(activeBundle, activeManifest, active.manifestHash)
            } else {
                require(staging.renameTo(destination)) { "Unable to activate imported model bundle" }
                val activeManifest = File(destination, manifestRelativePath)
                val activeBundle = activeManifest.parentFile ?: destination
                val active = validator.validate(activeBundle, activeManifest)
                ImportResult.Imported(activeBundle, activeManifest, active.manifestHash)
            }
        } catch (error: Throwable) {
            staging.deleteRecursively()
            ImportResult.Failed(readableError(error), error)
        }
    }

    private fun copyTree(treeUri: Uri, destination: File) {
        val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        copyChildren(treeUri, rootDocumentId, destination, 0)
    }

    private fun copyChildren(treeUri: Uri, parentId: String, destination: File, depth: Int) {
        require(depth <= MAX_DEPTH) { "Model bundle directory nesting is too deep" }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            ),
            null,
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(0)
                val displayName = cursor.getString(1)
                val mimeType = cursor.getString(2)
                val declaredSize = if (cursor.isNull(3)) null else cursor.getLong(3)
                validateName(displayName)
                val target = File(destination, displayName).canonicalFile
                require(target.toPath().startsWith(destination.canonicalFile.toPath())) {
                    "Model bundle entry escapes the destination"
                }
                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    require(target.mkdir() || target.isDirectory) { "Unable to create model directory" }
                    copyChildren(treeUri, documentId, target, depth + 1)
                } else {
                    require(declaredSize == null || declaredSize in 0..MAX_FILE_BYTES) {
                        "Model file is too large: $displayName"
                    }
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    copyFile(documentUri, target)
                }
            }
        } ?: throw IOException("Unable to enumerate the selected model directory")
    }

    private fun copyFile(source: Uri, destination: File) {
        var total = 0L
        resolver.openInputStream(source)?.use { input ->
            destination.outputStream().buffered().use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total = Math.addExact(total, count.toLong())
                    require(total <= MAX_FILE_BYTES) { "Model file exceeds the size limit" }
                    output.write(buffer, 0, count)
                }
            }
        } ?: throw IOException("Unable to read a model file")
    }

    private fun findManifest(root: File): File {
        val manifests = root.walkTopDown()
            .maxDepth(MAX_DEPTH)
            .filter { it.isFile && it.name == MANIFEST_FILE_NAME }
            .toList()
        require(manifests.size == 1) { "The model bundle must contain exactly one $MANIFEST_FILE_NAME" }
        return manifests.single()
    }

    private fun persistReadPermission(uri: Uri) {
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // The copy is performed immediately; temporary access is sufficient.
        }
    }

    private fun validateName(name: String) {
        require(name.isNotBlank() && name != "." && name != "..") { "Invalid model bundle entry" }
        require('/' !in name && '\\' !in name && '\u0000' !in name) { "Unsafe model bundle entry" }
    }

    private fun readableError(error: Throwable): String = when (error) {
        is SecurityException -> "Model directory permission was denied or revoked"
        is IOException -> error.message ?: "Model bundle could not be copied"
        is IllegalArgumentException -> error.message ?: "Model bundle validation failed"
        else -> "Model bundle import failed"
    }

    sealed class ImportResult {
        data class Imported(
            val bundleDirectory: File,
            val manifestFile: File,
            val manifestHash: String
        ) : ImportResult()

        data class Reused(
            val bundleDirectory: File,
            val manifestFile: File,
            val manifestHash: String
        ) : ImportResult()
        data class Failed(val reason: String, val cause: Throwable? = null) : ImportResult()
    }

    companion object {
        private const val MANIFEST_FILE_NAME = "manifest.json"
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_DEPTH = 16
        private const val MAX_FILE_BYTES = 8L * 1024L * 1024L * 1024L
    }
}
