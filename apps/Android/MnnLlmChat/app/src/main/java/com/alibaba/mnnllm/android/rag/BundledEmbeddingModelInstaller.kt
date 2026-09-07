package com.alibaba.mnnllm.android.rag

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID



internal interface BundledModelAssets {
    fun list(path: String): List<String>
    fun open(path: String): java.io.InputStream
    fun length(path: String): Long
}

private class AndroidBundledModelAssets(context: Context) : BundledModelAssets {
    private val manager = context.assets
    override fun list(path: String): List<String> = manager.list(path)?.toList().orEmpty()
    override fun open(path: String): java.io.InputStream = manager.open(path)
    override fun length(path: String): Long = manager.openFd(path).use { it.length }
}

/** Installs the APK-bundled embedding model into an atomic, content-addressed private directory. */
class BundledEmbeddingModelInstaller(
    private val context: Context,
    private val validator: ModelManifestValidator = ModelManifestValidator(),
    private val assetRoot: String = DEFAULT_ASSET_ROOT,
    private val storageMarginBytes: Long = DEFAULT_STORAGE_MARGIN_BYTES,
    private val availableBytes: (File) -> Long = { it.usableSpace },
    private val assets: BundledModelAssets = AndroidBundledModelAssets(context)
) {
    data class Installation(
        val directory: File,
        val manifestHash: String,
        val configFile: File,
        val reused: Boolean
    )

    @Synchronized
    fun install(): Installation {
        require(storageMarginBytes >= 0) { "Storage margin must not be negative" }
        val manifestBytes = readAsset(MANIFEST_FILE)
        val manifestHash = sha256(manifestBytes)
        val modelRoot = File(context.filesDir, PRIVATE_MODEL_ROOT)
        require(modelRoot.exists() || modelRoot.mkdirs()) { "Cannot create bundled model root" }
        cleanupInterruptedInstalls(modelRoot)

        val destination = File(modelRoot, manifestHash)
        validateReusable(destination)?.let { return it }
        if (destination.exists()) require(destination.deleteRecursively()) {
            "Cannot remove invalid bundled model installation"
        }

        val assetFiles = listAssetFiles()
        require(assetFiles.contains(MANIFEST_FILE)) { "Bundled model manifest is missing" }
        val requiredBytes = assetFiles.sumOf { assetLength(it) }
        require(availableBytes(modelRoot) >= requiredBytes + storageMarginBytes) {
            "Insufficient storage for bundled embedding model"
        }

        val temporary = File(modelRoot, ".install-${UUID.randomUUID()}")
        require(temporary.mkdir()) { "Cannot create temporary model directory" }
        try {
            assetFiles.forEach { relativePath ->
                val target = safeTarget(temporary, relativePath)
                require(target.parentFile?.let { it.exists() || it.mkdirs() } == true) {
                    "Cannot create model directory"
                }
                assets.open("$assetRoot/$relativePath").use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output, COPY_BUFFER_BYTES) }
                }
            }
            val validated = validator.validate(temporary, File(temporary, MANIFEST_FILE))
            require(validated.manifestHash == manifestHash) { "Bundled manifest changed during installation" }
            require(temporary.renameTo(destination)) { "Cannot atomically publish bundled model" }
            val installed = validator.validate(destination, File(destination, MANIFEST_FILE))
            return result(destination, installed.manifestHash, reused = false)
        } catch (error: Throwable) {
            temporary.deleteRecursively()
            throw error
        }
    }

    private fun validateReusable(directory: File): Installation? {
        if (!directory.isDirectory) return null
        return try {
            val validated = validator.validate(directory, File(directory, MANIFEST_FILE))
            result(directory, validated.manifestHash, reused = true)
        } catch (_: Exception) {
            null
        }
    }

    private fun result(directory: File, hash: String, reused: Boolean): Installation {
        val config = safeTarget(directory, CONFIG_FILE)
        require(config.isFile) { "Bundled embedding config is missing" }
        return Installation(directory, hash, config, reused)
    }

    private fun cleanupInterruptedInstalls(root: File) {
        root.listFiles()?.filter { it.name.startsWith(".install-") }?.forEach {
            require(it.deleteRecursively()) { "Cannot clean interrupted model installation" }
        }
    }

    private fun listAssetFiles(): List<String> {
        val names = assets.list(assetRoot).sorted()
        require(names.isNotEmpty()) { "Bundled embedding model assets are missing" }
        names.forEach { name ->
            require(name.isNotBlank() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\')) {
                "Unsafe bundled model asset path"
            }
            require(assets.list("$assetRoot/$name").isEmpty()) {
                "Nested bundled model asset directories are not supported"
            }
        }
        return names
    }

    private fun readAsset(relativePath: String): ByteArray =
        assets.open("$assetRoot/$relativePath").use { it.readBytes() }

    private fun assetLength(relativePath: String): Long =
        assets.length("$assetRoot/$relativePath")

    private fun safeTarget(root: File, relativePath: String): File {
        require(relativePath.isNotBlank() && !File(relativePath).isAbsolute) { "Invalid model path" }
        require(relativePath.replace('\\', '/').split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "Unsafe model path"
        }
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, relativePath).canonicalFile
        require(target.toPath().startsWith(canonicalRoot.toPath())) { "Model path escapes installation root" }
        return target
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val DEFAULT_ASSET_ROOT = "rag/models/bge-small-zh-v1.5"
        const val PRIVATE_MODEL_ROOT = "rag/models/bge-small-zh-v1.5"
        const val MANIFEST_FILE = "manifest.json"
        const val CONFIG_FILE = "config.json"
        const val DEFAULT_STORAGE_MARGIN_BYTES = 16L * 1024L * 1024L
        private const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
