package com.zxkws.fastvoice.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentProtocolTest {
    private val supportedWakeWords = setOf("布丁", "你好布丁")

    private fun currentHello() = ServerHelloCapabilities(
        encoding = "opus",
        inputRate = 16_000,
        outputRate = 48_000,
        frameMs = 20,
        wakeEnabled = true,
        wakeWords = listOf("布丁", "你好布丁"),
        controlEnabled = true,
        controlProtocol = "commands-v1",
        controlActions = CurrentProtocol.CONTROL_ACTIONS,
        localCommandEnabled = true,
        candidateMessage = "local_command_candidate",
        decisionMessage = "local_command_decision",
        confirmTimeoutMs = 1_800,
        generationBound = true,
    )

    @Test
    fun acceptsOnlyTheCurrentCompleteHello() {
        assertTrue(CurrentProtocol.acceptsHello(currentHello(), supportedWakeWords))
        assertFalse(CurrentProtocol.acceptsHello(currentHello().copy(encoding = "pcm"), supportedWakeWords))
        assertFalse(CurrentProtocol.acceptsHello(currentHello().copy(inputRate = 8_000), supportedWakeWords))
        assertFalse(CurrentProtocol.acceptsHello(currentHello().copy(outputRate = 24_000), supportedWakeWords))
        assertFalse(CurrentProtocol.acceptsHello(currentHello().copy(frameMs = 40), supportedWakeWords))
    }

    @Test
    fun rejectsIncompleteControlProtocol() {
        assertFalse(CurrentProtocol.acceptsHello(currentHello().copy(controlEnabled = false), supportedWakeWords))
        assertFalse(CurrentProtocol.acceptsHello(currentHello().copy(controlProtocol = "commands-v2"), supportedWakeWords))
        assertFalse(
            CurrentProtocol.acceptsHello(
                currentHello().copy(controlActions = CurrentProtocol.CONTROL_ACTIONS.dropLast(1)),
                supportedWakeWords,
            ),
        )
    }

    @Test
    fun rejectsIncompleteLocalCommandProtocol() {
        assertFalse(CurrentProtocol.acceptsHello(currentHello().copy(localCommandEnabled = false), supportedWakeWords))
        assertFalse(CurrentProtocol.acceptsHello(currentHello().copy(candidateMessage = "candidate"), supportedWakeWords))
        assertFalse(CurrentProtocol.acceptsHello(currentHello().copy(decisionMessage = "decision"), supportedWakeWords))
        assertFalse(CurrentProtocol.acceptsHello(currentHello().copy(confirmTimeoutMs = 0), supportedWakeWords))
        assertFalse(CurrentProtocol.acceptsHello(currentHello().copy(generationBound = false), supportedWakeWords))
    }

    @Test
    fun rejectsInvalidWakeNegotiation() {
        assertFalse(CurrentProtocol.acceptsHello(currentHello().copy(wakeWords = null), supportedWakeWords))
        assertFalse(CurrentProtocol.acceptsHello(currentHello().copy(wakeWords = emptyList()), supportedWakeWords))
        assertFalse(CurrentProtocol.acceptsHello(currentHello().copy(wakeWords = listOf("未知")), supportedWakeWords))
        assertTrue(
            CurrentProtocol.acceptsHello(
                currentHello().copy(wakeEnabled = false, wakeWords = emptyList()),
                supportedWakeWords,
            ),
        )
    }

    @Test
    fun rejectsNonHelloMessagesBeforeNegotiation() {
        assertTrue(CurrentProtocol.acceptsBeforeHello("hello"))
        assertFalse(CurrentProtocol.acceptsBeforeHello("command"))
        assertFalse(CurrentProtocol.acceptsBeforeHello("state"))
    }
}
