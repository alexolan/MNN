package com.alibaba.mnnllm.android.rag

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class BundledEmbeddingInferenceTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var engine: MnnEmbeddingEngine? = null

    @After
    fun tearDown() {
        engine?.close()
        engine = null
    }

    @Test
    fun bundledModelProducesFiniteNormalizedVectorsAndExpectedRanking() {
        val installation = BundledEmbeddingModelInstaller(context).install()
        val validated = ModelManifestValidator().validate(
            installation.directory,
            java.io.File(installation.directory, BundledEmbeddingModelInstaller.MANIFEST_FILE)
        )
        val output = validated.embeddingModel.outputs.single { it.name == "sentence_embeddings" }
        val dimensions = output.shape.last()
        assertEquals(512, dimensions)

        val active = MnnEmbeddingEngine(installation.configFile, dimensions).also { engine = it }
        val query = active.embed("北京是中国的首都")
        val related = active.embed("中国的首都是北京")
        val unrelated = active.embed("今天适合学习游泳")
        val english = active.embed("Beijing is the capital of China")
        val unicode = active.embed("你好，世界 🌏")

        listOf(query, related, unrelated, english, unicode).forEach { vector ->
            assertEquals(512, vector.size)
            assertTrue(vector.all(Float::isFinite))
            val norm = sqrt(vector.sumOf { value -> (value * value).toDouble() })
            assertTrue("Embedding norm must be finite and non-zero", norm.isFinite() && norm > 0.0)
        }
        assertTrue(cosine(query, related) > cosine(query, unrelated))
        assertTrue(cosine(query, english).isFinite())
    }

    @Test
    fun blankInputIsRejectedAndLongUnicodeInputRemainsValid() {
        val installation = BundledEmbeddingModelInstaller(context).install()
        val validated = ModelManifestValidator().validate(
            installation.directory,
            java.io.File(installation.directory, BundledEmbeddingModelInstaller.MANIFEST_FILE)
        )
        val dimensions = validated.embeddingModel.outputs.single { it.name == "sentence_embeddings" }.shape.last()
        val active = MnnEmbeddingEngine(installation.configFile, dimensions).also { engine = it }

        var rejected = false
        try {
            active.embed("  \n\t  ")
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)

        val longInput = buildString {
            repeat(700) { append("中文Unicode边界🙂测试") }
        }
        val vector = active.embed(longInput)
        assertEquals(512, vector.size)
        assertTrue(vector.all(Float::isFinite))
    }

    private fun cosine(left: FloatArray, right: FloatArray): Double {
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        for (index in left.indices) {
            dot += left[index] * right[index]
            leftNorm += left[index] * left[index]
            rightNorm += right[index] * right[index]
        }
        return dot / (sqrt(leftNorm) * sqrt(rightNorm))
    }
}
