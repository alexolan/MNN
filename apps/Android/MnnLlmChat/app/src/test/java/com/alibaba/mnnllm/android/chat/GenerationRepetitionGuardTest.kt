package com.alibaba.mnnllm.android.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GenerationRepetitionGuardTest {

    @Test
    fun detectsLongConsecutiveSentenceLoop() {
        val guard = GenerationRepetitionGuard()
        val sentence = "这是一段异常重复输出，需要自动停止。"
        val detection = guard.detect("正常开头内容。" + sentence.repeat(6))

        assertNotNull(detection)
        assertEquals(5, detection!!.repeatCount)
    }

    @Test
    fun ignoresNormalShortRepetition() {
        val guard = GenerationRepetitionGuard()

        assertNull(guard.detect("哈哈哈，谢谢谢谢，这是正常的简短表达。"))
    }

    @Test
    fun ignoresLongNonRepeatingResponse() {
        val guard = GenerationRepetitionGuard()
        val response = buildString {
            repeat(12) { index ->
                append("第")
                append(index)
                append("段内容各不相同，用于验证正常长回答不会被误判。")
            }
        }

        assertNull(guard.detect(response))
    }

    @Test
    fun detectsAsciiPhraseLoop() {
        val guard = GenerationRepetitionGuard()
        val phrase = "The same generated phrase repeats. "

        assertNotNull(guard.detect("A sufficiently long response prefix. " + phrase.repeat(5)))
    }
}
