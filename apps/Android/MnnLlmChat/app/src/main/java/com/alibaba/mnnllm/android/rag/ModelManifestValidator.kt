package com.alibaba.mnnllm.android.rag

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Parses and validates a local RAG model manifest and every declared model file.
 * Paths are resolved relative to the authorized model bundle directory and may
 * not escape it through absolute paths, traversal, or symbolic links.
 */
class ModelManifestValidator(
    private val maxManifestBytes: Long = 1024L * 1024L
) {
    init {
        require(maxManifestBytes > 0) { "Manifest size limit must be positive" }
    }

    fun validate(bundleDirectory: File, manifestFile: File): ValidatedModelBundle {
        require(bundleDirectory.isDirectory) { "Model bundle directory does not exist" }
        require(manifestFile.isFile) { "Model manifest does not exist" }
        require(manifestFile.length() in 1..maxManifestBytes) { "Model manifest size is invalid" }

        val bundleRoot = bundleDirectory.canonicalFile
        require(manifestFile.canonicalFile.toPath().startsWith(bundleRoot.toPath())) {
            "Model manifest is outside the bundle directory"
        }

        val manifestBytes = manifestFile.readBytes()
        val manifestHash = sha256(manifestBytes)
        val manifest = parseManifest(JSONObject(manifestBytes.toString(Charsets.UTF_8)))
        require(manifest.schemaVersion == SUPPORTED_SCHEMA_VERSION) {
            "Unsupported model manifest schema version"
        }
        require(manifest.bundleVersion.isNotBlank()) { "Bundle version must not be blank" }
        require(manifest.models.isNotEmpty()) { "Model manifest does not contain models" }
        require(manifest.models.map(RagModelDefinition::id).distinct().size == manifest.models.size) {
            "Model ids must be unique"
        }
        require(manifest.models.count { it.role == ROLE_EMBEDDING } == 1) {
            "Manifest must contain exactly one embedding model"
        }

        val validatedFiles = manifest.models.flatMap { model ->
            validateModel(model)
            model.files.map { declared ->
                validateFile(bundleRoot, model, declared)
            }
        }
        return ValidatedModelBundle(bundleRoot, manifest, manifestHash, validatedFiles)
    }

    private fun validateModel(model: RagModelDefinition) {
        require(model.id.matches(ID_PATTERN)) { "Model id is invalid" }
        require(model.role in SUPPORTED_ROLES) { "Unsupported model role: ${model.role}" }
        require(model.format.equals("mnn", ignoreCase = true)) { "Unsupported model format" }
        require(model.files.isNotEmpty()) { "Model ${model.id} does not declare files" }
        require(model.files.map(RagModelFile::relativePath).distinct().size == model.files.size) {
            "Model ${model.id} contains duplicate file paths"
        }
        require(model.threads > 0) { "Model thread count must be positive" }
        model.inputs.forEach(::validateTensor)
        model.outputs.forEach(::validateTensor)
        if (model.role == ROLE_EMBEDDING) {
            require(model.outputs.any { it.name == "sentence_embeddings" }) {
                "Embedding model must expose sentence_embeddings"
            }
        }
    }

    private fun validateTensor(tensor: RagModelTensor) {
        require(tensor.name.isNotBlank()) { "Tensor name must not be blank" }
        require(tensor.dtype.isNotBlank()) { "Tensor dtype must not be blank" }
        require(tensor.shape.isNotEmpty() && tensor.shape.all { it == -1 || it > 0 }) {
            "Tensor shape is invalid"
        }
    }

    private fun validateFile(
        bundleRoot: File,
        model: RagModelDefinition,
        declared: RagModelFile
    ): ValidatedModelFile {
        require(declared.relativePath.isNotBlank()) { "Model file path must not be blank" }
        require(!File(declared.relativePath).isAbsolute) { "Absolute model file paths are forbidden" }
        require(declared.relativePath.replace('\\', '/').split('/').none { it == ".." }) {
            "Model file path traversal is forbidden"
        }
        require(declared.sha256.matches(SHA256_PATTERN)) { "Model file SHA-256 is invalid" }
        require(declared.sizeBytes >= 0) { "Model file size must not be negative" }

        val file = File(bundleRoot, declared.relativePath).canonicalFile
        require(file.toPath().startsWith(bundleRoot.toPath())) { "Model file escapes the bundle directory" }
        require(file.isFile) { "Required model file is missing: ${declared.relativePath}" }
        require(file.length() == declared.sizeBytes) { "Model file size mismatch: ${declared.relativePath}" }
        val actualHash = FileInputStream(file).use(::sha256)
        require(actualHash.equals(declared.sha256, ignoreCase = true)) {
            "Model file SHA-256 mismatch: ${declared.relativePath}"
        }
        return ValidatedModelFile(model.id, model.role, file, actualHash, declared.sizeBytes)
    }

    private fun parseManifest(json: JSONObject): RagModelManifest {
        val models = json.requiredArray("models").mapObjects { model ->
            RagModelDefinition(
                id = model.requiredString("id"),
                role = model.requiredString("role").lowercase(),
                format = model.requiredString("format"),
                files = model.requiredArray("files").mapObjects { file ->
                    RagModelFile(
                        relativePath = file.requiredString("relativePath"),
                        sha256 = file.requiredString("sha256").lowercase(),
                        sizeBytes = file.requiredLong("sizeBytes")
                    )
                },
                inputs = model.optJSONArray("inputs")?.mapObjects(::parseTensor).orEmpty(),
                outputs = model.optJSONArray("outputs")?.mapObjects(::parseTensor).orEmpty(),
                threads = model.optInt("threads", 4),
                options = model.optJSONObject("options")?.let(::stringMap).orEmpty()
            )
        }
        return RagModelManifest(
            schemaVersion = json.requiredInt("schemaVersion"),
            bundleVersion = json.requiredString("bundleVersion"),
            models = models
        )
    }

    private fun parseTensor(json: JSONObject): RagModelTensor {
        val shape = json.requiredArray("shape").let { array ->
            List(array.length()) { index -> array.getInt(index) }
        }
        return RagModelTensor(
            name = json.requiredString("name"),
            dtype = json.requiredString("dtype"),
            shape = shape,
            layout = json.optString("layout").takeIf(String::isNotBlank)
        )
    }

    private fun stringMap(json: JSONObject): Map<String, String> {
        return json.keys().asSequence().associateWith { key -> json.getString(key) }
    }

    private fun sha256(bytes: ByteArray): String = sha256(bytes.inputStream())

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        input.use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun JSONObject.requiredString(name: String): String {
        require(has(name) && !isNull(name)) { "Missing manifest field: $name" }
        return getString(name).also { require(it.isNotBlank()) { "Manifest field $name is blank" } }
    }

    private fun JSONObject.requiredInt(name: String): Int {
        require(has(name) && !isNull(name)) { "Missing manifest field: $name" }
        return getInt(name)
    }

    private fun JSONObject.requiredLong(name: String): Long {
        require(has(name) && !isNull(name)) { "Missing manifest field: $name" }
        return getLong(name)
    }

    private fun JSONObject.requiredArray(name: String): JSONArray {
        require(has(name) && !isNull(name)) { "Missing manifest field: $name" }
        return getJSONArray(name)
    }

    private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> {
        return List(length()) { index -> transform(getJSONObject(index)) }
    }

    data class ValidatedModelBundle(
        val directory: File,
        val manifest: RagModelManifest,
        val manifestHash: String,
        val files: List<ValidatedModelFile>
    ) {
        val embeddingModel: RagModelDefinition
            get() = manifest.models.single { it.role == ROLE_EMBEDDING }
    }

    data class ValidatedModelFile(
        val modelId: String,
        val role: String,
        val file: File,
        val sha256: String,
        val sizeBytes: Long
    )

    companion object {
        const val ROLE_EMBEDDING = "embedding"
        private const val SUPPORTED_SCHEMA_VERSION = 1
        private val SUPPORTED_ROLES = setOf(ROLE_EMBEDDING, "ocr", "reranker")
        private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
    }
}
