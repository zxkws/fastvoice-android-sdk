package com.zxkws.fastvoice.internal

/** Remembers the latest server uplink request while a local recovery prompt forces capture off. */
internal class TurnErrorUplinkState {
    private var serverRequested = false

    @Synchronized
    fun reset() {
        serverRequested = false
    }

    @Synchronized
    fun onServerCommand(enabled: Boolean, localPromptPlaying: Boolean): Boolean {
        serverRequested = enabled
        return enabled && !localPromptPlaying
    }

    @Synchronized
    fun afterTerminal(): Boolean = serverRequested
}
