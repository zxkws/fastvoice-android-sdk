package com.zxkws.fastvoice.internal

/** Allows exactly one success/failure terminal for one playback generation and local epoch. */
internal class PlaybackTerminalState {
    enum class FailureResult { RECORDED, ALREADY_FAILED, STALE_OR_FINISHED }

    private var generation = -1
    private var epoch = -1L
    private var terminal: String? = null
    private var failureReason: String? = null

    @Synchronized
    fun begin(generation: Int, epoch: Long) {
        this.generation = generation
        this.epoch = epoch
        terminal = null
        failureReason = null
    }

    @Synchronized
    fun invalidate(generation: Int, epoch: Long) {
        this.generation = generation
        this.epoch = epoch
        terminal = "stopped"
        failureReason = null
    }

    @Synchronized
    fun fail(generation: Int, epoch: Long, reason: String): FailureResult {
        if (generation != this.generation || epoch != this.epoch ||
            terminal == "finished" || terminal == "stopped"
        ) {
            return FailureResult.STALE_OR_FINISHED
        }
        if (terminal == "failed") return FailureResult.ALREADY_FAILED
        terminal = "failed"
        failureReason = reason.ifEmpty { "playback failed" }
        return FailureResult.RECORDED
    }

    @Synchronized
    fun finish(generation: Int, epoch: Long): Boolean {
        if (generation != this.generation || epoch != this.epoch || terminal != null) return false
        terminal = "finished"
        return true
    }

    @Synchronized
    fun failureReason(generation: Int, epoch: Long): String? =
        if (generation == this.generation && epoch == this.epoch && terminal == "failed") {
            failureReason
        } else {
            null
        }
}
