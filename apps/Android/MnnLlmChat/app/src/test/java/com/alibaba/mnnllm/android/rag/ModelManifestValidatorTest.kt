package com.alibaba.mnnllm.android.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class ModelManifestValidatorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun validatesEmbeddingBundleAndFiles() {
        val bundle = temporaryFolder.newFolder("models")
        val config = File(bundle, "embedding/config.json").apply {
            parentFile?.mkdirs()
            writeText("{\"model\":\"embedding.mnn\"}")
        }
        val model = File(bundle, "embedding/embedding.mnn").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val manifest = writeManifest(bundle, config, model)

        val validated = ModelManifestValidator().validate(bundle, manifest)

        assertEquals(1, validated.manifest.schemaVersion)
        assertEquals("embedding-v1", validated.embeddingModel.id)
        assertEquals(2, validated.files.size)
        assertEquals(64, validated.manifestHash.length)
        assertTrue(validated.files.all { it.file.isFile })
    }

    @Test
    fun rejectsFileHashMismatch() {
        val bundle = temporaryFolder.newFolder("hash-mismatch")
        val config = File(bundle, "config.json").apply { writeText("{}") }
        val model = File(bundle, "embedding.mnn").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val manifest = writeManifest(bundle, config, model, modelHash = "0".repeat(64))

        assertThrows(IllegalArgumentException::class.java) {
            ModelManifestValidator().validate(bundle, manifest)
        }
    }

    @Test
    fun rejectsFileSizeMismatch() {
        val bundle = temporaryFolder.newFolder("size-mismatch")
        val config = File(bundle, "config.json").apply { writeText("{}") }
        val model = File(bundle, "embedding.mnn").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val manifest = writeManifest(bundle, config, model, modelSize = model.length() + 1)

        assertThrows(IllegalArgumentException::class.java) {
            ModelManifestValidator().validate(bundle, manifest)
        }
    }

    @Test
    fun rejectsPathTraversal() {
        val bundle = temporaryFolder.newFolder("traversal")
        val outside = temporaryFolder.newFile("outside.mnn").apply { writeBytes(byteArrayOf(1)) }
        val config = File(bundle, "config.json").apply { writeText("{}") }
        val manifest = File(bundle, "manifest.json")
        manifest.writeText(
            manifestJson(
                configPath = config.name,
                configHash = sha256(config),
                configSize = config.length(),
                modelPath = "../${outside.name}",
                modelHash = sha256(outside),
                modelSize = outside.length()
            )
        )

        assertThrows(IllegalArgumentException::class.java) {
            ModelManifestValidator().validate(bundle, manifest)
        }
    }

    @Test
    fun rejectsMissingSentenceEmbeddingOutput() {
        val bundle = temporaryFolder.newFolder("wrong-output")
        val config = File(bundle, "config.json").apply { writeText("{}") }
        val model = File(bundle, "embedding.mnn").apply { writeBytes(byteArrayOf(1)) }
        val manifest = File(bundle, "manifest.json")
        manifest.writeText(
            manifestJson(
                configPath = config.name,
                configHash = sha256(config),
                configSize = config.length(),
                modelPath = model.name,
                modelHash = sha256(model),
                modelSize = model.length(),
                outputName = "wrong_output"
            )
        )

        assertThrows(IllegalArgumentException::class.java) {
            ModelManifestValidator().validate(bundle, manifest)
        }
    }

    @Test
    fun rejectsMultipleEmbeddingModels() {
        val bundle = temporaryFolder.newFolder("multiple")
        val config = File(bundle, "config.json").apply { writeText("{}") }
        val model = File(bundle, "embedding.mnn").apply { writeBytes(byteArrayOf(1)) }
        val singleModel = modelObject(
            id = "embedding-v1",
            configPath = config.name,
            configHash = sha256(config),
            configSize = config.length(),
            modelPath = model.name,
            modelHash = sha256(model),
            modelSize = model.length()
        )
        val secondModel = singleModel.replace("embedding-v1", "embedding-v2")
        val manifest = File(bundle, "manifest.json").apply {
            writeText("{\"schemaVersion\":1,\"bundleVersion\":\"1\",\"models\":[$singleModel,$secondModel]}")
        }

        assertThrows(IllegalArgumentException::class.java) {
            ModelManifestValidator().validate(bundle, manifest)
        }
    }

    private fun writeManifest(
        bundle: File,
        config: File,
        model: File,
        modelHash: String = sha256(model),
        modelSize: Long = model.length()
    ): File {
        return File(bundle, "manifest.json").apply {
            writeText(
                manifestJson(
                    configPath = config.relativeTo(bundle).invariantSeparatorsPath,
                    configHash = sha256(config),
                    configSize = config.length(),
                    modelPath = model.relativeTo(bundle).invariantSeparatorsPath,
                    modelHash = modelHash,
                    modelSize = modelSize
                )
            )
        }
    }

    private fun manifestJson(
        configPath: String,
        configHash: String,
        configSize: Long,
        modelPath: String,
        modelHash: String,
        modelSize: Long,
        outputName: String = "sentence_embeddings"
    ): String {
        val model = modelObject(
            id = "embedding-v1",
            configPath = configPath,
            configHash = configHash,
            configSize = configSize,
            modelPath = modelPath,
            modelHash = modelHash,
            modelSize = modelSize,
            outputName = outputName
        )
        return "{\"schemaVersion\":1,\"bundleVersion\":\"1\",\"models\":[$model]}"
    }

    private fun modelObject(
        id: String,
        configPath: String,
        configHash: String,
        configSize: Long,
        modelPath: String,
        modelHash: String,
        modelSize: Long,
        outputName: String = "sentence_embeddings"
    ): String {
        return """
            {
              "id":"$id",
              "role":"embedding",
              "format":"mnn",
              "files":[
                {"relativePath":"$configPath","sha256":"$configHash","sizeBytes":$configSize},
                {"relativePath":"$modelPath","sha256":"$modelHash","sizeBytes":$modelSize}
              ],
              "inputs":[{"name":"input_ids","dtype":"int32","shape":[1,-1]}],
              "outputs":[{"name":"$outputName","dtype":"float32","shape":[1,384]}],
              "threads":4
            }
        """.trimIndent()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
