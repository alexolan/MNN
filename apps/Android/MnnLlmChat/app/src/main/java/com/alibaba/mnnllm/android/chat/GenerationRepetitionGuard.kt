// Copyright (c) 2026 Alibaba Group Holding Limited All rights reserved.
package com.alibaba.mnnllm.android.chat

/**
 * Detects pathological loops in streamed LLM output without blocking legitimate
 * short repetitions. Detection starts only after enough output has accumulated
 * and requires the same trailing unit to appear several consecutive times.
 */
class GenerationRepetitionGuard(
    private val minimumOutputChars: Int = 96,
    private val minimumUnitChars: Int = 8,
    private val maximumUnitChars: Int = 160,
    private val requiredRepeats: Int = 5
) {
    init {
        require(minimumOutputChars > 0)
        require(minimumUnitChars > 0)
        require(maximumUnitChars >= minimumUnitChars)
        require(requiredRepeats >= 3)
    }

    data class Detection(
        val repeatedText: String,
        val repeatCount: Int
    )

    fun detect(output: CharSequence): Detection? {
        if (output.length < minimumOutputChars) return null

        val maxUnit = minOf(maximumUnitChars, output.length / requiredRepeats)
        if (maxUnit < minimumUnitChars) return null

        for (unitLength in minimumUnitChars..maxUnit) {
            val repeatedLength = unitLength * requiredRepeats
            if (repeatedLength > output.length) break

            val unitStart = output.length - unitLength
            val unit = output.subSequence(unitStart, output.length).toString()
            if (unit.isBlank() || unit.all { it.isWhitespace() || it.isLetterOrDigit().not() }) {
                continue
            }

            var matches = true
            for (repeatIndex in 2..requiredRepeats) {
                val start = output.length - unitLength * repeatIndex
                val end = start + unitLength
                if (!output.regionMatches(start, unit, 0, unitLength, ignoreCase = false)) {
                    matches = false
                    break
                }
            }
            if (matches) {
                return Detection(unit, requiredRepeats)
            }
        }
        return null
    }

    private fun CharSequence.regionMatches(
        thisOffset: Int,
        other: String,
        otherOffset: Int,
        length: Int,
        ignoreCase: Boolean
    ): Boolean {
        if (thisOffset < 0 || otherOffset < 0 ||
            thisOffset + length > this.length || otherOffset + length > other.length
        ) {
            return false
        }
        for (index in 0 until length) {
            val left = this[thisOffset + index]
            val right = other[otherOffset + index]
            if (left == right) continue
            if (!ignoreCase || !left.equals(right, ignoreCase = true)) return false
        }
        return true
    }
}
