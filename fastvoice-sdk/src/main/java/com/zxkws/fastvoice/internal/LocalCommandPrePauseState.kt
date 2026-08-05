package com.zxkws.fastvoice.internal

/** Owns the reversible pause caused by one locally detected command candidate. */
internal class LocalCommandPrePauseState {
    enum class Outcome { IGNORED, ACCEPTED_HOLD, CLEARED, RESUME }

    private var candidateId: String? = null
    private var generation = -1
    private var epoch = -1L
    private var accepted = false

    @Synchronized
    fun begin(id: String, generation: Int, epoch: Long): Boolean {
        if (id.isEmpty() || candidateId != null) return false
        candidateId = id
        this.generation = generation
        this.epoch = epoch
        accepted = false
        return true
    }

    @Synchronized
    fun decide(
        id: String,
        generation: Int,
        decision: String,
        currentGeneration: Int,
        currentEpoch: Long,
        serverPaused: Boolean,
    ): Outcome {
        if (!matches(id, generation) || accepted) return Outcome.IGNORED
        if (currentGeneration != this.generation || currentEpoch != epoch) {
            clearInternal()
            return Outcome.CLEARED
        }
        if (decision == "accepted") {
            accepted = true
            return Outcome.ACCEPTED_HOLD
        }
        if (decision != "rejected") return Outcome.IGNORED
        clearInternal()
        return if (serverPaused) Outcome.CLEARED else Outcome.RESUME
    }

    @Synchronized
    fun timeout(
        id: String,
        generation: Int,
        epoch: Long,
        currentGeneration: Int,
        currentEpoch: Long,
        serverPaused: Boolean,
        requireAccepted: Boolean,
    ): Outcome {
        if (!matches(id, generation) || this.epoch != epoch || accepted != requireAccepted) {
            return Outcome.IGNORED
        }
        val current = currentGeneration == this.generation && currentEpoch == this.epoch
        clearInternal()
        return if (!current || serverPaused) Outcome.CLEARED else Outcome.RESUME
    }

    @Synchronized
    fun clear(): Boolean {
        if (candidateId == null) return false
        clearInternal()
        return true
    }

    private fun matches(id: String, generation: Int): Boolean =
        candidateId == id && this.generation == generation

    private fun clearInternal() {
        candidateId = null
        generation = -1
        epoch = -1L
        accepted = false
    }
}
