package com.zxkws.fastvoice.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayCachePolicyTest {
    @Test
    fun activeGenerationUsesItsCompletePendingBuffer() {
        assertEquals(
            listOf("current-complete"),
            ReplayCachePolicy.select(
                currentGenerationActive = true,
                current = emptyList(),
                pending = listOf("current-complete"),
                lastCompleted = listOf("previous"),
            ),
        )
    }

    @Test
    fun streamingGenerationUsesCurrentPartialBufferInsteadOfPreviousAnswer() {
        assertEquals(
            listOf("current-partial"),
            ReplayCachePolicy.select(
                currentGenerationActive = true,
                current = listOf("current-partial"),
                pending = emptyList(),
                lastCompleted = listOf("previous"),
            ),
        )
    }

    @Test
    fun idleGenerationUsesLastCompletedBuffer() {
        assertEquals(
            listOf("previous"),
            ReplayCachePolicy.select(
                currentGenerationActive = false,
                current = emptyList(),
                pending = emptyList(),
                lastCompleted = listOf("previous"),
            ),
        )
    }
}
