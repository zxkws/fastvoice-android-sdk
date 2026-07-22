package com.zxkws.fastvoice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.zxkws.fastvoice.internal.AndroidTextToSpeechPromptPlayer
import com.zxkws.fastvoice.internal.AudioEngine
import com.zxkws.fastvoice.internal.ClientSessionEpoch
import com.zxkws.fastvoice.internal.CurrentProtocol
import com.zxkws.fastvoice.internal.FallbackPromptGate
import com.zxkws.fastvoice.internal.FallbackPromptPlayer
import com.zxkws.fastvoice.internal.LocalCommandPrePauseState
import com.zxkws.fastvoice.internal.LocalCommandTimeoutPolicy
import com.zxkws.fastvoice.internal.PlaybackCommandGenerationPolicy
import com.zxkws.fastvoice.internal.PlaybackTerminalState
import com.zxkws.fastvoice.internal.ProtocolEncoder
import com.zxkws.fastvoice.internal.ServerHelloCapabilities
import com.zxkws.fastvoice.internal.TurnErrorUplinkState
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.Closeable
import java.net.Proxy
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

private val TRUSTED_MESSAGE_TYPES = setOf("context_update", "spot_arrival")
private val HMAC_SHA256_SIGNATURE = Regex("^[0-9a-fA-F]{64}$")
private const val MAX_TRUSTED_MESSAGE_BYTES = 64 * 1024

/**
 * The single public entry point for FastVoice audio, wake-up, transport, and trusted context.
 *
 * Keep one instance for one foreground voice session. Calls to [start] and [stop] are idempotent;
 * [close] releases the client permanently.
 */
