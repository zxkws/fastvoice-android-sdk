package com.zxkws.fastvoice.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentProtocolTest {
    private val supportedWakeWords = setOf("布丁", "你好布丁")

    private fun ready() = ReadyMessage(
        connectionId = "c1",
        wakeWords = listOf("布丁"),
        controlTimeoutMs = 2_500,
    )

    @Test
    fun acceptsOnlyACompleteReady() {
        assertTrue(CurrentProtocol.acceptsReady(ready(), supportedWakeWords))
        assertTrue(CurrentProtocol.acceptsReadyFields(CurrentProtocol.READY_FIELDS))
        assertFalse(
            CurrentProtocol.acceptsReadyFields(CurrentProtocol.READY_FIELDS + "unexpected"),
        )
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
    fun onlyReadyIsLegalBeforeTheConnectionIsUsable() {
        assertTrue(CurrentProtocol.acceptsBeforeReady("ready"))
        assertFalse(CurrentProtocol.acceptsBeforeReady("state"))
        assertFalse(CurrentProtocol.acceptsBeforeReady("playback.start"))
    }

    @Test
    fun fixedProtocolHasNoReplayOrSkipAndUsesTheFullWakeRing() {
        assertFalse("playback.replay" in CurrentProtocol.CONTROL_ACTIONS)
        assertFalse("playback.skip" in CurrentProtocol.CONTROL_ACTIONS)
        assertTrue("capture.start" in CurrentProtocol.CONTROL_ACTIONS)
        assertTrue(CurrentProtocol.MAX_CAPTURE_PRE_ROLL_MS == 1_800)
    }
}
