package com.zxkws.fastvoice.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackPromptGateTest {
    @Test
    fun serverAudioSuppressesTheLocalFallback() {
        val gate = FallbackPromptGate()
        gate.onTurnError("r1", "网络有点忙，请稍后再试")

        gate.onServerAudio()

        assertEquals(
            FallbackPromptGate.Action.APPLY_FINAL_STATE,
            gate.onTerminal("r1", false, "sleeping").action,
        )
    }

    @Test
    fun zeroAudioTerminalReleasesOriginalPromptExactlyOnce() {
        val gate = FallbackPromptGate()
        gate.onTurnError("r2", "  请再说一遍\n")

        val transition = gate.onTerminal("r2", false, "listening")
        assertEquals(FallbackPromptGate.Action.PLAY_LOCAL, transition.action)
        assertEquals("  请再说一遍\n", transition.prompt)
        assertEquals("listening", transition.finalState)
        assertEquals(
            FallbackPromptGate.Action.NONE,
            gate.onTerminal("r2", false, "listening").action,
        )
        assertTrue(gate.finishLocal("r2"))
        assertFalse(gate.finishLocal("r2"))
    }

    @Test
    fun staleTerminalCannotClearNewRecoveryAndRecentDuplicateCannotReplay() {
        val gate = FallbackPromptGate()
        gate.onTurnError("old", "旧提示")
        gate.onTurnError("new", "新提示")

        assertEquals(
            FallbackPromptGate.Action.NONE,
            gate.onTerminal("old", false, "sleeping").action,
        )
        assertEquals(
            FallbackPromptGate.Action.PLAY_LOCAL,
            gate.onTerminal("new", false, "sleeping").action,
        )
        assertTrue(gate.finishLocal("new"))
        assertEquals(FallbackPromptGate.Action.NONE, gate.onTurnError("new", "重复").action)
    }

    @Test
    fun blankPromptAppliesTerminalWithoutInventingSpeech() {
        val gate = FallbackPromptGate()
        gate.onTurnError("r3", "")

        assertEquals(
            FallbackPromptGate.Action.APPLY_FINAL_STATE,
            gate.onTerminal("r3", false, "sleeping").action,
        )
    }
}
