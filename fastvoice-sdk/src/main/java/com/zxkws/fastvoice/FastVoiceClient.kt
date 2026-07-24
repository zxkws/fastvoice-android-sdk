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
import com.zxkws.fastvoice.internal.FallbackPromptPlayer
import com.zxkws.fastvoice.internal.LocalCommandPrePauseState
import com.zxkws.fastvoice.internal.LocalCommandTimeoutPolicy
import com.zxkws.fastvoice.internal.OrderOperationState
import com.zxkws.fastvoice.internal.PlaybackTerminalState
import com.zxkws.fastvoice.internal.ProtocolEncoder
import com.zxkws.fastvoice.internal.ReadyMessage
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

/**
 * The single public entry point for the one FastVoice WebSocket protocol.
 *
 * Keep one instance while the host owns foreground voice. [start] and [stop] are idempotent;
 * [close] releases the instance permanently.
 */
class FastVoiceClient @JvmOverloads constructor(
    context: Context,
    val config: FastVoiceConfig,
    private val listener: FastVoiceListener = FastVoiceListenerAdapter(),
) : Closeable {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)
    private val sessions = ClientSessionEpoch()
    private val reconnectAttempt = AtomicInteger(0)
    private val socket = AtomicReference<WebSocket?>(null)
    private val wakeWords = AtomicReference<List<String>>(emptyList())

    private val playbackId = AtomicInteger(-1)
    private val playbackEpoch = AtomicLong(0)
    private val playbackTerminalState = PlaybackTerminalState()
    private val serverPlaybackPaused = AtomicBoolean(false)
    private val playbackControlLock = Any()

    private val localCommandPrePauseState = LocalCommandPrePauseState()
    private val localCommandSequence = AtomicLong(0)
    private var localCommandTimeoutFuture: ScheduledFuture<*>? = null
    @Volatile
    private var localCommandTimeoutMs = LocalCommandTimeoutPolicy.clientTimeoutMs(
        LocalCommandTimeoutPolicy.DEFAULT_SERVER_TIMEOUT_MS,
    )

    private val orderState = OrderOperationState()
    private val orderSendLock = Any()
    private var playbackTourId: String? = null

    private val localFallbackActive = AtomicBoolean(false)
    private val localFallbackGeneration = AtomicLong(0)
    private var fallbackPromptPlayer: FallbackPromptPlayer? = null

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
    private val audioCallback = object : AudioEngine.Callback {
            override fun onWakeWord(word: String): Boolean {
                val current = socket.get()
                val sent = ready.get() && current != null &&
                    current.send(ProtocolEncoder.wake(word))
                if (!sent) {
                    audio.setWakeArmed(canArmWake())
                    emitLocalError("wake_send_failed")
                }
                return sent
            }

            override fun onLocalCommandCandidate(generation: Int, text: String) {
                handleLocalCommandCandidate(generation, text)
            }

            override fun onLocalPromptControl(text: String) {
                if (!localFallbackActive.get()) return
                cancelLocalFallback()
                audio.setWakeArmed(canArmWake())
                socket.get()?.takeIf { ready.get() }?.send(ProtocolEncoder.turnCancel())
            }

            override fun onUplinkPacket(packet: ByteArray) {
                socket.get()?.takeIf { ready.get() }?.send(ByteString.of(*packet))
            }

            override fun onPlaybackStarted() = Unit

            override fun onPlaybackProgress(generation: Int, playedMs: Long) {
                if (generation != playbackId.get()) return
                val epoch = playbackEpoch.get()
                if (playbackTerminalState.failureReason(generation, epoch) != null) return
                socket.get()?.takeIf { ready.get() }
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
                    if (error == null) FastVoiceLogLevel.WARN else FastVoiceLogLevel.ERROR,
                    "$code: $message",
                    error,
                )
                emitLocalError(code, message.ifEmpty { null }, error)
            }
        }

    init {
        audio = AudioEngine(
            context = appContext,
            routeToSpeaker = config.routeAudioToSpeaker,
            callback = audioCallback,
        )
        val unsupportedWakeWords = config.preferredWakeWords.filterNot {
            it in AudioEngine.SUPPORTED_WAKE_WORDS
        }
        require(unsupportedWakeWords.isEmpty()) {
            "preferredWakeWords unsupported by the local model: $unsupportedWakeWords"
        }
        if (config.localFallbackPromptEnabled) {
            fallbackPromptPlayer = AndroidTextToSpeechPromptPlayer(appContext) {
                    code, message, error ->
                log(
                    if (error == null) FastVoiceLogLevel.WARN else FastVoiceLogLevel.ERROR,
                    "$code: $message",
                    error,
                )
                emitLocalError(code, message.ifEmpty { null }, error)
            }
        }
    }

    /** Whether this instance currently owns audio resources and reconnect work. */
    val isStarted: Boolean
        get() = started.get()

    /** Exact server-selected wake words supported and enabled by this client. */
    val activeWakeWords: List<String>
        get() = wakeWords.get()

    @Synchronized
    fun start(): Boolean {
        check(!closed.get()) { "FastVoiceClient is closed" }
        if (started.get()) return true
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            emitLocalError("microphone_permission_missing")
            return false
        }
        if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) {
            emitLocalError("unsupported_abi", Build.SUPPORTED_ABIS.joinToString())
            return false
        }
        if (!started.compareAndSet(false, true)) return true
        if (!audio.start()) {
            started.set(false)
            return false
        }
        audio.setKwsSessionEnabled(false)
        audio.setWakeWords(selectedLocalWakeWords())
        audio.setWakeArmed(false)
        connect(sessions.begin())
        return true
    }

    @Synchronized
    fun stop() {
        if (!started.compareAndSet(true, false)) return
        sessions.invalidate()
        synchronized(orderSendLock) {
            ready.set(false)
            orderState.markConnectionUnready()
        }
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        cancelLocalFallback()
        audio.setKwsSessionEnabled(false)
        audio.setWakeArmed(false)
        audio.stopCapture()
        socket.getAndSet(null)?.close(1_000, "client stop")
        clearConnectionState()
        audio.stop()
    }

    /** Stops local output immediately and cancels the current server turn. */
    fun interrupt() {
        cancelLocalFallback()
        invalidatePlayback()
        socket.get()?.takeIf { ready.get() }?.send(ProtocolEncoder.turnCancel())
    }

    /**
     * Starts one order. The snapshot is retained and resent as order.start after reconnect.
     *
     * `true` means the SDK accepted the desired state, including while disconnected; only
     * [FastVoiceEvent.OrderAck] means the server accepted it.
     */
    fun startOrder(snapshot: OrderSnapshot): Boolean = synchronized(orderSendLock) {
        val acceptance = orderState.start(snapshot)
        if (!acceptance.accepted) {
            emitLocalError(requireNotNull(acceptance.errorCode), acceptance.errorRef)
            return@synchronized false
        }
        if (!acceptance.exactRetry) {
            syncKwsSession()
            invalidatePlayback()
        }
        sendIfReady(ProtocolEncoder.orderStart(snapshot))
        true
    }

    /**
     * Replaces the complete context of the active order.
     *
     * `true` means accepted by the SDK. Delivery may wait for ready/reconnect.
     */
    fun updateOrder(snapshot: OrderSnapshot): Boolean = synchronized(orderSendLock) {
        val acceptance = orderState.update(snapshot)
        if (!acceptance.accepted) {
            emitLocalError(requireNotNull(acceptance.errorCode), acceptance.errorRef)
            return@synchronized false
        }
        sendIfReady(ProtocolEncoder.orderUpdate(snapshot))
        true
    }

    /**
     * Ends the active order. The request remains retryable across reconnect until order.ack.
     *
     * `true` means accepted by the SDK, not acknowledged by the server.
     */
    @JvmOverloads
    fun endOrder(
        id: String,
        rev: Long,
        reason: String = "completed",
    ): Boolean = synchronized(orderSendLock) {
        require(reason.isNotBlank()) { "order end reason must not be blank" }
        val acceptance = orderState.end(id, rev, reason, audio.isUplinkEnabled())
        if (!acceptance.accepted) {
            emitLocalError(requireNotNull(acceptance.errorCode), acceptance.errorRef)
            return@synchronized false
        }
        if (!acceptance.exactRetry) {
            audio.stopCapture()
            syncKwsSession()
            invalidatePlayback()
        }
        sendIfReady(ProtocolEncoder.orderEnd(id, rev, reason))
        true
    }

    /** Requests an arrival announcement bound to one exact active order snapshot. */
    @JvmOverloads
    fun playArrival(
        id: String,
        orderId: String,
        orderRev: Long,
        spotId: String,
        content: String = "arrival_prompt",
    ): Boolean = synchronized(orderSendLock) {
        require(id.isNotBlank()) { "tour id must not be blank" }
        require(orderId.isNotBlank()) { "orderId must not be blank" }
        require(orderRev >= 0) { "orderRev must be non-negative" }
        require(spotId.isNotBlank()) { "spotId must not be blank" }
        require(content.isNotBlank()) { "tour content must not be blank" }
        val order = orderState.desired()
        if (order == null || order.id != orderId || order.rev != orderRev ||
            order.context["current_spot_id"] != spotId
        ) {
            emitLocalError("arrival_order_mismatch", id)
            return@synchronized false
        }
        sendTour(id, "arrival", content, orderId, orderRev, spotId)
    }

    /** Requests a fixed idle-cruise announcement while no order is active. */
    fun playCruise(id: String, content: String): Boolean = synchronized(orderSendLock) {
        require(id.isNotBlank()) { "tour id must not be blank" }
        require(content.isNotBlank()) { "tour content must not be blank" }
        if (orderState.hasDesiredOrder()) {
            emitLocalError("cruise_order_active", id)
            return@synchronized false
        }
        sendTour(id, "cruise", content, null, null, null)
    }

    private fun sendTour(
        id: String,
        source: String,
        content: String,
        orderId: String?,
        orderRev: Long?,
        spotId: String?,
    ): Boolean {
        val current = socket.get()?.takeIf { started.get() && ready.get() } ?: run {
            emitLocalError("tour_transport_unavailable", id)
            return false
        }
        if (!current.send(
                ProtocolEncoder.tourPlay(id, source, content, orderId, orderRev, spotId),
            )
        ) {
            emitLocalError("tour_send_failed", id)
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

    private fun connect(session: Long) {
        if (!started.get() || closed.get() || !sessions.isCurrent(session)) return
        val request = runCatching {
            Request.Builder()
                .url(config.endpoint)
                .apply {
                    val deviceId = requireNotNull(config.deviceId)
                    header("X-FastVoice-Device-Id", deviceId)
                    header("Authorization", "Bearer ${config.requireDeviceToken()}")
                }
                .build()
        }.getOrElse { error ->
            emitLocalError("connection_configuration_invalid", error.message, error)
            started.set(false)
            audio.stop()
            return
        }
        log(FastVoiceLogLevel.INFO, "opening WebSocket")
        val webSocket = httpClient.newWebSocket(request, SocketListener(session))
        sessions.runIfCurrent(session) {
            if (started.get()) {
                socket.getAndSet(webSocket)?.cancel()
            } else {
                webSocket.cancel()
            }
        }
    }

    private inner class SocketListener(
        private val session: Long,
    ) : WebSocketListener() {
        private val terminated = AtomicBoolean(false)

        override fun onOpen(webSocket: WebSocket, response: Response) {
            var current = false
            sessions.runIfCurrent(session) {
                current = started.get() && socket.get() === webSocket
            }
            if (!current) {
                webSocket.close(1_000, "stale connection")
                return
            }
            webSocket.send(ProtocolEncoder.hello(config.wakeEnabled))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            sessions.runIfCurrent(session) {
                if (!started.get() || socket.get() !== webSocket) return@runIfCurrent
                if (!ready.get() || playbackId.get() < 0) {
                    terminateProtocol("binary_before_playback")
                    return@runIfCurrent
                }
                audio.enqueueOpus(bytes.toByteArray())
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val parsed = runCatching { JSONObject(text) }
            sessions.runIfCurrent(session) {
                if (!started.get() || socket.get() !== webSocket) return@runIfCurrent
                parsed.fold(
                    onSuccess = ::handleServerMessage,
                    onFailure = { terminateProtocol("invalid_server_json", it) },
                )
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!terminated.compareAndSet(false, true)) return
            handleDisconnected(session, webSocket, "connection_failed", t.message, t)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!terminated.compareAndSet(false, true)) return
            handleDisconnected(
                session,
                webSocket,
                if (started.get()) "connection_closed" else null,
                reason.ifEmpty { code.toString() },
                null,
            )
        }
    }

    private fun handleServerMessage(message: JSONObject) {
        val type = message.strictString("type") ?: run {
            terminateProtocol("missing_message_type")
            return
        }
        if (!ready.get() && !CurrentProtocol.acceptsBeforeReady(type)) {
            terminateProtocol("message_before_ready")
            return
        }
        if (type == "ready") {
            if (ready.get()) terminateProtocol("duplicate_ready") else handleReady(message)
            return
        }
        when (type) {
            "state" -> handleState(message)
            "transcript" -> handleTranscript(message)
            "order.ack" -> handleOrderAck(message)
            "tour.ack" -> handleTourAck(message)
            "playback.start" -> handlePlaybackStart(message)
            "playback.end" -> handlePlaybackEnd(message)
            "control" -> handleControl(message)
            "control.decision" -> handleControlDecision(message)
            "error" -> handleError(message)
            else -> terminateProtocol("unsupported_server_message")
        }
    }

    private fun handleReady(message: JSONObject) {
        if (!CurrentProtocol.acceptsReadyFields(message.keys().asSequence().toSet())) {
            terminateProtocol("invalid_ready")
            return
        }
        val readyMessage = ReadyMessage(
            connectionId = message.strictString("connection_id"),
            wakeWords = message.strictStringList("wake_words"),
            controlTimeoutMs = message.strictLong("control_timeout_ms"),
        )
        if (!CurrentProtocol.acceptsReady(readyMessage, AudioEngine.SUPPORTED_WAKE_WORDS)) {
            terminateProtocol("invalid_ready")
            return
        }
        localCommandTimeoutMs = LocalCommandTimeoutPolicy.clientTimeoutMs(
            requireNotNull(readyMessage.controlTimeoutMs),
        )
        val allowed = selectedLocalWakeWords().toSet()
        val active = requireNotNull(readyMessage.wakeWords).filter { it in allowed }
        val immutable = Collections.unmodifiableList(ArrayList(active))
        wakeWords.set(immutable)
        audio.setWakeWords(active)
        synchronized(orderSendLock) {
            ready.set(true)
            reconnectAttempt.set(0)
            orderState.prepareConnectionStart()?.let {
                sendIfReady(ProtocolEncoder.orderStart(it))
            }
        }
    }

    private fun handleState(message: JSONObject) {
        val value = message.strictString("value")
        if (value == null || value !in CurrentProtocol.STATE_VALUES) {
            terminateProtocol("invalid_state")
            return
        }
        emit(FastVoiceEvent.StateChanged(FastVoiceState.fromRaw(value)))
    }

    private fun handleTranscript(message: JSONObject) {
        val role = message.strictString("role")
        val text = message.strictString("text")
        val final = message.strictBoolean("final")
        if (role !in setOf("user", "assistant") || text == null || final == null) {
            terminateProtocol("invalid_transcript")
            return
        }
        if (role == "assistant") cancelLocalFallback()
        emit(FastVoiceEvent.Transcript(requireNotNull(role), text, final))
    }

    private fun handleOrderAck(message: JSONObject) {
        val action = message.strictString("action")
        val id = message.strictString("id")
        val rev = message.strictLong("rev")
        if (action !in setOf("start", "update", "end") || id.isNullOrBlank() ||
            rev == null || rev < 0L
        ) {
            terminateProtocol("invalid_order_ack")
            return
        }
        val effect = synchronized(orderSendLock) {
            orderState.acknowledge(requireNotNull(action), id, rev).also {
                it.pendingEnd?.let { end ->
                    sendIfReady(ProtocolEncoder.orderEnd(end.id, end.rev, end.reason))
                }
            }
        }
        if (!effect.matched) {
            log(FastVoiceLogLevel.WARN, "ignored stale order acknowledgement")
            return
        }
        if (effect.ended) {
            audio.stopCapture()
            syncKwsSession()
            audio.setWakeArmed(false)
        } else if (effect.startAccepted) {
            syncKwsSession()
            audio.setWakeArmed(canArmWake())
        }
        emit(FastVoiceEvent.OrderAck(requireNotNull(action), id, rev))
    }

    private fun handleTourAck(message: JSONObject) {
        val id = message.strictString("id")
        if (id.isNullOrBlank()) {
            terminateProtocol("invalid_tour_ack")
            return
        }
        emit(FastVoiceEvent.TourAck(id))
    }

    private fun handlePlaybackStart(message: JSONObject) {
        val id = message.strictInt("id")
        val kind = message.strictString("kind")
        val tourId = when {
            !message.has("tour_id") -> null
            message.isNull("tour_id") -> ""
            else -> message.strictString("tour_id")?.takeIf(String::isNotBlank)
        }
        if (id == null || id < 0 || kind.isNullOrBlank() || tourId == null) {
            terminateProtocol("invalid_playback_start")
            return
        }
        cancelLocalFallback()
        synchronized(playbackControlLock) {
            clearLocalCommandPrePauseLocked(resume = false)
            playbackId.set(id)
            playbackTourId = tourId.takeIf(String::isNotEmpty)
            val epoch = playbackEpoch.incrementAndGet()
            playbackTerminalState.begin(id, epoch)
            serverPlaybackPaused.set(false)
            audio.setPromptPlayback(true)
            audio.setWakeArmed(false)
            audio.beginPlayback(id)
        }
    }

    private fun handlePlaybackEnd(message: JSONObject) {
        val id = message.strictInt("id")
        if (id == null || id != playbackId.get()) return
        val epoch = playbackEpoch.get()
        if (playbackTerminalState.failureReason(id, epoch) == null) audio.endPlayback()
    }

    private fun handleControl(message: JSONObject) {
        val commandId = message.strictString("id")
        val action = message.strictString("action")
        if (commandId.isNullOrBlank() || action.isNullOrBlank()) {
            terminateProtocol("invalid_control")
            return
        }
        var code: String? = null
        val applied = when (action) {
            "capture.start" -> {
                val preRollMs = message.strictInt("pre_roll_ms")
                if (!isOrderCaptureAllowed()) {
                    code = "order_inactive"
                    false
                } else if (preRollMs == null || preRollMs < 0) {
                    code = "invalid_pre_roll"
                    false
                } else {
                    audio.setWakeArmed(false)
                    audio.startCapture(preRollMs).also {
                        if (!it) code = "capture_unavailable"
                    }
                }
            }
            "capture.stop" -> {
                audio.stopCapture()
                audio.setWakeArmed(canArmWake())
                true
            }
            "playback.stop" -> applyPlaybackStop(message)
                .also { if (!it) code = "stale_playback" }
            "playback.pause" -> applyPlaybackPause(message)
                .also { if (!it) code = "playback_pause_failed" }
            "playback.resume" -> applyPlaybackResume(message)
                .also { if (!it) code = "playback_resume_failed" }
            "volume.up", "volume.down" -> {
                synchronized(playbackControlLock) {
                    val resume = clearLocalCommandPrePauseLocked(resume = true)
                    if (resume) audio.resumePlayback(playbackId.get())
                }
                val direction = if (action == "volume.up") {
                    AudioManager.ADJUST_RAISE
                } else {
                    AudioManager.ADJUST_LOWER
                }
                audio.adjustPlaybackVolume(direction).also {
                    if (!it) code = "volume_adjust_failed"
                }
            }
            else -> {
                code = "unsupported_action"
                false
            }
        }
        socket.get()?.takeIf { ready.get() }
            ?.send(ProtocolEncoder.controlResult(commandId, applied, code))
    }

    private fun applyPlaybackStop(message: JSONObject): Boolean {
        val target = message.strictInt("playback_id") ?: return false
        synchronized(playbackControlLock) {
            if (playbackId.get() < 0 && localFallbackActive.get()) {
                cancelLocalFallback()
                audio.setWakeArmed(canArmWake())
                return true
            }
            if (target != playbackId.get()) return false
            clearLocalCommandPrePauseLocked(resume = false)
            val epoch = playbackEpoch.incrementAndGet()
            playbackTerminalState.invalidate(target, epoch)
            playbackId.set(-1)
            playbackTourId = null
            serverPlaybackPaused.set(false)
            audio.interruptPlayback()
            audio.setPromptPlayback(false)
            audio.setWakeArmed(canArmWake())
            return true
        }
    }

    private fun applyPlaybackPause(message: JSONObject): Boolean {
        val target = message.strictInt("playback_id") ?: return false
        synchronized(playbackControlLock) {
            if (target != playbackId.get()) return false
            serverPlaybackPaused.set(true)
            clearLocalCommandPrePauseLocked(resume = false)
            return audio.pausePlayback(target)
        }
    }

    private fun applyPlaybackResume(message: JSONObject): Boolean {
        val target = message.strictInt("playback_id") ?: return false
        synchronized(playbackControlLock) {
            if (target != playbackId.get()) return false
            serverPlaybackPaused.set(false)
            clearLocalCommandPrePauseLocked(resume = false)
            return audio.resumePlayback(target)
        }
    }

    private fun handleControlDecision(message: JSONObject) {
        val id = message.strictString("id")
        val accepted = message.strictBoolean("accepted")
        if (id.isNullOrBlank() || accepted == null) {
            terminateProtocol("invalid_control_decision")
            return
        }
        applyLocalCommandDecision(id, accepted)
    }

    private fun handleError(message: JSONObject) {
        val scope = message.strictString("scope")
        val code = message.strictString("code")
        val recoverable = message.strictBoolean("recoverable")
        val rev = message.strictLong("rev")
        if (scope.isNullOrBlank() || code.isNullOrBlank() || recoverable == null ||
            (scope == "order" && (rev == null || rev < 0L))
        ) {
            terminateProtocol("invalid_error")
            return
        }
        if (scope == "order") {
            val effect = synchronized(orderSendLock) {
                orderState.fail(message.nullableString("ref"), requireNotNull(rev))
            }
            if (effect.matchedCurrent) {
                syncKwsSession()
                if (effect.restoreOrderAudio && effect.restoreCapture) {
                    audio.setWakeArmed(false)
                    audio.startCapture(0)
                } else {
                    audio.setWakeArmed(canArmWake())
                }
            }
        }
        val error = FastVoiceError(
            scope = scope,
            ref = message.nullableString("ref"),
            rev = rev,
            code = code,
            message = message.nullableString("message"),
            recoverable = recoverable,
            fallbackText = message.nullableString("fallback_text"),
        )
        emit(FastVoiceEvent.Error(error))
        error.fallbackText?.let(::startLocalFallback)
    }

    private fun handleLocalCommandCandidate(playback: Int, name: String) {
        if (!ready.get() || playback != playbackId.get()) {
            audio.resumePlayback(playback)
            return
        }
        val candidateId: String
        val epoch: Long
        synchronized(playbackControlLock) {
            epoch = playbackEpoch.get()
            if (playbackTerminalState.failureReason(playback, epoch) != null) {
                audio.resumePlayback(playback)
                return
            }
            candidateId = "lc-${localCommandSequence.incrementAndGet()}"
            if (!localCommandPrePauseState.begin(candidateId, playback, epoch)) return
            armLocalCommandTimeoutLocked(
                candidateId,
                playback,
                epoch,
                requireAccepted = false,
                timeoutMs = localCommandTimeoutMs,
            )
        }
        val sent = socket.get()?.takeIf { ready.get() }?.send(
            ProtocolEncoder.controlCandidate(candidateId, playback, name),
        ) == true
        if (!sent) applyLocalCommandDecision(candidateId, accepted = false)
    }

    private fun applyLocalCommandDecision(id: String, accepted: Boolean) {
        var resume = false
        synchronized(playbackControlLock) {
            val currentPlayback = playbackId.get()
            val epoch = playbackEpoch.get()
            val decision = if (accepted) "accepted" else "rejected"
            when (
                localCommandPrePauseState.decide(
                    id,
                    currentPlayback,
                    decision,
                    currentPlayback,
                    epoch,
                    serverPlaybackPaused.get(),
                )
            ) {
                LocalCommandPrePauseState.Outcome.IGNORED -> return
                LocalCommandPrePauseState.Outcome.ACCEPTED_HOLD -> {
                    cancelLocalCommandTimeoutLocked()
                    armLocalCommandTimeoutLocked(
                        id,
                        currentPlayback,
                        epoch,
                        requireAccepted = true,
                        timeoutMs = LocalCommandTimeoutPolicy.ACCEPTED_ACTION_TIMEOUT_MS,
                    )
                }
                LocalCommandPrePauseState.Outcome.RESUME -> {
                    cancelLocalCommandTimeoutLocked()
                    resume = true
                }
                LocalCommandPrePauseState.Outcome.CLEARED -> cancelLocalCommandTimeoutLocked()
            }
        }
        if (resume) audio.resumePlayback(playbackId.get())
    }

    private fun armLocalCommandTimeoutLocked(
        id: String,
        playback: Int,
        epoch: Long,
        requireAccepted: Boolean,
        timeoutMs: Long,
    ) {
        cancelLocalCommandTimeoutLocked()
        localCommandTimeoutFuture = scheduler.schedule({
            var resume = false
            synchronized(playbackControlLock) {
                when (
                    localCommandPrePauseState.timeout(
                        id,
                        playback,
                        epoch,
                        playbackId.get(),
                        playbackEpoch.get(),
                        serverPlaybackPaused.get(),
                        requireAccepted,
                    )
                ) {
                    LocalCommandPrePauseState.Outcome.RESUME -> resume = true
                    else -> Unit
                }
                localCommandTimeoutFuture = null
            }
            if (resume) audio.resumePlayback(playback)
        }, timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun cancelLocalCommandTimeoutLocked() {
        localCommandTimeoutFuture?.cancel(false)
        localCommandTimeoutFuture = null
    }

    private fun clearLocalCommandPrePauseLocked(resume: Boolean): Boolean {
        cancelLocalCommandTimeoutLocked()
        val cleared = localCommandPrePauseState.clear()
        return cleared && resume && !serverPlaybackPaused.get()
    }

    private fun handlePlaybackFinished(id: Int) {
        var finished = false
        var tourId: String? = null
        synchronized(playbackControlLock) {
            val epoch = playbackEpoch.get()
            if (id == playbackId.get() && playbackTerminalState.finish(id, epoch)) {
                clearLocalCommandPrePauseLocked(resume = false)
                playbackId.set(-1)
                tourId = playbackTourId
                playbackTourId = null
                serverPlaybackPaused.set(false)
                audio.setPromptPlayback(false)
                audio.setWakeArmed(canArmWake())
                finished = true
            }
        }
        if (finished) {
            socket.get()?.takeIf { ready.get() }?.send(ProtocolEncoder.playbackFinished(id))
            emit(FastVoiceEvent.PlaybackFinished(id, tourId))
            tourId?.let { log(FastVoiceLogLevel.DEBUG, "tour playback finished: $it") }
        }
    }

    private fun handlePlaybackFailed(id: Int, reason: String) {
        val code = playbackFailureCode(reason)
        var report = false
        var tourId: String? = null
        synchronized(playbackControlLock) {
            val epoch = playbackEpoch.get()
            if (id != playbackId.get()) return
            if (playbackTerminalState.fail(id, epoch, reason) ==
                PlaybackTerminalState.FailureResult.RECORDED
            ) {
                clearLocalCommandPrePauseLocked(resume = false)
                playbackId.set(-1)
                tourId = playbackTourId
                playbackTourId = null
                serverPlaybackPaused.set(false)
                audio.setPromptPlayback(false)
                audio.setWakeArmed(canArmWake())
                report = true
            }
        }
        if (report) {
            socket.get()?.takeIf { ready.get() }?.send(ProtocolEncoder.playbackFailed(id, code))
            emit(FastVoiceEvent.PlaybackFailed(id, tourId, code))
            tourId?.let { log(FastVoiceLogLevel.DEBUG, "tour playback failed: $it code=$code") }
        }
    }

    private fun invalidatePlayback() {
        synchronized(playbackControlLock) {
            val current = playbackId.getAndSet(-1)
            playbackTourId = null
            val epoch = playbackEpoch.incrementAndGet()
            if (current >= 0) playbackTerminalState.invalidate(current, epoch)
            clearLocalCommandPrePauseLocked(resume = false)
            serverPlaybackPaused.set(false)
            audio.interruptPlayback()
            audio.setPromptPlayback(false)
            audio.setWakeArmed(canArmWake())
        }
    }

    private fun sendIfReady(message: String): Boolean =
        socket.get()?.takeIf { ready.get() }?.send(message) == true

    private fun canArmWake(): Boolean =
        started.get() && ready.get() && config.wakeEnabled &&
            wakeWords.get().isNotEmpty() && playbackId.get() < 0 &&
            !localFallbackActive.get() && !audio.isUplinkEnabled() &&
            isOrderCaptureAllowed()

    private fun isOrderCaptureAllowed(): Boolean = orderState.captureAllowed()

    private fun syncKwsSession() {
        audio.setKwsSessionEnabled(
            orderState.wakeKwsEnabled(
                started = started.get(),
                ready = ready.get(),
                wakeRequested = config.wakeEnabled,
            ),
        )
    }

    private fun selectedLocalWakeWords(): List<String> =
        if (config.preferredWakeWords.isEmpty()) AudioEngine.SUPPORTED_WAKE_WORDS.toList()
        else config.preferredWakeWords

    private fun startLocalFallback(text: String) {
        val player = fallbackPromptPlayer ?: return
        cancelLocalFallback()
        val generation = localFallbackGeneration.incrementAndGet()
        localFallbackActive.set(true)
        audio.stopCapture()
        audio.setWakeArmed(false)
        audio.setPromptPlayback(true)
        if (!player.speak(text) { success ->
                if (localFallbackGeneration.get() != generation) return@speak
                localFallbackActive.set(false)
                audio.setPromptPlayback(playbackId.get() >= 0)
                audio.setWakeArmed(canArmWake())
                if (!success) emitLocalError("fallback_tts_unavailable")
            }
        ) {
            localFallbackActive.set(false)
            audio.setPromptPlayback(playbackId.get() >= 0)
            audio.setWakeArmed(canArmWake())
            emitLocalError("fallback_tts_unavailable")
        }
    }

    private fun cancelLocalFallback() {
        localFallbackGeneration.incrementAndGet()
        localFallbackActive.set(false)
        fallbackPromptPlayer?.stop()
        audio.setPromptPlayback(playbackId.get() >= 0)
    }

    private fun handleDisconnected(
        session: Long,
        webSocket: WebSocket,
        code: String?,
        message: String?,
        cause: Throwable?,
    ) {
        var current = false
        sessions.runIfCurrent(session) {
            current = socket.compareAndSet(webSocket, null)
            if (current) {
                synchronized(orderSendLock) {
                    ready.set(false)
                    orderState.markConnectionUnready()
                }
                wakeWords.set(emptyList())
                audio.setKwsSessionEnabled(false)
                audio.setWakeArmed(false)
                audio.stopCapture()
                cancelLocalFallback()
                invalidatePlayback()
            }
        }
        if (!current) return
        code?.let { emitLocalError(it, message, cause) }
        scheduleReconnect(session)
    }

    private fun terminateProtocol(code: String, cause: Throwable? = null) {
        emitLocalError(code, cause?.message, cause)
        synchronized(orderSendLock) {
            ready.set(false)
            orderState.markConnectionUnready()
        }
        audio.setKwsSessionEnabled(false)
        audio.setWakeArmed(false)
        audio.stopCapture()
        cancelLocalFallback()
        invalidatePlayback()
        socket.get()?.close(1_002, code)
    }

    private fun clearConnectionState() {
        wakeWords.set(emptyList())
        invalidatePlayback()
    }

    private fun scheduleReconnect(session: Long) {
        if (!started.get() || !config.autoReconnect || !sessions.isCurrent(session)) return
        val attempt = reconnectAttempt.getAndIncrement()
        val delays = longArrayOf(1, 2, 4, 8, 15, 30)
        val delay = delays[minOf(attempt, delays.lastIndex)]
        reconnectFuture?.cancel(false)
        reconnectFuture = scheduler.schedule({ connect(session) }, delay, TimeUnit.SECONDS)
    }

    private fun playbackFailureCode(reason: String): String = when {
        "decode" in reason -> "opus_decode_failed"
        "empty audio stream" in reason -> "audio_stream_empty"
        "write" in reason -> "audio_track_write_failed"
        "drain" in reason -> "audio_track_drain_failed"
        "pause" in reason || "resume" in reason || "play " in reason ->
            "audio_track_control_failed"
        else -> "audio_playback_failed"
    }

    private fun emitLocalError(
        code: String,
        message: String? = null,
        cause: Throwable? = null,
    ) {
        emit(
            FastVoiceEvent.Error(
                FastVoiceError(
                    scope = "sdk",
                    code = code,
                    message = message,
                    recoverable = true,
                    cause = cause,
                ),
            ),
        )
    }

    private fun emit(event: FastVoiceEvent) {
        mainHandler.post {
            runCatching { listener.onEvent(event) }.onFailure {
                log(FastVoiceLogLevel.ERROR, "listener callback failed", it)
            }
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
