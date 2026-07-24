package com.zxkws.fastvoice.internal

internal object CapturePreRollPolicy {
    fun frameCount(
        requestedMs: Int,
        wakeCapturePending: Boolean,
        frameMs: Int = CurrentProtocol.FRAME_MS,
        maxMs: Int = CurrentProtocol.MAX_CAPTURE_PRE_ROLL_MS,
    ): Int {
        require(requestedMs >= 0) { "requestedMs must be non-negative" }
        val bounded = if (wakeCapturePending) maxMs else requestedMs.coerceAtMost(maxMs)
        return if (bounded == 0) 0 else (bounded + frameMs - 1) / frameMs
    }

    fun startIndex(bufferedFrames: Int, requestedFrames: Int): Int =
        (bufferedFrames - requestedFrames).coerceAtLeast(0)
}
