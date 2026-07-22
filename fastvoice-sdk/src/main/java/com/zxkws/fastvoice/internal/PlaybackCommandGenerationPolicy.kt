package com.zxkws.fastvoice.internal

/** Generation checks for commands that can terminate or replace a playback stream. */
internal object PlaybackCommandGenerationPolicy {
    /** Begin/stop/skip/replay may advance a generation but must never roll it back. */
    fun acceptsReset(incomingGeneration: Int, currentGeneration: Int): Boolean =
        incomingGeneration >= currentGeneration

    /** A stream end is meaningful only for the exact stream currently being played. */
    fun acceptsEnd(incomingGeneration: Int, currentGeneration: Int): Boolean =
        incomingGeneration == currentGeneration
}