class FastVoiceClient @JvmOverloads constructor(
    context: Context,
    val config: FastVoiceConfig,
    private val listener: FastVoiceListener = FastVoiceListenerAdapter(),
) : Closeable {

    private data class PendingPlaybackAck(
        val commandId: String,
        val generation: Int,
    )

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val protocolTerminationInProgress = AtomicBoolean(false)
    private val helloReady = AtomicBoolean(false)
    private val sessions = ClientSessionEpoch()
    private val reconnectAttempt = AtomicInteger(0)
    private val socket = AtomicReference<WebSocket?>(null)
    private val wakeWords = AtomicReference<List<String>>(emptyList())
    private val playbackGeneration = AtomicInteger(-1)
    private val playbackEpoch = AtomicLong(0)
    private val pendingPlaybackAck = AtomicReference<PendingPlaybackAck?>(null)
    private val playbackControlLock = Any()
    private val playbackTerminalState = PlaybackTerminalState()
    private val serverPlaybackPaused = AtomicBoolean(false)
    private val localCommandPrePauseState = LocalCommandPrePauseState()
    private val localCommandSequence = AtomicLong(0)
    private var localCommandDecisionFuture: ScheduledFuture<*>? = null
    @Volatile
    private var localCommandDecisionTimeoutMs =
        LocalCommandTimeoutPolicy.clientTimeoutMs(
            LocalCommandTimeoutPolicy.DEFAULT_SERVER_TIMEOUT_MS,
        )
    private val fallbackPromptGate = FallbackPromptGate()
    private val turnErrorUplinkState = TurnErrorUplinkState()
    private val localFallbackActive = AtomicBoolean(false)
    private val localFallbackGeneration = AtomicLong(0)

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "fastvoice-scheduler").apply { isDaemon = true }
    }
    private var reconnectFuture: ScheduledFuture<*>? = null

    private val httpClient = OkHttpClient.Builder()
        .apply { if (config.bypassSystemProxy) proxy(Proxy.NO_PROXY) }
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private lateinit var audio: AudioEngine
    private var fallbackPromptPlayer: FallbackPromptPlayer? = null

    init {
        val unsupportedWakeWords = config.preferredWakeWords.filterNot {
            it in AudioEngine.SUPPORTED_WAKE_WORDS
        }
        require(unsupportedWakeWords.isEmpty()) {
            "preferredWakeWords unsupported by the local model: $unsupportedWakeWords"
        }
        audio = AudioEngine(
            context = appContext,
            routeToSpeaker = config.routeAudioToSpeaker,
            callback = object : AudioEngine.Callback {
            override fun onWakeWord(word: String): Boolean {
                val current = socket.get()
                val sent = helloReady.get() && current != null &&
                    current.send(ProtocolEncoder.wake(word))
                if (!sent) {
                    audio.setUplinkEnabled(false)
                    audio.setWakeArmed(helloReady.get() && wakeWords.get().isNotEmpty())
                    emitError(code = "wake_send_failed")
                }
                return sent
            }

            override fun onLocalCommandCandidate(generation: Int, text: String) {
                handleLocalCommandCandidate(generation, text)
            }

            override fun onUplinkPacket(packet: ByteArray) {
                socket.get()?.takeIf { helloReady.get() }?.send(ByteString.of(*packet))
            }

            override fun onPlaybackStarted() = Unit

            override fun onPlaybackProgress(generation: Int, playedMs: Long) {
                if (generation != playbackGeneration.get()) return
                val epoch = playbackEpoch.get()
                if (playbackTerminalState.failureReason(generation, epoch) != null) return
                socket.get()?.takeIf { helloReady.get() }
                    ?.send(ProtocolEncoder.playbackProgress(generation, playedMs))
            }

            override fun onPlaybackFinished(generation: Int) {
                handlePlaybackFinished(generation)
            }

            override fun onPlaybackFailed(generation: Int, reason: String) {
                handlePlaybackFailed(generation, reason)
            }

            override fun onDiagnostic(code: String, message: String, error: Throwable?) {
                log(
                    if (error == null) FastVoiceLogLevel.INFO else FastVoiceLogLevel.ERROR,
                    "$code: $message",
                    error,
                )
                emitError(code = code, message = message.ifEmpty { null }, cause = error)
            }
            },
        )
        if (config.localFallbackPromptEnabled) {
            fallbackPromptPlayer = AndroidTextToSpeechPromptPlayer(appContext) {
                    code, message, error ->
                log(
                    if (error == null) FastVoiceLogLevel.WARN else FastVoiceLogLevel.ERROR,
                    "$code: $message",
                    error,
                )
                emitError(code = code, message = message.ifEmpty { null }, cause = error)
            }
        }
    }

    /** Whether this instance currently owns audio resources and is trying to stay connected. */
    val isStarted: Boolean
        get() = started.get()

    /** The exact server-selected/local-supported intersection from the latest hello response. */
    val activeWakeWords: List<String>
        get() = wakeWords.get()

    /**
     * Starts local audio and opens the WebSocket. Returns false when permission or audio setup is
     * unavailable. Connection failures are reported asynchronously and automatically retried.
     */
    @Synchronized
    fun start(): Boolean {
        check(!closed.get()) { "FastVoiceClient is closed" }
        if (protocolTerminationInProgress.get()) return false
        if (started.get()) return true
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            emitError(code = "microphone_permission_missing")
            return false
        }
        if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) {
            emitError(code = "unsupported_abi", message = Build.SUPPORTED_ABIS.joinToString())
            return false
        }
        if (!started.compareAndSet(false, true)) return true
        if (!audio.start()) {
            started.set(false)
            return false
        }
        audio.setWakeWords(selectedLocalWakeWords())
        connect(sessions.begin())
        return true
    }

    /** Stops audio and reconnect work. The same instance may be started again. */
    @Synchronized
    fun stop() {
        if (protocolTerminationInProgress.get()) return
        if (!started.compareAndSet(true, false)) return
        sessions.invalidate()
        helloReady.set(false)
        fallbackPromptGate.resetSession()
        cancelLocalFallback()
        audio.setWakeArmed(false)
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        socket.getAndSet(null)?.close(1_000, "client stop")
        clearConnectionScopedState()
        audio.stop()
    }

    /** Immediately stops local playback and asks the server to cancel the active response. */
    fun interrupt() {
        fallbackPromptGate.clear()
        cancelLocalFallback()
        invalidateCurrentPlaybackState()
        socket.get()?.takeIf { helloReady.get() }
            ?.send(ProtocolEncoder.interrupt())
    }

    /**
     * Sends a vehicle-platform-signed `context_update` or `spot_arrival` JSON message unchanged.
     *
     * The SDK validates only the public envelope shape and device binding. It never signs the
     * message, stores a signing key, or parses and reserializes the payload. The returned value
     * means that the current WebSocket accepted the text for transmission; the server callback is
     * still authoritative for signature verification and business acceptance.
     */
    fun sendTrustedMessage(rawJson: String): Boolean {
        if (!requireTrustedDevice("trusted_message_credentials_required")) return false
        if (rawJson.toByteArray(Charsets.UTF_8).size > MAX_TRUSTED_MESSAGE_BYTES) {
            emitError(code = "trusted_message_too_large")
            return false
        }
        val message = runCatching { JSONObject(rawJson) }.getOrElse { error ->
            emitError(
                code = "invalid_trusted_message_json",
                message = error.message,
                cause = error,
            )
            return false
        }
        if (message.optString("type") !in TRUSTED_MESSAGE_TYPES) {
            emitError(code = "invalid_trusted_message_type")
            return false
        }
        if (message.optString("device_id") != config.deviceId) {
            emitError(code = "trusted_message_device_mismatch")
            return false
        }
        val auth = message.optJSONObject("auth")
        val signature = auth?.optString("signature").orEmpty()
        if (auth == null || auth.length() != 2 ||
            auth.optString("algorithm") != "hmac-sha256" ||
            !HMAC_SHA256_SIGNATURE.matches(signature)
        ) {
            emitError(code = "trusted_message_signature_required")
            return false
        }
        // Signed messages carry timestamps and monotonic versions. Do not queue or replay one
        // invisibly across connection setup/reconnect; the vehicle platform decides when to retry.
        val current = socket.get()?.takeIf { started.get() && helloReady.get() }
        if (current == null) {
            emitError(code = "trusted_message_transport_unavailable")
            return false
        }
        if (!current.send(rawJson)) {
            emitError(code = "trusted_message_send_failed")
            return false
        }
        return true
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stop()
        fallbackPromptPlayer?.close()
        fallbackPromptPlayer = null
        scheduler.shutdownNow()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    private fun connect(generation: Long) {
        if (!started.get() || closed.get() || !sessions.isCurrent(generation)) return
        val request = runCatching { Request.Builder().url(config.endpoint).build() }
            .getOrElse {
                emitError(code = "invalid_endpoint", message = it.message, cause = it)
                started.set(false)
                audio.stop()
                return
            }
        log(FastVoiceLogLevel.INFO, "opening WebSocket")
        val webSocket = httpClient.newWebSocket(request, SocketListener(generation))
        var published = false
        sessions.runIfCurrent(generation) {
            if (started.get() && !closed.get()) {
                socket.getAndSet(webSocket)?.cancel()
                published = true
            }
        }
        if (!published) webSocket.cancel()
    }

    private inner class SocketListener(
        private val generation: Long,
    ) : WebSocketListener() {
        private val terminated = AtomicBoolean(false)

        override fun onOpen(webSocket: WebSocket, response: Response) {
            var current = false
            sessions.runIfCurrent(generation) {
                current = started.get() && socket.get() === webSocket
            }
            if (!current) {
                webSocket.close(1_000, "stale connection")
                return
            }
            val hello = runCatching {
                ProtocolEncoder.hello(config, AudioEngine.SUPPORTED_WAKE_WORDS)
            }.getOrElse { error ->
                emitError(
                    code = "device_credentials_unavailable",
                    message = error.message,
                    cause = error,
                )
                webSocket.close(1_008, "credentials unavailable")
                return
            }
            sessions.runIfCurrent(generation) {
                if (started.get() && socket.get() === webSocket) webSocket.send(hello)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val packet = bytes.toByteArray()
            sessions.runIfCurrent(generation) {
                if (started.get() && socket.get() === webSocket) {
                    if (!helloReady.get()) {
                        rejectServerHello()
                        return@runIfCurrent
                    }
                    observeServerAudio()
                    audio.enqueueOpus(packet)
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val parsed = runCatching { JSONObject(text) }
            sessions.runIfCurrent(generation) {
                if (!started.get() || socket.get() !== webSocket) return@runIfCurrent
                parsed.fold(
                    onSuccess = ::handleServerMessage,
                    onFailure = { error ->
                        if (helloReady.get()) {
                            emitError(
                                code = "invalid_server_message",
                                message = error.message,
                                cause = error,
                            )
                        } else {
                            rejectServerHello()
                        }
                    },
                )
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!terminated.compareAndSet(false, true)) return
            handleDisconnected(
                generation,
                webSocket,
                FastVoiceError(
                    code = "connection_failed",
                    message = t.message,
                    cause = t,
                ),
            )
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!terminated.compareAndSet(false, true)) return
            val error = if (started.get()) {
                FastVoiceError(code = "connection_closed", message = reason.ifEmpty { code.toString() })
            } else {
                null
            }
            handleDisconnected(generation, webSocket, error)
        }
    }

    private fun handleServerMessage(message: JSONObject) {
        val type = message.strictString("type") ?: run {
            rejectServerHello()
            return
        }
        if (!helloReady.get() && !CurrentProtocol.acceptsBeforeHello(type)) {
            rejectServerHello()
            return
        }
        if (type == "hello") {
            if (helloReady.get()) rejectServerHello() else handleHello(message)
            return
        }
        when (type) {
            "state" -> handleState(message.optString("value"))
            "wake_ack" -> handleWakeAcknowledgement(message)
            "spot_arrival_prompt" -> {
                clearTurnErrorFallback()
                handleServerPrompt(message)
            }
            "asr" -> {
                clearTurnErrorFallback()
                emit(FastVoiceEvent.Asr(message.optString("text")))
            }
            "reply_delta" -> emit(FastVoiceEvent.ReplyDelta(message.optString("text")))
            "command" -> handleCommand(message)
            "turn_error" -> {
                val transition = if (config.localFallbackPromptEnabled) {
                    fallbackPromptGate.onTurnError(
                        message.nullableString("recovery_id"),
                        message.nullableString("prompt"),
                    )
                } else {
                    fallbackPromptGate.clear()
                }
                if (transition.action == FallbackPromptGate.Action.STOP_LOCAL) {
                    cancelLocalFallback()
                }
                emitError(
                    code = message.nullableString("code"),
                    message = message.nullableString("message"),
                    prompt = message.nullableString("prompt"),
                )
            }
            "turn_error_terminal" -> handleTurnErrorTerminal(message)
            "local_command_decision" -> handleLocalCommandDecision(message)
            "context_updated" -> handleContextUpdated(message)
            "context_error" -> handleContextError(message)
            "arrival_accepted" -> handleArrivalAccepted(message)
            "arrival_error" -> handleArrivalError(message)
        }
    }

    private fun handleHello(message: JSONObject) {
        val control = message.optJSONObject("control")
        val audioConfig = message.optJSONObject("audio")
        val localCommand = message.optJSONObject("local_command")
        val wake = message.optJSONObject("wake")
        val capabilities = ServerHelloCapabilities(
            encoding = audioConfig?.strictString("encoding"),
            inputRate = audioConfig?.strictLong("input_rate"),
            outputRate = audioConfig?.strictLong("output_rate"),
            frameMs = audioConfig?.strictLong("frame_ms"),
            wakeEnabled = wake?.strictBoolean("enabled"),
            wakeWords = wake?.strictStringList("words"),
            controlEnabled = control?.strictBoolean("enabled"),
            controlProtocol = control?.strictString("protocol"),
            controlActions = control?.strictStringList("actions"),
            localCommandEnabled = localCommand?.strictBoolean("enabled"),
            candidateMessage = localCommand?.strictString("candidate_message"),
            decisionMessage = localCommand?.strictString("decision_message"),
            confirmTimeoutMs = localCommand?.strictLong("confirm_timeout_ms"),
            generationBound = localCommand?.strictBoolean("generation_bound"),
        )
        if (!CurrentProtocol.acceptsHello(capabilities, AudioEngine.SUPPORTED_WAKE_WORDS)) {
            rejectServerHello()
            return
        }
        localCommandDecisionTimeoutMs = LocalCommandTimeoutPolicy.clientTimeoutMs(
            requireNotNull(capabilities.confirmTimeoutMs),
        )
        val negotiated = linkedSetOf<String>()
        negotiated.addAll(requireNotNull(capabilities.wakeWords))
        val active = if (capabilities.wakeEnabled == true) {
            negotiated.filterTo(linkedSetOf()) { it in AudioEngine.SUPPORTED_WAKE_WORDS }
        } else {
            linkedSetOf()
        }
        val immutable = Collections.unmodifiableList(ArrayList(active))
        wakeWords.set(immutable)
        audio.setWakeWords(active)
        helloReady.set(true)
        reconnectAttempt.set(0)
        emit(FastVoiceEvent.ActiveWakeWords(immutable))

        wake?.nullableString("error")?.let { emitError(code = it) }
        message.optJSONObject("context")?.nullableString("error")?.let {
            emitError(code = it)
        }

    }

    private fun rejectServerHello() {
        if (!protocolTerminationInProgress.compareAndSet(false, true)) return
        try {
            emitError(code = "unsupported_server_protocol")
            started.set(false)
            helloReady.set(false)
            sessions.invalidate()
            fallbackPromptGate.resetSession()
            cancelLocalFallback()
            reconnectFuture?.cancel(false)
            reconnectFuture = null
            audio.setWakeArmed(false)
            audio.setUplinkEnabled(false)
            socket.getAndSet(null)?.close(1_002, "commands-v1 with opus is required")
            clearConnectionScopedState()
            audio.stop()
        } finally {
            protocolTerminationInProgress.set(false)
        }
    }

    private fun handleState(rawValue: String) {
        if (rawValue == FastVoiceState.RECOGNIZING.value) clearTurnErrorFallback()
        emit(FastVoiceEvent.StateChanged(FastVoiceState.fromRaw(rawValue)))
    }

    private fun handleWakeAcknowledgement(message: JSONObject) {
        clearTurnErrorFallback()
        if (!message.optBoolean("awaiting_command")) return
        if (message.optString("audio") == "server") {
            handleServerPrompt(message)
        } else {
            emitError(code = "unsupported_wake_prompt_audio")
        }
    }

    private fun handleServerPrompt(message: JSONObject) {
        if (message.optString("audio") != "server") return
        audio.setWakeArmed(false)
        audio.setPromptPlayback(true)
        audio.setUplinkEnabled(false)
    }

    private fun handleCommand(message: JSONObject) {
        val commandId = message.strictString("id")?.takeIf(String::isNotBlank) ?: return
        val action = message.strictString("action")?.takeIf(String::isNotBlank) ?: return
        val ack = message.strictBoolean("ack") ?: return
        val generation = message.strictInt("gen") ?: if (action in CurrentProtocol.PLAYBACK_ACTIONS) {
            return
        } else {
            playbackGeneration.get()
        }
        var applied = true
        var ackAfterPlayback = false

        when (action) {
            "uplink.start" -> {
                audio.setWakeArmed(false)
                audio.setUplinkEnabled(
                    turnErrorUplinkState.onServerCommand(
                        enabled = true,
                        localPromptPlaying = localFallbackActive.get(),
                    ),
                )
            }
            "uplink.stop" -> {
                audio.setUplinkEnabled(
                    turnErrorUplinkState.onServerCommand(
                        enabled = false,
                        localPromptPlaying = localFallbackActive.get(),
                    ),
                )
                audio.setWakeArmed(
                    config.wakeEnabled && helloReady.get() && wakeWords.get().isNotEmpty(),
                )
            }
            "playback.begin" -> {
                synchronized(playbackControlLock) {
                    if (!PlaybackCommandGenerationPolicy.acceptsReset(
                            generation,
                            playbackGeneration.get(),
                        )
                    ) {
                        applied = false
                    } else {
                        clearAnyLocalCommandPrePauseLocked()
                        playbackGeneration.set(generation)
                        val epoch = playbackEpoch.incrementAndGet()
                        playbackTerminalState.begin(generation, epoch)
                        pendingPlaybackAck.set(null)
                        serverPlaybackPaused.set(false)
                        audio.beginPlayback(generation)
                    }
                }
            }
            "playback.end" -> {
                var resumePrePause = false
                var failureReason: String? = null
                synchronized(playbackControlLock) {
                    if (!PlaybackCommandGenerationPolicy.acceptsEnd(
                            generation,
                            playbackGeneration.get(),
                        )
                    ) {
                        applied = false
                    } else {
                        val epoch = playbackEpoch.get()
                        resumePrePause = clearLocalCommandPrePauseLocked(resume = true)
                        failureReason = playbackTerminalState.failureReason(generation, epoch)
                        ackAfterPlayback = ack
                        if (failureReason == null && ackAfterPlayback && commandId.isNotBlank()) {
                            pendingPlaybackAck.set(PendingPlaybackAck(commandId, generation))
                        } else {
                            pendingPlaybackAck.set(null)
                        }
                    }
                }
                if (resumePrePause) audio.resumePlayback(generation)
                if (applied) {
                    if (failureReason != null) {
                        socket.get()?.takeIf { helloReady.get() }?.send(
                            ProtocolEncoder.playbackFailed(
                                generation,
                                failureReason!!,
                                commandId.takeIf { it.isNotBlank() },
                            ),
                        )
                    } else {
                        audio.endPlayback()
                    }
                }
            }
            "playback.stop", "playback.skip" -> {
                synchronized(playbackControlLock) {
                    if (!PlaybackCommandGenerationPolicy.acceptsReset(
                            generation,
                            playbackGeneration.get(),
                        )
                    ) {
                        applied = false
                    } else {
                        clearAnyLocalCommandPrePauseLocked()
                        playbackGeneration.set(generation)
                        val epoch = playbackEpoch.incrementAndGet()
                        playbackTerminalState.invalidate(generation, epoch)
                        pendingPlaybackAck.set(null)
                        serverPlaybackPaused.set(false)
                        audio.setPromptPlayback(false)
                        if (action == "playback.stop") {
                            audio.interruptPlayback()
                        } else {
                            audio.skipPlayback()
                        }
                    }
                }
            }
            "playback.pause" -> {
                synchronized(playbackControlLock) {
                    if (generation != playbackGeneration.get()) {
                        applied = false
                    } else {
                        serverPlaybackPaused.set(true)
                        clearLocalCommandPrePauseLocked(resume = false)
                        applied = audio.pausePlayback(generation)
                    }
                }
            }
            "playback.resume" -> {
                synchronized(playbackControlLock) {
                    if (generation != playbackGeneration.get()) {
                        applied = false
                    } else {
                        serverPlaybackPaused.set(false)
                        clearLocalCommandPrePauseLocked(resume = false)
                        applied = audio.resumePlayback(generation)
                    }
                }
            }
            "playback.replay" -> {
                synchronized(playbackControlLock) {
                    if (!PlaybackCommandGenerationPolicy.acceptsReset(
                            generation,
                            playbackGeneration.get(),
                        )
                    ) {
                        applied = false
                    } else {
                        clearAnyLocalCommandPrePauseLocked()
                        playbackGeneration.set(generation)
                        val epoch = playbackEpoch.incrementAndGet()
                        playbackTerminalState.begin(generation, epoch)
                        pendingPlaybackAck.set(null)
                        serverPlaybackPaused.set(false)
                        applied = audio.replayPlayback(generation)
                    }
                }
                if (!applied && generation == playbackGeneration.get()) {
                    handlePlaybackFailed(generation, "replay unavailable")
                }
            }
            "audio.volume.adjust" -> {
                synchronized(playbackControlLock) {
                    val resume = clearLocalCommandPrePauseLocked(resume = true)
                    if (resume) applied = audio.resumePlayback(generation)
                }
                val direction = when (message.optString("direction")) {
                    "up" -> AudioManager.ADJUST_RAISE
                    "down" -> AudioManager.ADJUST_LOWER
                    else -> 0
                }
                if (applied) applied = audio.adjustPlaybackVolume(direction)
            }
            else -> {
                applied = false
                log(FastVoiceLogLevel.INFO, "ignored unsupported server action: $action")
            }
        }

        if (!applied) {
            log(FastVoiceLogLevel.INFO, "server action was not applicable: $action")
        }
        if (applied && ack && !ackAfterPlayback) {
            socket.get()?.takeIf { helloReady.get() }
                ?.send(ProtocolEncoder.commandAck(commandId))
        }
    }

    private fun handleLocalCommandCandidate(generation: Int, text: String) {
        if (!helloReady.get() || generation != playbackGeneration.get()) {
            audio.resumePlayback(generation)
            return
        }
        val candidateId: String
        val epoch: Long
        synchronized(playbackControlLock) {
            epoch = playbackEpoch.get()
            if (playbackTerminalState.failureReason(generation, epoch) != null) {
                audio.resumePlayback(generation)
                return
            }
            candidateId = "lc-${localCommandSequence.incrementAndGet()}"
            if (!localCommandPrePauseState.begin(candidateId, generation, epoch)) return
            armLocalCommandTimeoutLocked(
                candidateId,
                generation,
                epoch,
                requireAccepted = false,
                localCommandDecisionTimeoutMs,
            )
        }
        val sent = socket.get()?.takeIf { helloReady.get() }?.send(
            ProtocolEncoder.localCommandCandidate(candidateId, text, generation),
        ) == true
        if (!sent) applyLocalCommandDecision(candidateId, generation, "rejected")
    }

    private fun handleLocalCommandDecision(message: JSONObject) {
        val id = message.optString("id")
        val rawGeneration = message.opt("gen")
        val generation = (rawGeneration as? Number)?.toLong()?.takeIf {
            it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
        }?.toInt() ?: return
        val decision = message.optString("decision")
        if (id.isEmpty() || decision != "accepted" && decision != "rejected") return
        applyLocalCommandDecision(id, generation, decision)
    }

    private fun applyLocalCommandDecision(id: String, generation: Int, decision: String) {
        var resume = false
        synchronized(playbackControlLock) {
            val epoch = playbackEpoch.get()
            when (
                localCommandPrePauseState.decide(
                    id,
                    generation,
                    decision,
                    playbackGeneration.get(),
                    epoch,
                    serverPlaybackPaused.get(),
                )
            ) {
                LocalCommandPrePauseState.Outcome.IGNORED -> return
                LocalCommandPrePauseState.Outcome.ACCEPTED_HOLD -> {
                    cancelLocalCommandTimeoutLocked()
                    armLocalCommandTimeoutLocked(
                        id,
                        generation,
                        epoch,
                        requireAccepted = true,
                        LocalCommandTimeoutPolicy.ACCEPTED_ACTION_TIMEOUT_MS,
                    )
                }
                LocalCommandPrePauseState.Outcome.RESUME -> {
                    cancelLocalCommandTimeoutLocked()
                    resume = true
                }
                LocalCommandPrePauseState.Outcome.CLEARED -> {
                    cancelLocalCommandTimeoutLocked()
                }
            }
        }
        if (resume) audio.resumePlayback(generation)
    }

    private fun armLocalCommandTimeoutLocked(
        id: String,
        generation: Int,
        epoch: Long,
        requireAccepted: Boolean,
        delayMs: Long,
    ) {
        cancelLocalCommandTimeoutLocked()
        localCommandDecisionFuture = scheduler.schedule({
            var resume = false
            synchronized(playbackControlLock) {
                val outcome = localCommandPrePauseState.timeout(
                    id,
                    generation,
                    epoch,
                    playbackGeneration.get(),
                    playbackEpoch.get(),
                    serverPlaybackPaused.get(),
                    requireAccepted,
                )
                if (outcome != LocalCommandPrePauseState.Outcome.IGNORED) {
                    localCommandDecisionFuture = null
                    resume = outcome == LocalCommandPrePauseState.Outcome.RESUME
                }
            }
            if (resume) audio.resumePlayback(generation)
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun cancelLocalCommandTimeoutLocked() {
        localCommandDecisionFuture?.cancel(false)
        localCommandDecisionFuture = null
    }

    /** Caller must hold [playbackControlLock]. */
    private fun clearLocalCommandPrePauseLocked(resume: Boolean): Boolean {
        val generation = playbackGeneration.get()
        val epoch = playbackEpoch.get()
        val cleared = localCommandPrePauseState.clearIfCurrent(generation, epoch)
        if (cleared) cancelLocalCommandTimeoutLocked()
        return cleared && resume && !serverPlaybackPaused.get()
    }

    /** Caller must hold [playbackControlLock]. */
    private fun clearAnyLocalCommandPrePauseLocked() {
        if (localCommandPrePauseState.clear()) cancelLocalCommandTimeoutLocked()
    }

    private fun invalidateCurrentPlaybackState() {
        synchronized(playbackControlLock) {
            val generation = playbackGeneration.get()
            clearAnyLocalCommandPrePauseLocked()
            val epoch = playbackEpoch.incrementAndGet()
            playbackTerminalState.invalidate(generation, epoch)
            pendingPlaybackAck.set(null)
            serverPlaybackPaused.set(false)
            audio.interruptPlayback()
            audio.setPromptPlayback(false)
        }
    }

    private fun handlePlaybackFinished(generation: Int) {
        val current = socket.get()?.takeIf { helloReady.get() }
        val pendingAck: PendingPlaybackAck?
        synchronized(playbackControlLock) {
            if (generation != playbackGeneration.get() ||
                !playbackTerminalState.finish(generation, playbackEpoch.get())
            ) return
            pendingAck = pendingPlaybackAck.getAndSet(null)?.takeIf {
                it.generation == generation
            }
            clearLocalCommandPrePauseLocked(resume = false)
        }
        if (current?.send(ProtocolEncoder.playbackFinished(generation)) == true) {
            pendingAck?.let { current.send(ProtocolEncoder.commandAck(it.commandId)) }
        }
        audio.setPromptPlayback(false)
    }

    private fun handlePlaybackFailed(generation: Int, reason: String) {
        val failureReason: String
        val pendingAck: PendingPlaybackAck?
        synchronized(playbackControlLock) {
            if (generation != playbackGeneration.get()) return
            val epoch = playbackEpoch.get()
            val result = playbackTerminalState.fail(generation, epoch, reason)
            if (result != PlaybackTerminalState.FailureResult.RECORDED) return
            failureReason = playbackTerminalState.failureReason(generation, epoch) ?: return
            pendingAck = pendingPlaybackAck.getAndSet(null)?.takeIf {
                it.generation == generation
            }
            clearLocalCommandPrePauseLocked(resume = false)
        }
        audio.setPromptPlayback(false)
        socket.get()?.takeIf { helloReady.get() }?.send(
            ProtocolEncoder.playbackFailed(
                generation,
                failureReason,
                pendingAck?.commandId,
            ),
        )
    }

    private fun handleTurnErrorTerminal(message: JSONObject) {
        val recoveryId = message.optString("recovery_id")
        val rawAudioStarted = message.opt("server_audio_started")
        val finalState = message.optString("final_state")
        if (recoveryId.isEmpty() || rawAudioStarted !is Boolean ||
            finalState != FastVoiceState.SLEEPING.value &&
            finalState != FastVoiceState.LISTENING.value
        ) return
        val transition = fallbackPromptGate.onTerminal(
            recoveryId,
            rawAudioStarted,
            finalState,
        )
        when (transition.action) {
            FallbackPromptGate.Action.NONE -> Unit
            FallbackPromptGate.Action.STOP_LOCAL -> {
                cancelLocalFallback()
                applyTurnErrorFinalState(finalState)
            }
            FallbackPromptGate.Action.APPLY_FINAL_STATE -> {
                applyTurnErrorFinalState(finalState)
            }
            FallbackPromptGate.Action.PLAY_LOCAL -> {
                startLocalFallback(
                    requireNotNull(transition.recoveryId),
                    requireNotNull(transition.prompt),
                    requireNotNull(transition.finalState),
                )
            }
        }
    }

    private fun applyTurnErrorFinalState(finalState: String) {
        val uplink = turnErrorUplinkState.afterTerminal()
        audio.setUplinkEnabled(uplink)
        audio.setWakeArmed(
            !uplink && finalState == FastVoiceState.SLEEPING.value &&
                config.wakeEnabled && helloReady.get() && wakeWords.get().isNotEmpty(),
        )
    }

    private fun handleContextUpdated(message: JSONObject) {
        emit(FastVoiceEvent.ContextUpdated(message.optLong("version", -1L)))
    }

    private fun handleContextError(message: JSONObject) {
        val code = message.nullableString("code")
        val errorMessage = message.nullableString("message")
        emitError(code = code, message = errorMessage)
    }

    private fun handleArrivalAccepted(message: JSONObject) {
        val eventId = message.optString("event_id")
        emit(
            FastVoiceEvent.ArrivalAccepted(
                eventId = eventId,
                version = message.optLong("version", -1L),
                spotId = message.optString("spot_id"),
            ),
        )
    }

    private fun handleArrivalError(message: JSONObject) {
        val code = message.nullableString("code")
        val errorMessage = message.nullableString("message")
        emit(FastVoiceEvent.ArrivalRejected(code, errorMessage))
        emitError(code = code, message = errorMessage)
    }

    private fun handleDisconnected(
        generation: Long,
        webSocket: WebSocket,
        error: FastVoiceError?,
    ) {
        sessions.runIfCurrent(generation) {
            if (!socket.compareAndSet(webSocket, null)) return@runIfCurrent
            helloReady.set(false)
            fallbackPromptGate.resetSession()
            cancelLocalFallback()
            audio.setWakeArmed(false)
            audio.setUplinkEnabled(false)
            audio.interruptPlayback()
            clearConnectionScopedState()
            error?.let { emit(FastVoiceEvent.Error(it)) }
            scheduleReconnect(generation)
        }
    }

    private fun clearConnectionScopedState() {
        synchronized(playbackControlLock) {
            clearAnyLocalCommandPrePauseLocked()
            playbackGeneration.set(-1)
            val epoch = playbackEpoch.incrementAndGet()
            playbackTerminalState.invalidate(-1, epoch)
            pendingPlaybackAck.set(null)
            serverPlaybackPaused.set(false)
        }
        turnErrorUplinkState.reset()
        audio.setPromptPlayback(false)
    }

    private fun observeServerAudio() {
        val transition = fallbackPromptGate.onServerAudio()
        if (transition.action == FallbackPromptGate.Action.STOP_LOCAL ||
            localFallbackActive.get()
        ) {
            cancelLocalFallback()
        }
    }

    private fun clearTurnErrorFallback() {
        val transition = fallbackPromptGate.clear()
        if (transition.action == FallbackPromptGate.Action.STOP_LOCAL ||
            localFallbackActive.get()
        ) {
            cancelLocalFallback()
        }
    }

    private fun startLocalFallback(recoveryId: String, prompt: String, finalState: String) {
        val player = fallbackPromptPlayer
        if (player == null) {
            fallbackPromptGate.finishLocal(recoveryId)
            applyTurnErrorFinalState(finalState)
            return
        }
        cancelLocalFallback()
        val generation = localFallbackGeneration.incrementAndGet()
        localFallbackActive.set(true)
        audio.interruptPlayback()
        audio.setWakeArmed(false)
        audio.setUplinkEnabled(false)
        audio.setPromptPlayback(true)
        val accepted = player.speak(prompt) {
            mainHandler.post {
                if (localFallbackGeneration.get() != generation ||
                    !localFallbackActive.compareAndSet(true, false)
                ) {
                    return@post
                }
                audio.setPromptPlayback(false)
                if (fallbackPromptGate.finishLocal(recoveryId)) {
                    applyTurnErrorFinalState(finalState)
                }
            }
        }
        if (!accepted && localFallbackGeneration.get() == generation &&
            localFallbackActive.compareAndSet(true, false)
        ) {
            audio.setPromptPlayback(false)
            if (fallbackPromptGate.finishLocal(recoveryId)) {
                applyTurnErrorFinalState(finalState)
            }
            emitError(code = "fallback_tts_unavailable")
        }
    }

    private fun cancelLocalFallback() {
        localFallbackGeneration.incrementAndGet()
        val wasActive = localFallbackActive.getAndSet(false)
        fallbackPromptPlayer?.stop()
        if (wasActive) {
            audio.setPromptPlayback(false)
        }
    }

    private fun scheduleReconnect(generation: Long) {
        if (!started.get() || !config.autoReconnect || !sessions.isCurrent(generation)) return
        val attempt = reconnectAttempt.getAndIncrement()
        val delays = longArrayOf(1, 2, 4, 8, 15, 30)
        val delay = delays[minOf(attempt, delays.lastIndex)]
        log(FastVoiceLogLevel.INFO, "reconnecting in ${delay}s")
        reconnectFuture?.cancel(false)
        reconnectFuture = scheduler.schedule({ connect(generation) }, delay, TimeUnit.SECONDS)
    }

    private fun requireTrustedDevice(code: String): Boolean {
        if (config.deviceId != null) return true
        emitError(code = code)
        return false
    }

    private fun selectedLocalWakeWords(): List<String> =
        if (config.preferredWakeWords.isEmpty()) AudioEngine.SUPPORTED_WAKE_WORDS.toList()
        else config.preferredWakeWords

    private fun emitError(
        code: String? = null,
        message: String? = null,
        prompt: String? = null,
        cause: Throwable? = null,
    ) {
        emit(FastVoiceEvent.Error(FastVoiceError(code, message, prompt, cause)))
    }

    private fun emit(event: FastVoiceEvent) {
        mainHandler.post {
            safeListenerCall { listener.onEvent(event) }
            when (event) {
                is FastVoiceEvent.StateChanged -> safeListenerCall {
                    listener.onStateChanged(event.state)
                }
                is FastVoiceEvent.Asr -> safeListenerCall { listener.onAsr(event.text) }
                is FastVoiceEvent.ReplyDelta -> safeListenerCall {
                    listener.onReplyDelta(event.text)
                }
                is FastVoiceEvent.Error -> safeListenerCall { listener.onError(event.error) }
                is FastVoiceEvent.ActiveWakeWords -> safeListenerCall {
                    listener.onActiveWakeWords(event.words)
                }
                is FastVoiceEvent.ContextUpdated -> safeListenerCall {
                    listener.onContextUpdated(event)
                }
                is FastVoiceEvent.ArrivalAccepted -> safeListenerCall {
                    listener.onArrivalAccepted(event)
                }
                is FastVoiceEvent.ArrivalRejected -> safeListenerCall {
                    listener.onArrivalRejected(event)
                }
            }
        }
    }

    private inline fun safeListenerCall(block: () -> Unit) {
        runCatching(block).onFailure { error ->
            log(FastVoiceLogLevel.ERROR, "listener callback failed", error)
        }
    }

    private fun log(level: FastVoiceLogLevel, message: String, error: Throwable? = null) {
        runCatching { config.logger?.log(level, message, error) }
    }

    class Builder internal constructor(private val context: Context) {
        private var endpoint: String? = null
        private var deviceId: String? = null
        private var tokenProvider: DeviceTokenProvider? = null
        private var wakeEnabled = true
        private var preferredWakeWords: List<String> = emptyList()
        private var autoReconnect = true
        private var bypassSystemProxy = false
        private var allowInsecureConnection = false
        private var routeAudioToSpeaker = true
        private var logger: FastVoiceLogger? = null
        private var listener: FastVoiceListener = FastVoiceListenerAdapter()
        private var localFallbackPromptEnabled = true

        fun endpoint(endpoint: String) = apply { this.endpoint = endpoint }

        fun device(deviceId: String, tokenProvider: DeviceTokenProvider) = apply {
            this.deviceId = deviceId
            this.tokenProvider = tokenProvider
        }

        fun device(credentials: DeviceCredentials) =
            device(credentials.deviceId, credentials.asTokenProvider())

        fun wakeEnabled(enabled: Boolean) = apply { wakeEnabled = enabled }

        fun preferredWakeWords(words: List<String>) = apply {
            preferredWakeWords = ArrayList(words)
        }

        fun autoReconnect(enabled: Boolean) = apply { autoReconnect = enabled }

        fun bypassSystemProxy(enabled: Boolean) = apply { bypassSystemProxy = enabled }

        fun allowInsecureConnection(allowed: Boolean) = apply {
            allowInsecureConnection = allowed
        }

        fun routeAudioToSpeaker(enabled: Boolean) = apply { routeAudioToSpeaker = enabled }

        fun logger(logger: FastVoiceLogger?) = apply { this.logger = logger }

        fun listener(listener: FastVoiceListener) = apply { this.listener = listener }

        fun localFallbackPromptEnabled(enabled: Boolean) = apply {
            localFallbackPromptEnabled = enabled
        }

        fun build(): FastVoiceClient {
            val config = FastVoiceConfig(
                endpoint = requireNotNull(endpoint) { "endpoint is required" },
                deviceId = deviceId,
                tokenProvider = tokenProvider,
                wakeEnabled = wakeEnabled,
                preferredWakeWords = preferredWakeWords,
                autoReconnect = autoReconnect,
                bypassSystemProxy = bypassSystemProxy,
                allowInsecureConnection = allowInsecureConnection,
                routeAudioToSpeaker = routeAudioToSpeaker,
                logger = logger,
                localFallbackPromptEnabled = localFallbackPromptEnabled,
            )
            return FastVoiceClient(context, config, listener)
        }
    }

    companion object {
        /** The local model capability. The server still selects the active subset per device. */
        @JvmField
        val SUPPORTED_WAKE_WORDS: List<String> = Collections.unmodifiableList(
            ArrayList(AudioEngine.SUPPORTED_WAKE_WORDS),
        )

        @JvmStatic
        fun builder(context: Context): Builder = Builder(context.applicationContext)
    }
}

private fun JSONObject.nullableString(name: String): String? =
    takeIf { has(name) && !isNull(name) }?.optString(name)

private fun JSONObject.strictString(name: String): String? = opt(name) as? String

private fun JSONObject.strictBoolean(name: String): Boolean? = opt(name) as? Boolean

private fun JSONObject.strictLong(name: String): Long? {
    val value = opt(name) as? Number ?: return null
    val number = value.toLong()
    return number.takeIf { value.toDouble().isFinite() && it.toDouble() == value.toDouble() }
}

private fun JSONObject.strictInt(name: String): Int? = strictLong(name)?.takeIf {
    it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
}?.toInt()

private fun JSONObject.strictStringList(name: String): List<String>? {
    val array = optJSONArray(name) ?: return null
    val values = ArrayList<String>(array.length())
    for (index in 0 until array.length()) {
        values += array.opt(index) as? String ?: return null
    }
    return values
}
