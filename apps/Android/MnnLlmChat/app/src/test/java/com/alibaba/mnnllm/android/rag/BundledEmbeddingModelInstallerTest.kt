package com.alibaba.mnnllm.android.rag

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class BundledEmbeddingModelInstallerTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val root = File(context.filesDir, BundledEmbeddingModelInstaller.PRIVATE_MODEL_ROOT)

    @After fun cleanUp() { root.deleteRecursively() }

    @Test fun firstInstallCopiesAndValidatesAssets() {
        val assets = modelAssets("v1")
        val result = installer(assets).install()
        assertFalse(result.reused)
        assertTrue(result.configFile.isFile)
        assertEquals(sha256(assets.bytes("manifest.json")), result.manifestHash)
        assertEquals("model-v1", File(result.directory, "model.mnn").readText())
    }

    @Test fun repeatedInstallReusesValidatedDirectory() {
        val assets = modelAssets("v1")
        val first = installer(assets).install()
        val marker = File(first.directory, "reuse-marker").apply { writeText("keep") }
        val second = installer(assets).install()
        assertTrue(second.reused)
        assertEquals(first.directory, second.directory)
        assertTrue(marker.isFile)
    }

    @Test fun corruptedInstallationIsRepaired() {
        val assets = modelAssets("v1")
        val first = installer(assets).install()
        File(first.directory, "model.mnn").writeText("corrupt")
        val repaired = installer(assets).install()
        assertFalse(repaired.reused)
        assertEquals("model-v1", File(repaired.directory, "model.mnn").readText())
    }

    @Test fun insufficientStorageLeavesNoPublishedOrTemporaryDirectory() {
        val assets = modelAssets("v1")
        assertThrows(IllegalArgumentException::class.java) {
            installer(assets, available = 0L).install()
        }
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".install-") })
        assertTrue(root.listFiles().orEmpty().none { it.name.length == 64 })
    }

    @Test fun manifestUpgradeUsesNewContentAddressedDirectory() {
        val first = installer(modelAssets("v1")).install()
        val second = installer(modelAssets("v2")).install()
        assertNotEquals(first.manifestHash, second.manifestHash)
        assertNotEquals(first.directory, second.directory)
        assertTrue(first.directory.isDirectory)
        assertTrue(second.directory.isDirectory)
    }

    @Test fun interruptedTemporaryDirectoriesAreRemovedBeforeInstall() {
        val stale = File(root, ".install-stale").apply { mkdirs() }
        File(stale, "partial").writeText("partial")
        installer(modelAssets("v1")).install()
        assertFalse(stale.exists())
    }

    @Test fun rejectsNegativeStorageMargin() {
        assertThrows(IllegalArgumentException::class.java) {
            BundledEmbeddingModelInstaller(
                context,
                storageMarginBytes = -1,
                assets = modelAssets("v1")
            ).install()
        }
    }

    private fun installer(assets: FakeAssets, available: Long = Long.MAX_VALUE) =
        BundledEmbeddingModelInstaller(
            context = context,
            storageMarginBytes = 0,
            availableBytes = { available },
            assets = assets
        )

    private fun modelAssets(version: String): FakeAssets {
        val config = "{\"model\":\"model.mnn\"}".toByteArray()
        val model = "model-$version".toByteArray()
        val manifest = """
            {
              "schemaVersion":1,
              "bundleVersion":"$version",
              "models":[{
                "id":"embedding-$version",
                "role":"embedding",
                "format":"mnn",
                "files":[
                  {"relativePath":"config.json","sha256":"${sha256(config)}","sizeBytes":${config.size}},
                  {"relativePath":"model.mnn","sha256":"${sha256(model)}","sizeBytes":${model.size}}
                ],
                "inputs":[{"name":"input_ids","dtype":"int32","shape":[1,-1]}],
                "outputs":[{"name":"sentence_embeddings","dtype":"float32","shape":[1,512]}],
                "threads":4
              }]
            }
        """.trimIndent().toByteArray()
        val prefix = BundledEmbeddingModelInstaller.DEFAULT_ASSET_ROOT
        return FakeAssets(mapOf(
            "$prefix/config.json" to config,
            "$prefix/model.mnn" to model,
            "$prefix/manifest.json" to manifest
        ))
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private class FakeAssets(private val files: Map<String, ByteArray>) : BundledModelAssets {
        override fun list(path: String): List<String> {
            val prefix = "$path/"
            return files.keys.filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix).substringBefore('/') }.distinct()
        }
        override fun open(path: String): InputStream = ByteArrayInputStream(files.getValue(path))
        override fun length(path: String): Long = files.getValue(path).size.toLong()
        fun bytes(name: String): ByteArray = files.getValue("${BundledEmbeddingModelInstaller.DEFAULT_ASSET_ROOT}/$name")
    }
}
