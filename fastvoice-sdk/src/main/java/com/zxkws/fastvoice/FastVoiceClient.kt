package com.zxkws.fastvoice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.zxkws.fastvoice.internal.AudioEngine
import com.zxkws.fastvoice.internal.MonotonicVersionStore
import com.zxkws.fastvoice.internal.ProtocolEncoder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.Closeable
import java.net.Proxy
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

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

    private data class PendingArrival(
        val arrival: SpotArrival,
        val arrivedContext: VehicleContext,
    )

    private data class InFlightArrival(
        val arrival: SpotArrival,
        val contextVersion: Long,
    )

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val helloReady = AtomicBoolean(false)
    private val connectionGeneration = AtomicLong(0)
    private val reconnectAttempt = AtomicInteger(0)
    private val socket = AtomicReference<WebSocket?>(null)
    private val wakeWords = AtomicReference<List<String>>(emptyList())
    private val resumeUplinkAfterPlayback = AtomicBoolean(false)
    private val promptDoneType = AtomicReference<String?>(null)
    private val pendingArrival = AtomicReference<PendingArrival?>(null)
    private val inFlightArrivalContexts = ConcurrentHashMap<Long, InFlightArrival>()
    private val arrivalEventIds = ConcurrentHashMap<String, SpotArrival>()
    private val lastContext = AtomicReference<VehicleContext?>(null)

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "fastvoice-scheduler").apply { isDaemon = true }
    }
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var contextRefreshFuture: ScheduledFuture<*>? = null

    private val versions = config.deviceId?.let { deviceId ->
        MonotonicVersionStore(appContext, config.endpoint, deviceId)
    }

    private val httpClient = OkHttpClient.Builder()
        .apply { if (config.bypassSystemProxy) proxy(Proxy.NO_PROXY) }
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private lateinit var audio: AudioEngine

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

            override fun onLocalCommandCandidate(text: String) {
                socket.get()?.takeIf { helloReady.get() }
                    ?.send(ProtocolEncoder.localCommandCandidate(text))
            }

            override fun onUplinkPacket(packet: ByteArray) {
                socket.get()?.takeIf { helloReady.get() }?.send(ByteString.of(*packet))
            }

            override fun onPlaybackStarted() = Unit

            override fun onPlaybackFinished() {
                promptDoneType.getAndSet(null)?.let { type ->
                    socket.get()?.takeIf { helloReady.get() }
                        ?.send(ProtocolEncoder.promptDone(type))
                }
                audio.setPromptPlayback(false)
                if (resumeUplinkAfterPlayback.compareAndSet(true, false) && helloReady.get()) {
                    audio.setUplinkEnabled(true)
                }
            }

            override fun onDiagnostic(code: String, message: String, error: Throwable?) {
                log(
                    if (error == null) FastVoiceLogLevel.INFO else FastVoiceLogLevel.ERROR,
                    "$code: $message",
                    error,
                )
                // A microphone/speaker/codec failure affects the host; an expected source fallback
                // remains a diagnostic only.
                if (code != "microphone_source_fallback") {
                    emitError(code = code, message = message.ifEmpty { null }, cause = error)
                }
            }
            },
        )
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
    fun start(): Boolean {
        check(!closed.get()) { "FastVoiceClient is closed" }
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
        connect(connectionGeneration.incrementAndGet())
        return true
    }

    /** Stops audio and reconnect work. The same instance may be started again. */
    fun stop() {
        if (!started.compareAndSet(true, false)) return
        connectionGeneration.incrementAndGet()
        helloReady.set(false)
        audio.setWakeArmed(false)
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        contextRefreshFuture?.cancel(false)
        contextRefreshFuture = null
        socket.getAndSet(null)?.close(1_000, "client stop")
        clearConnectionScopedState()
        audio.stop()
    }

    /** Immediately stops local playback and asks the server to cancel the active response. */
    fun interrupt() {
        audio.interruptPlayback()
        promptDoneType.set(null)
        resumeUplinkAfterPlayback.set(false)
        socket.get()?.takeIf { helloReady.get() }
            ?.send(ProtocolEncoder.localCommandCandidate("停止"))
    }

    /**
     * Queues the latest trusted context and sends it as soon as device authentication completes.
     * Returns false only when this client was created without device credentials.
     */
    fun updateContext(context: VehicleContext): Boolean {
        if (!requireTrustedDevice("context_credentials_required")) return false
        lastContext.set(context)
        if (helloReady.get()) sendContext(context, arrival = null)
        scheduleContextRefresh()
        return true
    }

    /**
     * Atomically prepares an `arrived` trusted context and, after server acknowledgement, sends the
     * matching arrival event. IDs, versions, timestamps, and event IDs are managed by the SDK.
     */
    fun reportArrival(arrival: SpotArrival): Boolean {
        if (!requireTrustedDevice("arrival_credentials_required")) return false
        val current = lastContext.get()
        val arrived = VehicleContext(
            parkId = arrival.parkId,
            routeId = arrival.routeId,
            currentSpotId = arrival.spotId,
            currentStationId = arrival.stationId,
            nextStationId = current?.nextStationId,
            destinationId = current?.destinationId,
            vehicleName = current?.vehicleName,
            currentLocation = current?.currentLocation,
            routeName = current?.routeName,
            destination = current?.destination,
            nextStop = current?.nextStop,
            operationStatus = VehicleContext.STATUS_ARRIVED,
            speedMps = current?.speedMps,
            batteryPercent = current?.batteryPercent,
            passengerCount = current?.passengerCount,
            latitude = current?.latitude,
            longitude = current?.longitude,
        )
        lastContext.set(arrived)
        val pending = PendingArrival(arrival, arrived)
        pendingArrival.set(pending)
        if (helloReady.get()) {
            pendingArrival.compareAndSet(pending, null)
            sendContext(arrived, arrival)
        }
        scheduleContextRefresh()
        return true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stop()
        scheduler.shutdownNow()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    private fun connect(generation: Long) {
        if (!started.get() || closed.get() || generation != connectionGeneration.get()) return
        val request = runCatching { Request.Builder().url(config.endpoint).build() }
            .getOrElse {
                emitError(code = "invalid_endpoint", message = it.message, cause = it)
                started.set(false)
                audio.stop()
                return
            }
        log(FastVoiceLogLevel.INFO, "opening WebSocket")
        val webSocket = httpClient.newWebSocket(request, SocketListener(generation))
        socket.getAndSet(webSocket)?.cancel()
    }

    private inner class SocketListener(
        private val generation: Long,
    ) : WebSocketListener() {
        private val terminated = AtomicBoolean(false)

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrent(generation, webSocket)) {
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
            webSocket.send(hello)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (isCurrent(generation, webSocket)) audio.enqueueOpus(bytes.toByteArray())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent(generation, webSocket)) return
            handleServerText(text)
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

    private fun handleServerText(text: String) {
        val message = runCatching { JSONObject(text) }.getOrElse { error ->
            emitError(code = "invalid_server_message", message = error.message, cause = error)
            return
        }
        when (message.optString("type")) {
            "hello" -> handleHello(message)
            "state" -> handleState(message.optString("value"))
            "wake_ack" -> handleWakeAcknowledgement(message)
            "spot_arrival_prompt" -> handleServerPrompt(message, "spot_prompt_done")
            "asr" -> emit(FastVoiceEvent.Asr(message.optString("text")))
            "reply_delta" -> emit(FastVoiceEvent.ReplyDelta(message.optString("text")))
            "turn_error" -> {
                audio.setUplinkEnabled(false)
                resumeUplinkAfterPlayback.set(true)
                emitError(
                    code = message.nullableString("code"),
                    message = message.nullableString("message"),
                    prompt = message.nullableString("prompt"),
                )
            }
            "audio_end" -> audio.endPlayback()
            "interrupt" -> {
                audio.interruptPlayback()
                promptDoneType.set(null)
                resumeUplinkAfterPlayback.set(false)
                audio.setPromptPlayback(false)
            }
            "context_updated" -> handleContextUpdated(message)
            "context_error" -> handleContextError(message)
            "arrival_accepted" -> handleArrivalAccepted(message)
            "arrival_error" -> handleArrivalError(message)
        }
    }

    private fun handleHello(message: JSONObject) {
        val negotiated = linkedSetOf<String>()
        val wake = message.optJSONObject("wake")
        val words = wake?.optJSONArray("words")
        if (words != null) {
            for (index in 0 until words.length()) {
                words.optString(index).takeIf(String::isNotBlank)?.let(negotiated::add)
            }
        }
        wake?.optString("word")?.takeIf(String::isNotBlank)?.let(negotiated::add)
        val active = if (wake?.optBoolean("enabled") == true) {
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

        pendingArrival.getAndSet(null)?.let { pending ->
            sendContext(pending.arrivedContext, pending.arrival)
        } ?: lastContext.get()?.let { context ->
            sendContext(context, arrival = null)
        }
        scheduleContextRefresh()
    }

    private fun handleState(rawValue: String) {
        if (rawValue == FastVoiceState.SLEEPING.value) {
            promptDoneType.set(null)
            resumeUplinkAfterPlayback.set(false)
            audio.setPromptPlayback(false)
            audio.setUplinkEnabled(false)
            audio.setWakeArmed(
                config.wakeEnabled && helloReady.get() && wakeWords.get().isNotEmpty(),
            )
        } else if (!config.wakeEnabled && rawValue == FastVoiceState.LISTENING.value) {
            audio.setWakeArmed(false)
            audio.setUplinkEnabled(true)
        }
        emit(FastVoiceEvent.StateChanged(FastVoiceState.fromRaw(rawValue)))
    }

    private fun handleWakeAcknowledgement(message: JSONObject) {
        if (!message.optBoolean("awaiting_command")) return
        if (message.optString("audio") == "server") {
            handleServerPrompt(message, "wake_prompt_done")
        } else {
            // Current servers always provide the configured server voice. On an older server, do
            // not invoke Android TTS with a different voice; simply open the capture window.
            socket.get()?.send(ProtocolEncoder.promptDone("wake_prompt_done"))
            audio.setUplinkEnabled(true)
        }
    }

    private fun handleServerPrompt(message: JSONObject, doneType: String) {
        if (message.optString("audio") != "server") return
        promptDoneType.set(doneType)
        resumeUplinkAfterPlayback.set(true)
        audio.setWakeArmed(false)
        audio.setPromptPlayback(true)
        audio.setUplinkEnabled(false)
    }

    private fun handleContextUpdated(message: JSONObject) {
        val version = message.optLong("version", -1L)
        emit(FastVoiceEvent.ContextUpdated(version))
        val pending = inFlightArrivalContexts.remove(version) ?: return
        sendArrival(pending.arrival)
    }

    private fun handleContextError(message: JSONObject) {
        val code = message.nullableString("code")
        val errorMessage = message.nullableString("message")
        // The server currently does not echo the rejected version. There can be only one arrival
        // context request at a time, so fail it explicitly instead of sending an untrusted event.
        if (inFlightArrivalContexts.isNotEmpty()) {
            inFlightArrivalContexts.clear()
            emit(FastVoiceEvent.ArrivalRejected(code, errorMessage))
        }
        emitError(code = code, message = errorMessage)
    }

    private fun handleArrivalAccepted(message: JSONObject) {
        val eventId = message.optString("event_id")
        arrivalEventIds.remove(eventId)
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

    private fun sendContext(context: VehicleContext, arrival: SpotArrival?) {
        if (!helloReady.get()) return
        val store = versions ?: return
        val version = runCatching { store.nextContextVersion() }.getOrElse { error ->
            emitError(code = "context_version_failed", message = error.message, cause = error)
            return
        }
        val payload = ProtocolEncoder.contextUpdate(
            config,
            context,
            version,
            System.currentTimeMillis() / 1_000.0,
        )
        if (arrival != null) inFlightArrivalContexts[version] = InFlightArrival(arrival, version)
        if (socket.get()?.send(payload) != true) {
            inFlightArrivalContexts.remove(version)
            if (arrival != null) pendingArrival.set(PendingArrival(arrival, context))
            emitError(code = "context_send_failed")
        }
    }

    private fun sendArrival(arrival: SpotArrival) {
        val store = versions ?: return
        val version = runCatching { store.nextArrivalVersion() }.getOrElse { error ->
            emitError(code = "arrival_version_failed", message = error.message, cause = error)
            return
        }
        val eventId = UUID.randomUUID().toString()
        val payload = ProtocolEncoder.spotArrival(
            config,
            arrival,
            version,
            System.currentTimeMillis() / 1_000.0,
            eventId,
        )
        arrivalEventIds[eventId] = arrival
        if (socket.get()?.send(payload) != true) {
            arrivalEventIds.remove(eventId)
            emitError(code = "arrival_send_failed")
        }
    }

    private fun scheduleContextRefresh() {
        contextRefreshFuture?.cancel(false)
        if (!started.get() || config.deviceId == null || lastContext.get() == null) return
        val delayMillis = maxOf(1_000L, (config.contextTtlSeconds * 500.0).toLong())
        contextRefreshFuture = scheduler.schedule({
            if (started.get() && helloReady.get()) {
                lastContext.get()?.let { sendContext(it, arrival = null) }
            }
            scheduleContextRefresh()
        }, delayMillis, TimeUnit.MILLISECONDS)
    }

    private fun handleDisconnected(
        generation: Long,
        webSocket: WebSocket,
        error: FastVoiceError?,
    ) {
        if (socket.compareAndSet(webSocket, null)) {
            helloReady.set(false)
            audio.setWakeArmed(false)
            audio.setUplinkEnabled(false)
            audio.interruptPlayback()
            clearConnectionScopedState()
            error?.let { emit(FastVoiceEvent.Error(it)) }
            scheduleReconnect(generation)
        }
    }

    private fun clearConnectionScopedState() {
        promptDoneType.set(null)
        resumeUplinkAfterPlayback.set(false)
        audio.setPromptPlayback(false)
        inFlightArrivalContexts.clear()
        arrivalEventIds.clear()
    }

    private fun scheduleReconnect(generation: Long) {
        if (!started.get() || !config.autoReconnect || generation != connectionGeneration.get()) return
        val attempt = reconnectAttempt.getAndIncrement()
        val delays = longArrayOf(1, 2, 4, 8, 15, 30)
        val delay = delays[minOf(attempt, delays.lastIndex)]
        log(FastVoiceLogLevel.INFO, "reconnecting in ${delay}s")
        reconnectFuture?.cancel(false)
        reconnectFuture = scheduler.schedule({ connect(generation) }, delay, TimeUnit.SECONDS)
    }

    private fun requireTrustedDevice(code: String): Boolean {
        if (config.deviceId != null && versions != null) return true
        emitError(code = code)
        return false
    }

    private fun selectedLocalWakeWords(): List<String> =
        if (config.preferredWakeWords.isEmpty()) AudioEngine.SUPPORTED_WAKE_WORDS.toList()
        else config.preferredWakeWords

    private fun isCurrent(generation: Long, webSocket: WebSocket): Boolean =
        started.get() && generation == connectionGeneration.get() && socket.get() === webSocket

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
        private var contextTtlSeconds = FastVoiceConfig.DEFAULT_TTL_SECONDS
        private var arrivalTtlSeconds = FastVoiceConfig.DEFAULT_TTL_SECONDS

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

        fun contextTtlSeconds(seconds: Double) = apply { contextTtlSeconds = seconds }

        fun arrivalTtlSeconds(seconds: Double) = apply { arrivalTtlSeconds = seconds }

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
                contextTtlSeconds = contextTtlSeconds,
                arrivalTtlSeconds = arrivalTtlSeconds,
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
