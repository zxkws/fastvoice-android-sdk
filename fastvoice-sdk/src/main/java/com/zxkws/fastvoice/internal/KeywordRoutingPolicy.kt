package com.zxkws.fastvoice.internal

/**
 * Pure routing boundary for native KWS labels.
 *
 * WAKE and CONTROL are mutually exclusive. Playback suppresses only WAKE; CONTROL continues so a
 * user can stop or pause server audio.
 */
internal object KeywordRoutingPolicy {
    enum class Dispatch { WAKE, CONTROL, IGNORE }
    enum class ControlTarget { SERVER_PLAYBACK, LOCAL_PROMPT, NONE }

    fun shouldResetStream(previousEnabled: Boolean, enabled: Boolean): Boolean =
        previousEnabled && !enabled

    fun shouldResetPromptStream(
        previousPromptExpected: Boolean,
        promptExpected: Boolean,
        kwsSessionEnabled: Boolean,
    ): Boolean = previousPromptExpected && !promptExpected && !kwsSessionEnabled

    fun controlTarget(
        serverPlaybackGeneration: Int,
        playbackOrPromptExpected: Boolean,
        playbackActive: Boolean,
    ): ControlTarget = when {
        serverPlaybackGeneration >= 0 && (playbackOrPromptExpected || playbackActive) ->
            ControlTarget.SERVER_PLAYBACK
        serverPlaybackGeneration < 0 && playbackOrPromptExpected ->
            ControlTarget.LOCAL_PROMPT
        else -> ControlTarget.NONE
    }

    fun dispatch(
        route: LocalCommandSpotter.KeywordRoute?,
        playbackOrPromptExpected: Boolean,
        playbackActive: Boolean,
        wakeArmed: Boolean,
    ): Dispatch = when (route) {
        LocalCommandSpotter.KeywordRoute.WAKE -> {
            if (!playbackOrPromptExpected && !playbackActive && wakeArmed) {
                Dispatch.WAKE
            } else {
                Dispatch.IGNORE
            }
        }
        LocalCommandSpotter.KeywordRoute.CONTROL -> Dispatch.CONTROL
        LocalCommandSpotter.KeywordRoute.IGNORE, null -> Dispatch.IGNORE
    }
}
