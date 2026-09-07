package com.zxkws.fastvoice.internal

/**
 * Selects the same PCM source for live ASR uplink and pre-roll.
 *
 * While playback or its echo tail has a render reference, use WebRTC AEC3 output.
 * Without recent render, preserve the raw microphone frame. KWS has its own branch.
 */
internal object CaptureFramePolicy {
    fun uplink(rawMic: ByteArray, aecProcessed: ByteArray, hasRecentRender: Boolean): ByteArray =
        if (hasRecentRender) aecProcessed else rawMic
}
