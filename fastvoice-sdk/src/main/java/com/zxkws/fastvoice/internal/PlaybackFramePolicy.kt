package com.zxkws.fastvoice.internal

/** Fixed-frame and terminal validation for one server playback stream. */
internal object PlaybackFramePolicy {
    fun acceptsDecodedFrame(samples: Int, expectedSamples: Int): Boolean =
        samples == expectedSamples

    fun canFinish(bytesWritten: Long): Boolean = bytesWritten > 0L
}
