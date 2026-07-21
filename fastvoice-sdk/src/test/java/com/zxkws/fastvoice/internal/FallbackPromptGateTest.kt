package com.zxkws.fastvoice.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FallbackPromptGateTest {
    @Test
    fun serverAudioSuppressesTheLocalFallback() {
        val gate = FallbackPromptGate()
        gate.onTurnError("网络有点忙，请稍后再试")

        gate.onServerAudio()

        assertNull(gate.takeAfterTerminalState("sleeping"))
    }

    @Test
    fun promptIsReleasedOnlyAfterARecoveryTerminalState() {
        val gate = FallbackPromptGate()
        gate.onTurnError("请再说一遍")

        assertNull(gate.takeAfterTerminalState("generating"))
        assertEquals("请再说一遍", gate.takeAfterTerminalState("listening"))
        assertNull(gate.takeAfterTerminalState("listening"))
    }

    @Test
    fun blankOrClearedPromptsNeverPlay() {
        val gate = FallbackPromptGate()
        gate.onTurnError("  ")
        assertNull(gate.takeAfterTerminalState("sleeping"))

        gate.onTurnError("请稍后再试")
        gate.clear()
        assertNull(gate.takeAfterTerminalState("sleeping"))
    }
}
