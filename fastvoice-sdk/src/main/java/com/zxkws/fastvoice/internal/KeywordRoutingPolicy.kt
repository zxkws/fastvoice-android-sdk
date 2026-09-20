package com.zxkws.fastvoice.internal

/**
 * Pure routing boundary for native KWS labels.
 *
 * Local KWS is only used for playback controls. Normal user speech goes through the always-open
 * server capture path and never needs a local wake label.
 */
internal object KeywordRoutingPolicy {
    enum class Dispatch { CONTROL, IGNORE }
    enum class ControlTarget { SERVER_PLAYBACK, NONE }

    fun controlTarget(
        serverPlaybackGeneration: Int,
        playbackOrPromptExpected: Boolean,
        playbackActive: Boolean,
    ): ControlTarget = when {
        serverPlaybackGeneration >= 0 && (playbackOrPromptExpected || playbackActive) ->
            ControlTarget.SERVER_PLAYBACK
        else -> ControlTarget.NONE
    }

    fun dispatch(
        route: LocalCommandSpotter.KeywordRoute?,
    ): Dispatch = when (route) {
        LocalCommandSpotter.KeywordRoute.CONTROL -> Dispatch.CONTROL
        LocalCommandSpotter.KeywordRoute.IGNORE, null -> Dispatch.IGNORE
    }
}
