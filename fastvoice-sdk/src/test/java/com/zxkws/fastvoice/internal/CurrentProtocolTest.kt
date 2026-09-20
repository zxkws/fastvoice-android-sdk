package com.zxkws.fastvoice.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentProtocolTest {
    private fun ready() = ReadyMessage(
        connectionId = "c1",
        controlTimeoutMs = 2_500,
    )

    @Test
    fun acceptsOnlyACompleteReady() {
        assertTrue(CurrentProtocol.acceptsReady(ready()))
        assertFalse(CurrentProtocol.acceptsReady(ready().copy(connectionId = "")))
        assertFalse(CurrentProtocol.acceptsReady(ready().copy(controlTimeoutMs = 0)))
    }

    @Test
    fun capturePreRollStaysBounded() {
        assertTrue(CurrentProtocol.MAX_CAPTURE_PRE_ROLL_MS == 1_800)
    }
}
