package com.zxkws.fastvoice.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentProtocolTest {
    private val supportedWakeWords = setOf("咘嘀", "你好咘嘀")

    private fun ready() = ReadyMessage(
        connectionId = "c1",
        wakeWords = listOf("咘嘀"),
        controlTimeoutMs = 2_500,
    )

    @Test
    fun acceptsOnlyACompleteReady() {
        assertTrue(CurrentProtocol.acceptsReady(ready(), supportedWakeWords))
        assertFalse(
            CurrentProtocol.acceptsReady(ready().copy(connectionId = ""), supportedWakeWords),
        )
        assertFalse(
            CurrentProtocol.acceptsReady(ready().copy(controlTimeoutMs = 0), supportedWakeWords),
        )
        assertFalse(
            CurrentProtocol.acceptsReady(ready().copy(wakeWords = listOf("未知")), supportedWakeWords),
        )
        assertTrue(
            CurrentProtocol.acceptsReady(ready().copy(wakeWords = emptyList()), supportedWakeWords),
        )
    }

    @Test
    fun fixedProtocolHasNoReplayOrSkipAndUsesTheFullWakeRing() {
        assertFalse("playback.replay" in CurrentProtocol.CONTROL_ACTIONS)
        assertFalse("playback.skip" in CurrentProtocol.CONTROL_ACTIONS)
        assertTrue("capture.start" in CurrentProtocol.CONTROL_ACTIONS)
        assertTrue(CurrentProtocol.MAX_CAPTURE_PRE_ROLL_MS == 1_800)
    }
}
