package com.zxkws.fastvoice.internal

/**
 * Holds a turn-error prompt until the server proves that its own fallback audio either started or
 * failed. Waiting for a terminal state prevents Android TTS from speaking over normal server TTS.
 */
internal class FallbackPromptGate {
    private var pendingPrompt: String? = null

    @Synchronized
    fun onTurnError(prompt: String?) {
        pendingPrompt = prompt?.takeIf(String::isNotBlank)
    }

    @Synchronized
    fun onServerAudio() {
        pendingPrompt = null
    }

    @Synchronized
    fun takeAfterTerminalState(state: String): String? {
        if (state != "sleeping" && state != "listening") return null
        val prompt = pendingPrompt
        pendingPrompt = null
        return prompt
    }

    @Synchronized
    fun clear() {
        pendingPrompt = null
    }
}
