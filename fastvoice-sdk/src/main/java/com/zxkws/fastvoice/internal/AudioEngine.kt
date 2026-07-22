package com.zxkws.fastvoice.internal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.SystemClock
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusSignal
import java.util.ArrayDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Internal owner of the microphone, local KWS, Opus codec, and speaker. */
internal class AudioEngine(
    context: Context,
    private val routeToSpeaker: Boolean,
    private val callback: Callback,
) {
    interface Callback {
        /** Called on the recorder thread immediately before wake pre-roll packets. */
        fun onWakeWord(word: String): Boolean

        /** A local KWS hit is only a candidate; the server remains the final arbiter. */
        fun onLocalCommandCandidate(generation: Int, text: String)

        fun onUplinkPacket(packet: ByteArray)
        fun onPlaybackStarted()
        fun onPlaybackProgress(generation: Int, playedMs: Long)
        fun onPlaybackFinished(generation: Int)
        fun onPlaybackFailed(generation: Int, reason: String)
        fun onDiagnostic(code: String, message: String, error: Throwable? = null)
    }

    companion object {
        const val INPUT_RATE = 16_000
        const val OUTPUT_RATE = 48_000
        const val FRAME_MS = 20
        const val FRAME_SAMPLES = INPUT_RATE / 1_000 * FRAME_MS
        const val FRAME_BYTES = FRAME_SAMPLES * 2
        private const val WAKE_PRE_ROLL_FRAMES = 75
        private const val PLAYBACK_PREBUFFER_BYTES = OUTPUT_RATE / 10 * 2 // 100 ms
        private const val MAX_REPLAY_BYTES = OUTPUT_RATE * 2 * 60 // at most one minute of PCM
        private const val LOCAL_COMMAND_DEBOUNCE_MS = 1_200L
        private const val RECORDER_REOPEN_ATTEMPTS = 3
        private const val RECORDER_STABLE_FRAMES_TO_RESET = 50
        private const val RECORDER_REOPEN_BACKOFF_MS = 100L
        private const val AUDIO_WRITE_UNHELD_ZERO_RETRIES = 3
        private const val AUDIO_WRITE_RETRY_DELAY_MS = 20L
        private const val PLAYBACK_PROGRESS_INTERVAL_MS = 500L

        val SUPPORTED_WAKE_WORDS: Set<String> = linkedSetOf(
            "布丁",
            "布丁布丁",
            "你好布丁",
            "布丁你好",
        )
    }

    private sealed interface PlaybackItem {
        val epoch: Int
        val serverGeneration: Int

        data class Audio(
            val pcm: ByteArray,
            override val epoch: Int,
            override val serverGeneration: Int,
        ) : PlaybackItem

        data class End(
            override val epoch: Int,
            override val serverGeneration: Int,
        ) : PlaybackItem
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val active = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val uplinkEnabled = AtomicBoolean(false)
    private val keywordDetectionPaused = AtomicBoolean(false)
    private val pendingWakeWord = AtomicReference<String?>(null)
    private val wakeArmed = AtomicBoolean(false)
    private val playbackActive = AtomicBoolean(false)
    private val playbackPaused = AtomicBoolean(false)
    private val playbackEpoch = AtomicInteger(0)
    private val serverPlaybackGeneration = AtomicInteger(-1)
    private val playbackAccepting = AtomicBoolean(false)
    private val terminalEpoch = AtomicInteger(Int.MIN_VALUE)
    private val playbackWriteHoldRevision = AtomicLong(0)
    private val lastLocalCommandAt = AtomicLong(0)
    private val playbackQueue = LinkedBlockingQueue<PlaybackItem>()
    private val kwsQueue = LinkedBlockingQueue<ByteArray>(100)
    private val decoderLock = Any()
    private val playbackCacheLock = Any()
    private val playbackControlLock = Any()
    private val currentPlaybackCache = ArrayList<ByteArray>()
    private var currentPlaybackBytes = 0
    private var currentPlaybackTooLarge = false
    private var pendingPlaybackCache: List<ByteArray> = emptyList()
    private var lastPlaybackCache: List<ByteArray> = emptyList()

    @Volatile
    private var enabledWakeWords: Set<String> = setOf("布丁")

    @Volatile
    private var recorder: AudioRecord? = null

    @Volatile
    private var player: AudioTrack? = null

    @Volatile
    private var keywordSpotter: LocalCommandSpotter? = null

    @Volatile
    private var echoCanceler: AcousticEchoCanceler? = null

    private var recorderThread: Thread? = null
    private var playerThread: Thread? = null
    private var kwsThread: Thread? = null
    private var encoder: OpusEncoder? = null
    private var decoder: OpusDecoder? = null

    private var previousAudioMode: Int? = null
    private var previousSpeakerphone: Boolean? = null
    private var previousCommunicationDevice: AudioDeviceInfo? = null
    private var focusRequest: AudioFocusRequest? = null

    @Synchronized
    fun start(): Boolean {
        if (active.get()) return true

        val runGeneration = generation.incrementAndGet()
        playbackQueue.clear()
        kwsQueue.clear()
        pendingWakeWord.set(null)
        wakeArmed.set(false)
        playbackActive.set(false)
        playbackPaused.set(false)
        playbackAccepting.set(false)
        serverPlaybackGeneration.set(-1)
        uplinkEnabled.set(false)
        keywordDetectionPaused.set(false)
        synchronized(playbackCacheLock) {
            currentPlaybackCache.clear()
            currentPlaybackBytes = 0
            currentPlaybackTooLarge = false
            pendingPlaybackCache = emptyList()
            lastPlaybackCache = emptyList()
        }

        try {
            encoder = OpusEncoder(INPUT_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP).apply {
                bitrate = 24_000
                complexity = 5
                useVBR = true
                useDTX = false
                signalType = OpusSignal.OPUS_SIGNAL_VOICE
            }
            decoder = OpusDecoder(OUTPUT_RATE, 1)
            configureAudioRoute()
            active.set(true)
            startPlayer(runGeneration)
            startLocalKws(runGeneration)
            if (!startRecorder(runGeneration)) {
                stop()
                return false
            }
        } catch (error: Throwable) {
            callback.onDiagnostic("audio_initialization_failed", error.message.orEmpty(), error)
            stop()
            return false
        }
        return true
    }

    @Synchronized
    fun stop() {
        active.set(false)
        generation.incrementAndGet()
        uplinkEnabled.set(false)
        keywordDetectionPaused.set(false)
        pendingWakeWord.set(null)
        wakeArmed.set(false)
        playbackActive.set(false)
        playbackPaused.set(false)
        playbackAccepting.set(false)
        serverPlaybackGeneration.set(-1)
        playbackEpoch.incrementAndGet()
        playbackWriteHoldRevision.incrementAndGet()
        playbackQueue.clear()
        kwsQueue.clear()
        synchronized(playbackCacheLock) {
            currentPlaybackCache.clear()
            currentPlaybackBytes = 0
            currentPlaybackTooLarge = false
            pendingPlaybackCache = emptyList()
            lastPlaybackCache = emptyList()
        }

        // The recorder thread owns release(). stop() only unblocks a pending read; otherwise the
        // thread-local AEC/AudioRecord references would be released twice during its finally block.
        echoCanceler = null
        recorder?.let { current ->
            runCatching { current.stop() }
        }
        recorder = null
        player?.let { current ->
            runCatching { current.pause() }
            runCatching { current.flush() }
        }
        player = null

        recorderThread?.interrupt()
        playerThread?.interrupt()
        kwsThread?.interrupt()
        recorderThread = null
        playerThread = null
        kwsThread = null

        // The KWS/player threads likewise release their native owners in finally.
        keywordSpotter = null
        encoder = null
        synchronized(decoderLock) { decoder = null }
        restoreAudioRoute()
    }

    fun setWakeWords(words: Collection<String>) {
        val supported = words.filterTo(linkedSetOf()) { it in SUPPORTED_WAKE_WORDS }
        enabledWakeWords = supported
        runCatching { keywordSpotter?.configureWakeWords(supported) }
            .onFailure {
                callback.onDiagnostic(
                    "wake_configuration_failed",
                    it.message.orEmpty(),
                    it,
                )
            }
    }

    fun setUplinkEnabled(enabled: Boolean) {
        if (!enabled) {
            kwsQueue.clear()
            keywordSpotter?.reset()
        }
        uplinkEnabled.set(enabled)
    }

    fun setPromptPlayback(active: Boolean) {
        keywordDetectionPaused.set(active)
        if (active) {
            kwsQueue.clear()
            keywordSpotter?.reset()
        }
    }

    fun setWakeArmed(armed: Boolean) {
        wakeArmed.set(armed)
        if (!armed) pendingWakeWord.set(null)
    }

    fun isUplinkEnabled(): Boolean = uplinkEnabled.get()

    fun isPlaybackActive(): Boolean = playbackActive.get()

    fun enqueueOpus(packet: ByteArray) {
        if (!active.get() || !playbackAccepting.get() || packet.isEmpty()) return
        val epoch = playbackEpoch.get()
        val serverGeneration = serverPlaybackGeneration.get()
        val pcm = ByteArray(OUTPUT_RATE / 1_000 * 120 * 2)
        val samples = synchronized(decoderLock) {
            runCatching {
                decoder?.decode(
                    packet,
                    0,
                    packet.size,
                    pcm,
                    0,
                    OUTPUT_RATE / 1_000 * 120,
                    false,
                ) ?: 0
            }.getOrElse {
                callback.onDiagnostic("opus_decode_failed", it.message.orEmpty(), it)
                0
            }
        }
        if (samples > 0) {
            val decoded = pcm.copyOf(samples * 2)
            if (playbackAccepting.get() && playbackEpoch.get() == epoch &&
                serverPlaybackGeneration.get() == serverGeneration
            ) {
                synchronized(playbackControlLock) {
                    // Recheck under the playback control lock used by begin/interrupt: a slow
                    // Opus decode from an old session must not poison a new replay cache.
                    if (!playbackAccepting.get() || playbackEpoch.get() != epoch ||
                        serverPlaybackGeneration.get() != serverGeneration
                    ) return
                    synchronized(playbackCacheLock) {
                        if (!currentPlaybackTooLarge) {
                            if (currentPlaybackBytes + decoded.size <= MAX_REPLAY_BYTES) {
                                currentPlaybackCache.add(decoded)
                                currentPlaybackBytes += decoded.size
                            } else {
                                currentPlaybackCache.clear()
                                currentPlaybackBytes = 0
                                currentPlaybackTooLarge = true
                            }
                        }
                        playbackQueue.offer(PlaybackItem.Audio(decoded, epoch, serverGeneration))
                    }
                }
            }
        }
    }

    /** Starts a new server playback generation and preserves the last completed replay buffer. */
    fun beginPlayback(serverGeneration: Int) {
        synchronized(playbackControlLock) {
            interruptPlaybackLocked()
            serverPlaybackGeneration.set(serverGeneration)
            playbackAccepting.set(true)
            // Publish generation and accepting state before the epoch observed by the player.
            playbackEpoch.incrementAndGet()
            synchronized(playbackCacheLock) {
                currentPlaybackCache.clear()
                currentPlaybackBytes = 0
                currentPlaybackTooLarge = false
            }
        }
    }

    /** Marks that every binary packet for the current response has arrived. */
    fun endPlayback() {
        synchronized(playbackControlLock) {
            if (!active.get() || !playbackAccepting.get()) return
            val epoch = playbackEpoch.get()
            val serverGeneration = serverPlaybackGeneration.get()
            synchronized(playbackCacheLock) {
                if (!playbackAccepting.get() || playbackEpoch.get() != epoch ||
                    serverPlaybackGeneration.get() != serverGeneration
                ) return
                pendingPlaybackCache = if (!currentPlaybackTooLarge) {
                    currentPlaybackCache.toList()
                } else {
                    emptyList()
                }
                currentPlaybackCache.clear()
                currentPlaybackBytes = 0
                currentPlaybackTooLarge = false
                playbackQueue.offer(PlaybackItem.End(epoch, serverGeneration))
            }
        }
    }

    /** Immediately discards queued audio and invalidates the current AudioTrack. */
    fun interruptPlayback() {
        synchronized(playbackControlLock) { interruptPlaybackLocked() }
    }

    private fun interruptPlaybackLocked() {
        playbackActive.set(false)
        playbackPaused.set(false)
        playbackAccepting.set(false)
        serverPlaybackGeneration.set(-1)
        playbackWriteHoldRevision.incrementAndGet()
        playbackQueue.clear()
        kwsQueue.clear()
        keywordSpotter?.reset()
        playbackEpoch.incrementAndGet()
        synchronized(decoderLock) { decoder?.resetState() }
        synchronized(playbackCacheLock) {
            currentPlaybackCache.clear()
            currentPlaybackBytes = 0
            currentPlaybackTooLarge = false
            pendingPlaybackCache = emptyList()
        }
        player?.let { current ->
            runCatching { current.pause() }
            runCatching { current.flush() }
        }
    }

    /** Pauses the current AudioTrack without discarding decoded or queued audio. */
    fun pausePlayback(expectedGeneration: Int = serverPlaybackGeneration.get()): Boolean {
        var failure: Throwable? = null
        var failureReason: String? = null
        var failureEpoch = -1
        val pausedSuccessfully = synchronized(playbackControlLock) {
            if (!active.get()) return false
            if (expectedGeneration < 0 || serverPlaybackGeneration.get() != expectedGeneration ||
                !playbackAccepting.get()
            ) return false
            if (!playbackActive.get() && playbackQueue.isEmpty()) return false
            if (playbackPaused.get()) return true
            playbackPaused.set(true)
            playbackWriteHoldRevision.incrementAndGet()
            val current = player
            if (playbackActive.get() && current != null) {
                var observedPlayState = Int.MIN_VALUE
                val paused = runCatching {
                    current.pause()
                    observedPlayState = current.playState
                    observedPlayState == AudioTrack.PLAYSTATE_PAUSED
                }.getOrElse { error ->
                    failure = error
                    false
                }
                if (!paused) {
                    failureEpoch = playbackEpoch.get()
                    failureReason = failure?.let {
                        "pause failed: ${it::class.java.simpleName}: ${it.message.orEmpty()}"
                    } ?: "pause failed: playState=$observedPlayState"
                    return@synchronized false
                }
            }
            true
        }
        val reason = failureReason
        if (reason != null) {
            callback.onDiagnostic("audio_playback_pause_failed", reason, failure)
            failPlayback(failureEpoch, expectedGeneration, reason)
        }
        return pausedSuccessfully
    }

    /** Continues a paused AudioTrack from its current hardware playback position. */
    fun resumePlayback(expectedGeneration: Int = serverPlaybackGeneration.get()): Boolean {
        var failure: Throwable? = null
        var failureReason: String? = null
        var failureEpoch = -1
        val resumedSuccessfully = synchronized(playbackControlLock) {
            if (expectedGeneration < 0 || serverPlaybackGeneration.get() != expectedGeneration ||
                !playbackAccepting.get()
            ) return false
            if (!active.get() || !playbackPaused.compareAndSet(true, false)) return false
            playbackWriteHoldRevision.incrementAndGet()
            val current = player
            if (playbackActive.get() && current != null) {
                val playCalled = runCatching {
                    current.play()
                    true
                }.onFailure { failure = it }.getOrDefault(false)
                val playing = playCalled && runCatching {
                    current.playState == AudioTrack.PLAYSTATE_PLAYING
                }.onFailure { failure = it }.getOrDefault(false)
                if (AudioTrackResumePolicy.classify(playCalled, playing) ==
                    AudioTrackResumePolicy.Decision.FAIL
                ) {
                    val reason = failure?.let {
                        "resume failed: ${it::class.java.simpleName}: ${it.message.orEmpty()}"
                    } ?: "resume failed: playState=${current.playState}"
                    failureEpoch = playbackEpoch.get()
                    failureReason = reason
                    return@synchronized false
                }
            }
            true
        }
        val reason = failureReason
        if (reason != null) {
            callback.onDiagnostic("audio_playback_resume_failed", reason, failure)
            failPlayback(failureEpoch, expectedGeneration, reason)
        }
        return resumedSuccessfully
    }

    /** Replays the current response from its beginning, or the last completed response when idle. */
    fun replayPlayback(serverGeneration: Int): Boolean {
        synchronized(playbackControlLock) {
            val currentGenerationActive = playbackAccepting.get() &&
                serverPlaybackGeneration.get() >= 0
            val replay = synchronized(playbackCacheLock) {
                ReplayCachePolicy.select(
                    currentGenerationActive = currentGenerationActive,
                    current = currentPlaybackCache,
                    pending = pendingPlaybackCache,
                    lastCompleted = lastPlaybackCache,
                ).toList()
            }
            interruptPlaybackLocked()
            if (!active.get() || replay.isEmpty()) return false
            serverPlaybackGeneration.set(serverGeneration)
            playbackAccepting.set(true)
            val epoch = playbackEpoch.incrementAndGet()
            replay.forEach {
                playbackQueue.offer(PlaybackItem.Audio(it, epoch, serverGeneration))
            }
            playbackQueue.offer(PlaybackItem.End(epoch, serverGeneration))
            return true
        }
    }

    fun skipPlayback() = interruptPlayback()

    /** Adjusts the Android media stream by one platform-defined step. */
    fun adjustPlaybackVolume(direction: Int): Boolean {
        if (direction != AudioManager.ADJUST_RAISE && direction != AudioManager.ADJUST_LOWER) {
            return false
        }
        return runCatching {
            audioManager.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, direction, 0)
        }.isSuccess
    }

    private fun isActive(runGeneration: Long): Boolean =
        active.get() && generation.get() == runGeneration

    private fun startRecorder(runGeneration: Long): Boolean {
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            callback.onDiagnostic("microphone_permission_missing", "")
            return false
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            INPUT_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            callback.onDiagnostic("microphone_initialization_failed", minBuffer.toString())
            return false
        }

        fun open(source: Int): AudioRecord? {
            val candidate = runCatching {
                AudioRecord(
                    source,
                    INPUT_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, 6_400),
                )
            }.getOrNull() ?: return null
            if (candidate.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { candidate.release() }
                return null
            }
            return candidate
        }

        fun openStarted(): AudioCaptureStartup.Started<AudioRecord, AcousticEchoCanceler?>? {
            val candidate = open(MediaRecorder.AudioSource.MIC) ?: return null
            return AudioCaptureStartup.start(
                candidate,
                { record -> createEchoCanceler(record) },
                { record -> record.startRecording() },
                { record -> record.recordingState == AudioRecord.RECORDSTATE_RECORDING },
                { effect -> effect?.release() },
                { record -> record.stop() },
                { record -> record.release() },
            )
        }

        // Production capture is deliberately MIC-only. VOICE_COMMUNICATION returns permanent
        // digital silence on some production devices and is not a safe automatic default.
        val initialCapture = openStarted() ?: run {
            callback.onDiagnostic("microphone_initialization_failed", "AudioRecord")
            return false
        }
        val firstRecorder = initialCapture.record()
        recorder = firstRecorder
        echoCanceler = initialCapture.effect()

        recorderThread = Thread({
            val frame = ByteArray(FRAME_BYTES)
            val preRoll = ArrayDeque<ByteArray>(WAKE_PRE_ROLL_FRAMES)
            var currentRecorder = firstRecorder
            var currentEchoCanceler = initialCapture.effect()
            val readRecovery = AudioReadRecoveryPolicy(
                RECORDER_REOPEN_ATTEMPTS,
                RECORDER_STABLE_FRAMES_TO_RESET,
            )

            fun sendUplink(raw: ByteArray) {
                val encoded = ByteArray(1_275)
                val size = runCatching {
                    // Raw MIC PCM is authoritative. Client-side VAD/gating must not rewrite the
                    // capture stream behind the server's VAD and echo assumptions.
                    encoder?.encode(raw, 0, FRAME_SAMPLES, encoded, 0, encoded.size) ?: 0
                }.getOrElse {
                    callback.onDiagnostic("opus_encode_failed", it.message.orEmpty(), it)
                    0
                }
                if (size > 0) callback.onUplinkPacket(encoded.copyOf(size))
            }

            fun releaseCurrentCapture() {
                if (echoCanceler === currentEchoCanceler) echoCanceler = null
                currentEchoCanceler?.let { effect -> runCatching { effect.release() } }
                currentEchoCanceler = null
                if (recorder === currentRecorder) recorder = null
                runCatching { currentRecorder.stop() }
                runCatching { currentRecorder.release() }
            }

            fun reopenRecorder(): Boolean {
                releaseCurrentCapture()
                if (!isActive(runGeneration)) return false
                val replacementCapture = openStarted() ?: return false
                if (!isActive(runGeneration)) {
                    replacementCapture.effect()?.let { effect -> runCatching { effect.release() } }
                    runCatching { replacementCapture.record().stop() }
                    runCatching { replacementCapture.record().release() }
                    return false
                }
                currentRecorder = replacementCapture.record()
                currentEchoCanceler = replacementCapture.effect()
                recorder = currentRecorder
                echoCanceler = currentEchoCanceler
                return true
            }

            try {
                while (isActive(runGeneration)) {
                    var offset = 0
                    var readFailed = false
                    var readError = 0
                    while (offset < frame.size && isActive(runGeneration)) {
                        val count = runCatching {
                            currentRecorder.read(frame, offset, frame.size - offset)
                        }.getOrElse { error ->
                            callback.onDiagnostic(
                                "microphone_read_exception",
                                error.message.orEmpty(),
                                error,
                            )
                            AudioRecord.ERROR_INVALID_OPERATION
                        }
                        if (!isActive(runGeneration)) return@Thread
                        if (count <= 0) {
                            readFailed = true
                            readError = count
                            break
                        }
                        offset += count
                    }
                    if (readFailed) {
                        if (readRecovery.onReadFailure() == AudioReadRecoveryPolicy.Decision.ABORT) {
                            uplinkEnabled.set(false)
                            callback.onDiagnostic(
                                "microphone_read_failed",
                                "read=$readError; recovery attempts exhausted",
                            )
                            return@Thread
                        }
                        runCatching { Thread.sleep(RECORDER_REOPEN_BACKOFF_MS) }
                        if (!isActive(runGeneration)) return@Thread
                        if (!reopenRecorder()) {
                            callback.onDiagnostic(
                                "microphone_reopen_failed",
                                "attempt=${readRecovery.consecutiveFailures()}",
                            )
                        }
                        continue
                    }
                    if (offset != frame.size) continue
                    readRecovery.onFrameRead()

                    val captured = frame.copyOf()
                    if (!kwsQueue.offer(captured)) {
                        kwsQueue.poll()
                        kwsQueue.offer(captured)
                    }
                    preRoll.addLast(captured)
                    while (preRoll.size > WAKE_PRE_ROLL_FRAMES) preRoll.removeFirst()

                    val detectedWord = pendingWakeWord.getAndSet(null)
                    if (detectedWord != null) {
                        // The callback sends wake JSON synchronously before these raw packets.
                        if (callback.onWakeWord(detectedWord)) {
                            encoder?.resetState()
                            uplinkEnabled.set(true)
                            preRoll.forEach(::sendUplink)
                        }
                        preRoll.clear()
                        continue
                    }

                    if (uplinkEnabled.get()) {
                        preRoll.clear()
                        sendUplink(captured)
                    }
                }
            } finally {
                releaseCurrentCapture()
            }
        }, "fastvoice-recorder").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun createEchoCanceler(record: AudioRecord): AcousticEchoCanceler? {
        if (!AcousticEchoCanceler.isAvailable()) {
            callback.onDiagnostic("acoustic_echo_canceler_unavailable", "")
            return null
        }
        val created = runCatching {
            AcousticEchoCanceler.create(record.audioSessionId)
        }.getOrElse { error ->
            callback.onDiagnostic("acoustic_echo_canceler_create_failed", error.message.orEmpty(), error)
            null
        } ?: return null
        return runCatching {
            val status = created.setEnabled(true)
            check(status == AudioEffect.SUCCESS && created.enabled) {
                "setEnabled failed: status=$status enabled=${created.enabled}"
            }
            created
        }.getOrElse { error ->
            runCatching { created.release() }
            callback.onDiagnostic("acoustic_echo_canceler_enable_failed", error.message.orEmpty(), error)
            null
        }
    }

    private fun startLocalKws(runGeneration: Long) {
        kwsThread = Thread({
            val engine = LocalCommandSpotter()
            keywordSpotter = engine
            try {
                engine.init(appContext.assets, enabledWakeWords)
                while (isActive(runGeneration)) {
                    val frame = kwsQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    if (keywordDetectionPaused.get()) continue
                    engine.accept(frame)?.let(::handleKeyword)
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Throwable) {
                if (isActive(runGeneration)) {
                    callback.onDiagnostic(
                        "local_keyword_spotter_failed",
                        error.message.orEmpty(),
                        error,
                    )
                }
            } finally {
                engine.release()
                if (keywordSpotter === engine) keywordSpotter = null
            }
        }, "fastvoice-kws").apply {
            isDaemon = true
            start()
        }
    }

    private fun handleKeyword(keyword: String) {
        when (LocalCommandSpotter.routeKeyword(keyword, enabledWakeWords)) {
            LocalCommandSpotter.KeywordRoute.WAKE -> {
                if (wakeArmed.compareAndSet(true, false)) {
                    pendingWakeWord.compareAndSet(null, keyword)
                }
            }
            LocalCommandSpotter.KeywordRoute.IGNORE -> return
            LocalCommandSpotter.KeywordRoute.CONTROL -> Unit
            null -> return
        }
        if (!playbackActive.get()) return
        val serverGeneration = serverPlaybackGeneration.get()
        if (serverGeneration < 0) return
        val now = SystemClock.elapsedRealtime()
        val previous = lastLocalCommandAt.get()
        if (now - previous < LOCAL_COMMAND_DEBOUNCE_MS) return
        if (lastLocalCommandAt.compareAndSet(previous, now)) {
            // Local control is speculative. Hold the exact playback generation reversibly before
            // asking the server; rejection/timeout can resume without losing queued PCM.
            if (pausePlayback(serverGeneration)) {
                callback.onLocalCommandCandidate(serverGeneration, keyword)
            }
        }
    }

    private fun startPlayer(runGeneration: Long) {
        playerThread = Thread({
            var localEpoch = playbackEpoch.get()
            var localServerGeneration = serverPlaybackGeneration.get()
            var track = createTrack()
            player = track
            var started = false
            var bytesWritten = 0L
            var lastProgressAt = 0L
            var lastPlayedMs = 0L

            fun replaceTrack(): AudioTrack? {
                runCatching { track?.pause() }
                runCatching { track?.flush() }
                runCatching { track?.release() }
                val replacement = createTrack()
                track = replacement
                player = replacement
                started = false
                bytesWritten = 0L
                lastProgressAt = 0L
                lastPlayedMs = 0L
                return replacement
            }

            fun reportProgress(force: Boolean = false) {
                val current = track ?: return
                if (!started || localServerGeneration < 0) return
                val now = SystemClock.elapsedRealtime()
                if (!force && now - lastProgressAt < PLAYBACK_PROGRESS_INTERVAL_MS) return
                val headFrames = runCatching {
                    current.playbackHeadPosition.toLong() and 0xffff_ffffL
                }.getOrDefault(0L)
                val playedMs = maxOf(lastPlayedMs, headFrames * 1_000L / OUTPUT_RATE)
                if (playedMs > lastPlayedMs || force) {
                    lastPlayedMs = playedMs
                    lastProgressAt = now
                    callback.onPlaybackProgress(localServerGeneration, playedMs)
                }
            }

            fun waitForWriteRetry(epoch: Int): Boolean {
                try {
                    Thread.sleep(AUDIO_WRITE_RETRY_DELAY_MS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
                return isActive(runGeneration) && playbackEpoch.get() == epoch &&
                    playbackAccepting.get()
            }

            fun writePcm(pcm: ByteArray): Boolean {
                val current = track ?: run {
                    failPlayback(localEpoch, localServerGeneration, "no current AudioTrack")
                    return false
                }
                val recovery = AudioWriteRecoveryPolicy(AUDIO_WRITE_UNHELD_ZERO_RETRIES)
                var offset = 0
                while (isActive(runGeneration) && playbackEpoch.get() == localEpoch &&
                    playbackAccepting.get()
                ) {
                    val revisionBefore = playbackWriteHoldRevision.get()
                    if (playbackPaused.get()) {
                        recovery.onPlaybackHeld()
                        if (!waitForWriteRetry(localEpoch)) return false
                        continue
                    }
                    val remaining = pcm.size - offset
                    var writeError: Throwable? = null
                    val written = runCatching {
                        current.write(pcm, offset, remaining, AudioTrack.WRITE_BLOCKING)
                    }.onFailure { writeError = it }.getOrDefault(AudioTrack.ERROR_INVALID_OPERATION)
                    if (!isActive(runGeneration) || playbackEpoch.get() != localEpoch ||
                        !playbackAccepting.get()
                    ) return false

                    val pauseAffectedWrite = playbackPaused.get() ||
                        playbackWriteHoldRevision.get() != revisionBefore
                    val decision = recovery.onWriteResult(written, remaining, pauseAffectedWrite)
                    if (written > 0) {
                        val accepted = minOf(written, remaining)
                        offset += accepted
                        bytesWritten += accepted
                        reportProgress()
                    }
                    when (decision) {
                        AudioWriteRecoveryPolicy.Decision.COMPLETE -> return offset == pcm.size
                        AudioWriteRecoveryPolicy.Decision.RETRY -> {
                            if (!waitForWriteRetry(localEpoch)) return false
                        }
                        AudioWriteRecoveryPolicy.Decision.FAIL -> {
                            val reason = writeError?.let {
                                "write threw ${it::class.java.simpleName}: ${it.message.orEmpty()}"
                            } ?: "write=$written expected=$remaining"
                            failPlayback(localEpoch, localServerGeneration, reason)
                            return false
                        }
                        null -> {
                            failPlayback(
                                localEpoch,
                                localServerGeneration,
                                "write recovery returned no decision",
                            )
                            return false
                        }
                    }
                }
                return false
            }

            fun finishCurrentPlayback() {
                val current = track
                if (started) {
                    val expectedFrames = bytesWritten / 2
                    val maximumWait = (bytesWritten * 1_000L / (OUTPUT_RATE * 2L)) + 1_000L
                    var deadline = SystemClock.elapsedRealtime() + maximumWait
                    while (
                        isActive(runGeneration) &&
                        playbackEpoch.get() == localEpoch &&
                        playbackAccepting.get() &&
                        (playbackPaused.get() ||
                            (SystemClock.elapsedRealtime() < deadline &&
                                (current?.playbackHeadPosition?.toLong() ?: 0L) < expectedFrames))
                    ) {
                        if (playbackPaused.get()) {
                            // A user pause is unbounded. Restore the underrun deadline on resume.
                            deadline = SystemClock.elapsedRealtime() + maximumWait
                        }
                        reportProgress()
                        Thread.sleep(10)
                    }
                    val headFrames = runCatching {
                        current?.playbackHeadPosition?.toLong() ?: 0L
                    }.getOrDefault(0L)
                    if (playbackEpoch.get() == localEpoch && playbackAccepting.get() &&
                        headFrames < expectedFrames
                    ) {
                        failPlayback(
                            localEpoch,
                            localServerGeneration,
                            "drain incomplete: head=$headFrames expected=$expectedFrames",
                        )
                        replaceTrack()
                        return
                    }
                }
                val completed = isActive(runGeneration) &&
                    playbackEpoch.get() == localEpoch && playbackAccepting.get()
                reportProgress(force = true)
                replaceTrack()
                if (completed) completePlayback(localEpoch, localServerGeneration)
            }

            try {
                while (isActive(runGeneration)) {
                    if (playbackEpoch.get() != localEpoch) {
                        localEpoch = playbackEpoch.get()
                        localServerGeneration = serverPlaybackGeneration.get()
                        replaceTrack()
                    }
                    val first = playbackQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    if (first.epoch != localEpoch || first.serverGeneration != localServerGeneration ||
                        playbackEpoch.get() != localEpoch || !playbackAccepting.get()
                    ) continue
                    if (first is PlaybackItem.End) {
                        finishCurrentPlayback()
                        continue
                    }

                    val buffered = ArrayList<ByteArray>()
                    var bufferedBytes = 0
                    var reachedEnd = false
                    val firstPcm = (first as PlaybackItem.Audio).pcm
                    buffered.add(firstPcm)
                    bufferedBytes += firstPcm.size
                    if (!started) {
                        val deadline = SystemClock.elapsedRealtime() + 120
                        while (
                            bufferedBytes < PLAYBACK_PREBUFFER_BYTES &&
                            SystemClock.elapsedRealtime() < deadline
                        ) {
                            when (val next = playbackQueue.poll(15, TimeUnit.MILLISECONDS)) {
                                null -> Unit
                                is PlaybackItem.Audio -> {
                                    if (next.epoch == localEpoch &&
                                        next.serverGeneration == localServerGeneration
                                    ) {
                                        buffered.add(next.pcm)
                                        bufferedBytes += next.pcm.size
                                    }
                                }
                                is PlaybackItem.End -> {
                                    if (next.epoch == localEpoch &&
                                        next.serverGeneration == localServerGeneration
                                    ) {
                                        reachedEnd = true
                                        break
                                    }
                                }
                            }
                        }
                        if (playbackEpoch.get() != localEpoch || !playbackAccepting.get()) continue
                        if (track == null) track = createTrack().also { player = it }
                        val current = track
                        if (current == null) {
                            failPlayback(
                                localEpoch,
                                localServerGeneration,
                                "AudioTrack initialization failed",
                            )
                            continue
                        }
                        if (!playbackPaused.get()) {
                            var playError: Throwable? = null
                            val playCalled = runCatching {
                                current.play()
                                true
                            }.onFailure { playError = it }.getOrDefault(false)
                            val playing = playCalled && runCatching {
                                current.playState == AudioTrack.PLAYSTATE_PLAYING
                            }.onFailure { playError = it }.getOrDefault(false)
                            if (AudioTrackResumePolicy.classify(playCalled, playing) ==
                                AudioTrackResumePolicy.Decision.FAIL
                            ) {
                                val reason = playError?.let {
                                    "play failed: ${it::class.java.simpleName}: ${it.message.orEmpty()}"
                                } ?: "play failed: playState=${current.playState}"
                                failPlayback(localEpoch, localServerGeneration, reason)
                                continue
                            }
                        }
                        started = true
                        playbackActive.set(true)
                        callback.onPlaybackStarted()
                    }
                    var writeSucceeded = true
                    for (pcm in buffered) {
                        if (!writePcm(pcm)) {
                            writeSucceeded = false
                            break
                        }
                    }
                    if (writeSucceeded && reachedEnd) finishCurrentPlayback()
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Throwable) {
                if (isActive(runGeneration)) {
                    callback.onDiagnostic("audio_playback_failed", error.message.orEmpty(), error)
                    failPlayback(
                        localEpoch,
                        localServerGeneration,
                        "player thread failed: ${error::class.java.simpleName}: " +
                            error.message.orEmpty(),
                    )
                }
            } finally {
                runCatching { track?.release() }
                if (player === track) player = null
            }
        }, "fastvoice-player").apply {
            isDaemon = true
            start()
        }
    }

    private fun claimPlaybackTerminal(epoch: Int, serverGeneration: Int): Boolean {
        if (!active.get() || playbackEpoch.get() != epoch ||
            serverPlaybackGeneration.get() != serverGeneration || !playbackAccepting.get()
        ) return false
        while (true) {
            val previous = terminalEpoch.get()
            if (previous == epoch) return false
            if (terminalEpoch.compareAndSet(previous, epoch)) return true
        }
    }

    private fun completePlayback(epoch: Int, serverGeneration: Int) {
        val completed = synchronized(playbackControlLock) {
            if (!claimPlaybackTerminal(epoch, serverGeneration)) return@synchronized false
            playbackAccepting.set(false)
            playbackActive.set(false)
            playbackPaused.set(false)
            playbackWriteHoldRevision.incrementAndGet()
            kwsQueue.clear()
            keywordSpotter?.reset()
            synchronized(playbackCacheLock) {
                if (pendingPlaybackCache.isNotEmpty()) {
                    lastPlaybackCache = pendingPlaybackCache
                }
                pendingPlaybackCache = emptyList()
            }
            true
        }
        if (completed) callback.onPlaybackFinished(serverGeneration)
    }

    private fun failPlayback(epoch: Int, serverGeneration: Int, reason: String): Boolean {
        val failed = synchronized(playbackControlLock) {
            if (!claimPlaybackTerminal(epoch, serverGeneration)) return@synchronized false
            playbackAccepting.set(false)
            playbackActive.set(false)
            playbackPaused.set(false)
            playbackWriteHoldRevision.incrementAndGet()
            playbackQueue.clear()
            kwsQueue.clear()
            keywordSpotter?.reset()
            synchronized(playbackCacheLock) {
                currentPlaybackCache.clear()
                currentPlaybackBytes = 0
                currentPlaybackTooLarge = false
                pendingPlaybackCache = emptyList()
            }
            playbackEpoch.compareAndSet(epoch, epoch + 1)
            player?.let { current ->
                runCatching { current.pause() }
                runCatching { current.flush() }
            }
            true
        }
        if (failed) callback.onPlaybackFailed(serverGeneration, reason)
        return failed
    }

    private fun createTrack(): AudioTrack? {
        val minBuffer = AudioTrack.getMinBufferSize(
            OUTPUT_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val created = runCatching {
            AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(OUTPUT_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                maxOf(minBuffer, 19_200),
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            ).apply {
                setVolume(1.0f)
                if (Build.VERSION.SDK_INT >= 31) {
                    // A paused short stream must be able to restart below the default full-buffer
                    // threshold; one frame is sufficient for streaming playback.
                    runCatching { setStartThresholdInFrames(1) }
                }
            }
        }.onFailure {
            callback.onDiagnostic("speaker_initialization_failed", it.message.orEmpty(), it)
        }.getOrNull() ?: return null
        if (created.state == AudioTrack.STATE_INITIALIZED) return created
        callback.onDiagnostic("speaker_initialization_failed", "state=${created.state}")
        runCatching { created.release() }
        return null
    }

    private fun configureAudioRoute() {
        previousAudioMode = audioManager.mode
        @Suppress("DEPRECATION")
        run { previousSpeakerphone = audioManager.isSpeakerphoneOn }
        if (Build.VERSION.SDK_INT >= 31) previousCommunicationDevice = audioManager.communicationDevice

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { }
                .build()
                .also(audioManager::requestAudioFocus)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
        }

        if (!routeToSpeaker) return
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            audioManager.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let(audioManager::setCommunicationDevice)
        } else {
            @Suppress("DEPRECATION")
            run { audioManager.isSpeakerphoneOn = true }
        }
    }

    private fun restoreAudioRoute() {
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let(audioManager::abandonAudioFocusRequest)
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }

        if (routeToSpeaker) {
            if (Build.VERSION.SDK_INT >= 31) {
                val previous = previousCommunicationDevice
                if (previous == null) audioManager.clearCommunicationDevice()
                else runCatching { audioManager.setCommunicationDevice(previous) }
            } else {
                previousSpeakerphone?.let { previous ->
                    @Suppress("DEPRECATION")
                    run { audioManager.isSpeakerphoneOn = previous }
                }
            }
            previousAudioMode?.let { audioManager.mode = it }
        }
        previousAudioMode = null
        previousSpeakerphone = null
        previousCommunicationDevice = null
    }
}
