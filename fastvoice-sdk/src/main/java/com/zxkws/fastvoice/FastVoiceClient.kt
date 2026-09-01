package com.zxkws.fastvoice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.zxkws.fastvoice.internal.AudioEngine
import com.zxkws.fastvoice.internal.CurrentProtocol
import com.zxkws.fastvoice.internal.LocalCommandPrePauseState
import com.zxkws.fastvoice.internal.LocalCommandTimeoutPolicy
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
    private val listener: FastVoiceListener = FastVoiceListener { },
) : Closeable {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)
    private val reconnectAttempt = AtomicInteger(0)
    private val connectionEpoch = AtomicLong(0)
    private val locationRequestEpoch = AtomicLong(0)
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

    private data class LocationSnapshot(
        val stationName: String? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
    )

    // Keep location state mutation and its wire emission ordered. In particular, a stale
    // reconnect/provider update must never be emitted after clearLocation().
    private val locationLock = Any()
    private var locationSnapshot = LocationSnapshot()
    private var playbackContentId: String? = null

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "fastvoice-scheduler").apply { isDaemon = true }
    }
    private var reconnectFuture: ScheduledFuture<*>? = null

    private val httpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private lateinit var audio: AudioEngine
    private val audioCallback = object : AudioEngine.Callback {
            override fun onWakeWord(word: String): Boolean {
                requestHostLocation()
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

            override fun onTrace(message: String) {
                log(FastVoiceLogLevel.DEBUG, message, null)
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
        val supportedAbis = setOf("arm64-v8a", "armeabi-v7a")
        if (Build.SUPPORTED_ABIS.none { it in supportedAbis }) {
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
        connect(connectionEpoch.incrementAndGet())
        return true
    }

    @Synchronized
    fun stop() {
        if (!started.compareAndSet(true, false)) return
        connectionEpoch.incrementAndGet()
        locationRequestEpoch.incrementAndGet()
        ready.set(false)
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        audio.setKwsSessionEnabled(false)
        audio.setWakeArmed(false)
        audio.stopCapture()
        socket.getAndSet(null)?.close(1_000, "client stop")
        clearConnectionState()
        audio.stop()
    }

    /** Stops local output immediately and cancels the current server turn. */
    fun interrupt() {
        invalidatePlayback()
        socket.get()?.takeIf { ready.get() }?.send(ProtocolEncoder.turnCancel())
    }

    /**
     * Updates the device's current location context.
     *
     * `true` means the SDK sent (or will retry) the update; only
     * [FastVoiceEvent.LocationAck] means the server accepted it.
     */
    fun updateLocation(stationName: String): Boolean {
        check(!closed.get()) { "FastVoiceClient is closed" }
        val normalizedStationName = stationName.trim()
        require(normalizedStationName.isNotEmpty()) { "stationName must not be blank" }
        if (!started.get()) return false
        return synchronized(locationLock) {
            locationSnapshot = locationSnapshot.copy(stationName = normalizedStationName)
            sendIfReady(
                ProtocolEncoder.locationUpdate(
                    locationSnapshot.stationName,
                    locationSnapshot.latitude,
                    locationSnapshot.longitude,
                ),
            )
        }
    }

    /** Updates the coordinates used by server-side location services such as weather. */
    fun updateCoordinates(latitude: Double, longitude: Double): Boolean {
        check(!closed.get()) { "FastVoiceClient is closed" }
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "latitude must be finite and within -90..90"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "longitude must be finite and within -180..180"
        }
        if (!started.get()) return false
        return synchronized(locationLock) {
            locationSnapshot = locationSnapshot.copy(
                latitude = latitude,
                longitude = longitude,
            )
            sendIfReady(
                ProtocolEncoder.locationUpdate(
                    null,
                    latitude,
                    longitude,
                ),
            )
        }
    }

    fun clearLocation(): Boolean {
        check(!closed.get()) { "FastVoiceClient is closed" }
        if (!started.get()) return false
        return synchronized(locationLock) {
            locationRequestEpoch.incrementAndGet()
            locationSnapshot = LocationSnapshot()
            sendIfReady(ProtocolEncoder.locationClear())
        }
    }

    @JvmOverloads
    fun playWelcome(stationName: String? = null): Boolean {
        check(!closed.get()) { "FastVoiceClient is closed" }
        if (!started.get()) return false
        return sendIfReady(ProtocolEncoder.welcomePlay(stationName))
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stop()
        scheduler.shutdownNow()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    private fun connect(session: Long) {
        if (!started.get() || closed.get() || connectionEpoch.get() != session) return
        val request = runCatching {
            Request.Builder()
                .url(config.endpoint)
                .build()
        }.getOrElse { error ->
            emitLocalError("connection_configuration_invalid", error.message, error)
            started.set(false)
            audio.stop()
            return
        }
        log(FastVoiceLogLevel.INFO, "opening WebSocket")
        val webSocket = httpClient.newWebSocket(request, SocketListener(session))
        if (connectionEpoch.get() == session && started.get()) {
            socket.getAndSet(webSocket)?.cancel()
        } else {
            webSocket.cancel()
        }
    }

    private inner class SocketListener(
        private val session: Long,
    ) : WebSocketListener() {
        private val terminated = AtomicBoolean(false)

        override fun onOpen(webSocket: WebSocket, response: Response) {
            val current = started.get() && connectionEpoch.get() == session &&
                socket.get() === webSocket
            if (!current) {
                webSocket.close(1_000, "stale connection")
                return
            }
            webSocket.send(ProtocolEncoder.hello(config.areaId))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (connectionEpoch.get() != session) return
            if (!started.get() || socket.get() !== webSocket) return
            if (!ready.get() || playbackId.get() < 0) {
                terminateProtocol("binary_before_playback")
                return
            }
            audio.enqueueOpus(bytes.toByteArray())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val parsed = runCatching { JSONObject(text) }
            if (connectionEpoch.get() != session) return
            if (!started.get() || socket.get() !== webSocket) return
            parsed.fold(
                onSuccess = ::handleServerMessage,
                onFailure = { terminateProtocol("invalid_server_json", it) },
            )
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
        if (!ready.get() && type != "ready") {
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
            "location.ack" -> handleLocationAck(message)
            "welcome.ack" -> handleWelcomeAck(message)
            "playback.start" -> handlePlaybackStart(message)
            "playback.end" -> handlePlaybackEnd(message)
            "control" -> handleControl(message)
            "control.decision" -> handleControlDecision(message)
            "error" -> handleError(message)
            else -> terminateProtocol("unsupported_server_message")
        }
    }

    private fun handleReady(message: JSONObject) {
        if (!message.hasExactFields(CurrentProtocol.READY_FIELDS)) {
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
        ready.set(true)
        reconnectAttempt.set(0)
        audio.setKwsSessionEnabled(true)
        audio.setWakeArmed(canArmWake())
        sendStoredLocation()
        requestHostLocation()
    }

    private fun sendStoredLocation() {
        synchronized(locationLock) {
            val snapshot = locationSnapshot
            if (snapshot.stationName == null && snapshot.latitude == null) return
            sendIfReady(
                ProtocolEncoder.locationUpdate(
                    snapshot.stationName,
                    snapshot.latitude,
                    snapshot.longitude,
                ),
            )
        }
    }

    private fun requestHostLocation() {
        val getLocation = config.getLocation ?: return
        if (!started.get() || closed.get()) return
        val request = locationRequestEpoch.incrementAndGet()
        mainHandler.post {
            if (!started.get() || closed.get() || locationRequestEpoch.get() != request) {
                return@post
            }
            val completed = AtomicBoolean(false)
            try {
                getLocation { json ->
                    if (!completed.compareAndSet(false, true)) return@getLocation
                    acceptHostLocation(request, json)
                }
            } catch (error: Exception) {
                if (completed.compareAndSet(false, true)) {
                    emitLocalError("location_provider_failed", error.message, error)
                }
            }
        }
    }

    private fun acceptHostLocation(request: Long, json: JSONObject?) {
        if (json == null || !started.get() || closed.get()) {
            return
        }
        val latitude = (json.opt("latitude") as? Number)?.toDouble()
        val longitude = (json.opt("longitude") as? Number)?.toDouble()
        if (latitude == null || longitude == null || !latitude.isFinite() ||
            !longitude.isFinite() || latitude !in -90.0..90.0 ||
            longitude !in -180.0..180.0
        ) {
            emitLocalError("location_provider_invalid_result")
            return
        }
        synchronized(locationLock) {
            if (!started.get() || closed.get() || locationRequestEpoch.get() != request) {
                return
            }
            locationSnapshot = locationSnapshot.copy(
                latitude = latitude,
                longitude = longitude,
            )
            sendIfReady(
                ProtocolEncoder.locationUpdate(
                    null,
                    latitude,
                    longitude,
                ),
            )
        }
    }

    private fun handleState(message: JSONObject) {
        if (!message.hasExactFields(setOf("type", "value"))) {
            terminateProtocol("invalid_state")
            return
        }
        val value = message.strictString("value")
        if (value == null || value !in CurrentProtocol.STATE_VALUES) {
            terminateProtocol("invalid_state")
            return
        }
        emit(FastVoiceEvent.StateChanged(FastVoiceState(value)))
    }

    private fun handleTranscript(message: JSONObject) {
        if (!message.hasExactFields(setOf("type", "role", "text", "final"))) {
            terminateProtocol("invalid_transcript")
            return
        }
        val role = message.strictString("role")
        val text = message.strictString("text")
        val final = message.strictBoolean("final")
        if (role !in setOf("user", "assistant") || text == null || final == null) {
            terminateProtocol("invalid_transcript")
            return
        }
        emit(FastVoiceEvent.Transcript(requireNotNull(role), text, final))
    }

    private fun handleLocationAck(message: JSONObject) {
        if (!message.hasExactFields(setOf("type")) &&
            !message.hasExactFields(setOf("type", "station_name"))
        ) {
            terminateProtocol("invalid_location_ack")
            return
        }
        val stationName = if (message.has("station_name") && !message.isNull("station_name")) {
            message.strictString("station_name")
        } else {
            null
        }
        if (message.has("station_name") && stationName.isNullOrBlank()) {
            terminateProtocol("invalid_location_ack")
            return
        }
        emit(FastVoiceEvent.LocationAck(stationName))
    }

    private fun handleWelcomeAck(message: JSONObject) {
        if (!message.hasExactFields(setOf("type")) &&
            !message.hasExactFields(setOf("type", "station_name"))
        ) {
            terminateProtocol("invalid_welcome_ack")
            return
        }
        val stationName = if (message.has("station_name") && !message.isNull("station_name")) {
            message.strictString("station_name")
        } else {
            null
        }
        if (message.has("station_name") && stationName.isNullOrBlank()) {
            terminateProtocol("invalid_welcome_ack")
            return
        }
        emit(FastVoiceEvent.WelcomeAck(stationName))
    }

    private fun handlePlaybackStart(message: JSONObject) {
        if (!message.hasExactFields(setOf("type", "id", "kind", "content_id"))) {
            terminateProtocol("invalid_playback_start")
            return
        }
        val id = message.strictInt("id")
        val kind = message.strictString("kind")
        val contentId = when {
            !message.has("content_id") -> null
            message.isNull("content_id") -> ""
            else -> message.strictString("content_id")?.takeIf(String::isNotBlank)
        }
        if (id == null || id < 0 || kind.isNullOrBlank() || contentId == null) {
            terminateProtocol("invalid_playback_start")
            return
        }
        synchronized(playbackControlLock) {
            clearLocalCommandPrePauseLocked(resume = false)
            playbackId.set(id)
            playbackContentId = contentId.takeIf(String::isNotEmpty)
            val epoch = playbackEpoch.incrementAndGet()
            playbackTerminalState.begin(id, epoch)
            serverPlaybackPaused.set(false)
            audio.setPromptPlayback(true)
            audio.setWakeArmed(false)
            audio.beginPlayback(id)
        }
    }

    private fun handlePlaybackEnd(message: JSONObject) {
        if (!message.hasExactFields(setOf("type", "id"))) {
            terminateProtocol("invalid_playback_end")
            return
        }
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
        val expectedFields = when (action) {
            "capture.start" -> setOf("type", "id", "action", "pre_roll_ms")
            "playback.stop", "playback.pause", "playback.resume" ->
                setOf("type", "id", "action", "playback_id")
            else -> setOf("type", "id", "action")
        }
        if (!message.hasExactFields(expectedFields)) {
            terminateProtocol("invalid_control")
            return
        }
        var code: String? = null
        val applied = when (action) {
            "capture.start" -> {
                val preRollMs = message.strictInt("pre_roll_ms")
                if (preRollMs == null || preRollMs < 0) {
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
            if (target != playbackId.get()) return false
            clearLocalCommandPrePauseLocked(resume = false)
            val epoch = playbackEpoch.incrementAndGet()
            playbackTerminalState.invalidate(target, epoch)
            playbackId.set(-1)
            playbackContentId = null
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
        if (!message.hasExactFields(setOf("type", "id", "accepted"))) {
            terminateProtocol("invalid_control_decision")
            return
        }
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
        val requiredFields = mutableSetOf("type", "scope", "ref", "code", "recoverable")
        if (!message.hasExactFields(
                requiredFields,
                optional = setOf("message", "fallback_text", "rev"),
            )
        ) {
            terminateProtocol("invalid_error")
            return
        }
        val ref = message.strictString("ref")
        val code = message.strictString("code")
        val recoverable = message.strictBoolean("recoverable")
        val rev = message.strictLong("rev")
        val detail = message.optionalStrictString("message")
        val fallbackText = message.optionalStrictString("fallback_text")
        if (scope.isNullOrBlank() || ref == null || code.isNullOrBlank() || recoverable == null ||
            detail.invalid || fallbackText.invalid
        ) {
            terminateProtocol("invalid_error")
            return
        }
        val error = FastVoiceError(
            scope = scope,
            ref = ref,
            rev = rev,
            code = code,
            message = detail.value,
            recoverable = recoverable,
            fallbackText = fallbackText.value,
        )
        emit(FastVoiceEvent.Error(error))
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
        var contentId: String? = null
        synchronized(playbackControlLock) {
            val epoch = playbackEpoch.get()
            if (id == playbackId.get() && playbackTerminalState.finish(id, epoch)) {
                clearLocalCommandPrePauseLocked(resume = false)
                playbackId.set(-1)
                contentId = playbackContentId
                playbackContentId = null
                serverPlaybackPaused.set(false)
                audio.setPromptPlayback(false)
                audio.setWakeArmed(canArmWake())
                finished = true
            }
        }
        if (finished) {
            socket.get()?.takeIf { ready.get() }?.send(ProtocolEncoder.playbackFinished(id))
            emit(FastVoiceEvent.PlaybackFinished(id, contentId))
            contentId?.let { log(FastVoiceLogLevel.DEBUG, "content playback finished: $it") }
        }
    }

    private fun handlePlaybackFailed(id: Int, reason: String) {
        val code = playbackFailureCode(reason)
        var report = false
        var contentId: String? = null
        synchronized(playbackControlLock) {
            val epoch = playbackEpoch.get()
            if (id != playbackId.get()) return
            if (playbackTerminalState.fail(id, epoch, reason) ==
                PlaybackTerminalState.FailureResult.RECORDED
            ) {
                clearLocalCommandPrePauseLocked(resume = false)
                playbackId.set(-1)
                contentId = playbackContentId
                playbackContentId = null
                serverPlaybackPaused.set(false)
                audio.setPromptPlayback(false)
                audio.setWakeArmed(canArmWake())
                report = true
            }
        }
        if (report) {
            socket.get()?.takeIf { ready.get() }?.send(ProtocolEncoder.playbackFailed(id, code))
            emit(FastVoiceEvent.PlaybackFailed(id, contentId, code))
            contentId?.let { log(FastVoiceLogLevel.DEBUG, "content playback failed: $it code=$code") }
        }
    }

    private fun invalidatePlayback() {
        synchronized(playbackControlLock) {
            val current = playbackId.getAndSet(-1)
            playbackContentId = null
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
        started.get() && ready.get() &&
            wakeWords.get().isNotEmpty() && playbackId.get() < 0 &&
            !audio.isUplinkEnabled()

    private fun selectedLocalWakeWords(): List<String> =
        if (config.preferredWakeWords.isEmpty()) AudioEngine.SUPPORTED_WAKE_WORDS.toList()
        else config.preferredWakeWords

    private fun handleDisconnected(
        session: Long,
        webSocket: WebSocket,
        code: String?,
        message: String?,
        cause: Throwable?,
    ) {
        if (connectionEpoch.get() != session) return
        val current = socket.compareAndSet(webSocket, null)
        if (current) {
            ready.set(false)
            wakeWords.set(emptyList())
            audio.setKwsSessionEnabled(false)
            audio.setWakeArmed(false)
            audio.stopCapture()
            invalidatePlayback()
        }
        if (!current) return
        code?.let { emitLocalError(it, message, cause) }
        scheduleReconnect(session)
    }

    private fun terminateProtocol(code: String, cause: Throwable? = null) {
        emitLocalError(code, cause?.message, cause)
        ready.set(false)
        audio.setKwsSessionEnabled(false)
        audio.setWakeArmed(false)
        audio.stopCapture()
        invalidatePlayback()
        socket.get()?.close(1_002, code)
    }

    private fun clearConnectionState() {
        wakeWords.set(emptyList())
        invalidatePlayback()
    }

    private fun scheduleReconnect(session: Long) {
        if (!started.get() || connectionEpoch.get() != session) return
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

    companion object {
        @JvmField
        val SUPPORTED_WAKE_WORDS: List<String> = Collections.unmodifiableList(
            ArrayList(AudioEngine.SUPPORTED_WAKE_WORDS),
        )
    }
}

private fun JSONObject.strictString(name: String): String? = opt(name) as? String

private fun JSONObject.strictBoolean(name: String): Boolean? = opt(name) as? Boolean

private fun JSONObject.hasExactFields(
    required: Set<String>,
    optional: Set<String> = emptySet(),
): Boolean {
    val actual = keys().asSequence().toSet()
    return actual.containsAll(required) && actual.all { it in required || it in optional }
}

private data class OptionalString(val value: String?, val invalid: Boolean)

private fun JSONObject.optionalStrictString(name: String): OptionalString {
    if (!has(name)) return OptionalString(null, false)
    return (opt(name) as? String)
        ?.let { OptionalString(it, false) }
        ?: OptionalString(null, true)
}

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
