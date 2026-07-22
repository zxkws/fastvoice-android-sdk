package com.zxkws.fastvoice.internal

internal data class ServerHelloCapabilities(
    val encoding: String?,
    val inputRate: Long?,
    val outputRate: Long?,
    val frameMs: Long?,
    val wakeEnabled: Boolean?,
    val wakeWords: List<String>?,
    val controlEnabled: Boolean?,
    val controlProtocol: String?,
    val controlActions: List<String>?,
    val localCommandEnabled: Boolean?,
    val candidateMessage: String?,
    val decisionMessage: String?,
    val confirmTimeoutMs: Long?,
    val generationBound: Boolean?,
)

/** The one wire contract implemented by this pre-release SDK. */
internal object CurrentProtocol {
    const val INPUT_RATE = 16_000
    const val OUTPUT_RATE = 48_000
    const val FRAME_MS = 20

    val CONTROL_ACTIONS = listOf(
        "playback.begin",
        "playback.end",
        "playback.stop",
        "playback.pause",
        "playback.resume",
        "playback.replay",
        "playback.skip",
        "audio.volume.adjust",
        "uplink.start",
        "uplink.stop",
    )

    val PLAYBACK_ACTIONS = setOf(
        "playback.begin",
        "playback.end",
        "playback.stop",
        "playback.pause",
        "playback.resume",
        "playback.replay",
        "playback.skip",
    )

    fun acceptsHello(
        hello: ServerHelloCapabilities,
        supportedWakeWords: Set<String>,
    ): Boolean {
        val wakeWords = hello.wakeWords ?: return false
        val actions = hello.controlActions ?: return false
        return hello.encoding == "opus" &&
            hello.inputRate == INPUT_RATE.toLong() &&
            hello.outputRate == OUTPUT_RATE.toLong() &&
            hello.frameMs == FRAME_MS.toLong() &&
            hello.wakeEnabled != null &&
            (!hello.wakeEnabled || wakeWords.isNotEmpty()) &&
            wakeWords.none(String::isBlank) &&
            wakeWords.distinct().size == wakeWords.size &&
            wakeWords.all(supportedWakeWords::contains) &&
            hello.controlEnabled == true &&
            hello.controlProtocol == "commands-v1" &&
            actions == CONTROL_ACTIONS &&
            hello.localCommandEnabled == true &&
            hello.candidateMessage == "local_command_candidate" &&
            hello.decisionMessage == "local_command_decision" &&
            hello.confirmTimeoutMs?.let { it > 0L } == true &&
            hello.generationBound == true
    }

    fun acceptsBeforeHello(messageType: String): Boolean = messageType == "hello"
}
