package com.zxkws.fastvoice.internal

/** Correlates one server recovery and prevents duplicate or overlapping local fallback prompts. */
internal class FallbackPromptGate {
    enum class Action { NONE, PLAY_LOCAL, STOP_LOCAL, APPLY_FINAL_STATE }

    data class Transition(
        val action: Action,
        val recoveryId: String? = null,
        val prompt: String? = null,
        val finalState: String? = null,
    )

    private var recoveryId: String? = null
    private var prompt: String? = null
    private var localPlaying = false
    private var serverAudioObserved = false
    private val closedRecoveryIds = linkedSetOf<String>()

    @Synchronized
    fun onTurnError(recoveryId: String?, prompt: String?): Transition {
        if (recoveryId.isNullOrEmpty() || recoveryId == this.recoveryId ||
            recoveryId in closedRecoveryIds
        ) {
            return Transition(Action.NONE)
        }
        val stopPrevious = localPlaying
        rememberCurrentRecovery()
        this.recoveryId = recoveryId
        this.prompt = prompt
        localPlaying = false
        serverAudioObserved = false
        return Transition(if (stopPrevious) Action.STOP_LOCAL else Action.NONE)
    }

    @Synchronized
    fun onServerAudio(): Transition {
        if (recoveryId == null) return Transition(Action.NONE)
        serverAudioObserved = true
        if (!localPlaying) return Transition(Action.NONE)
        localPlaying = false
        return Transition(Action.STOP_LOCAL)
    }

    @Synchronized
    fun onTerminal(
        recoveryId: String,
        serverAudioStarted: Boolean,
        finalState: String,
    ): Transition {
        if (this.recoveryId != recoveryId) return Transition(Action.NONE)
        if (serverAudioStarted || serverAudioObserved) {
            val stopLocal = localPlaying
            val completedId = this.recoveryId
            clearInternal()
            return Transition(
                if (stopLocal) Action.STOP_LOCAL else Action.APPLY_FINAL_STATE,
                recoveryId = completedId,
                finalState = finalState,
            )
        }
        if (localPlaying) return Transition(Action.NONE)
        val currentPrompt = prompt
        if (currentPrompt.isNullOrEmpty()) {
            val completedId = this.recoveryId
            clearInternal()
            return Transition(
                Action.APPLY_FINAL_STATE,
                recoveryId = completedId,
                finalState = finalState,
            )
        }
        localPlaying = true
        return Transition(Action.PLAY_LOCAL, this.recoveryId, currentPrompt, finalState)
    }

    @Synchronized
    fun finishLocal(recoveryId: String): Boolean {
        if (!localPlaying || this.recoveryId != recoveryId) return false
        clearInternal()
        return true
    }

    @Synchronized
    fun clear(): Transition {
        val stopLocal = localPlaying
        clearInternal()
        return Transition(if (stopLocal) Action.STOP_LOCAL else Action.NONE)
    }

    @Synchronized
    fun resetSession(): Transition {
        val stopLocal = localPlaying
        recoveryId = null
        prompt = null
        localPlaying = false
        serverAudioObserved = false
        closedRecoveryIds.clear()
        return Transition(if (stopLocal) Action.STOP_LOCAL else Action.NONE)
    }

    private fun clearInternal() {
        rememberCurrentRecovery()
        recoveryId = null
        prompt = null
        localPlaying = false
        serverAudioObserved = false
    }

    private fun rememberCurrentRecovery() {
        val current = recoveryId ?: return
        if (!closedRecoveryIds.add(current)) return
        while (closedRecoveryIds.size > CLOSED_RECOVERY_ID_LIMIT) {
            closedRecoveryIds.remove(closedRecoveryIds.first())
        }
    }

    private companion object {
        const val CLOSED_RECOVERY_ID_LIMIT = 32
    }
}
