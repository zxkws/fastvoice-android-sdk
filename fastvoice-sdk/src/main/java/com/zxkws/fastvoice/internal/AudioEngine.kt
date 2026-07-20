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
        fun onLocalCommandCandidate(text: String)

        fun onUplinkPacket(packet: ByteArray)
        fun onPlaybackStarted()
        fun onPlaybackFinished()
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
        private const val LOCAL_COMMAND_DEBOUNCE_MS = 1_200L

        val SUPPORTED_WAKE_WORDS: Set<String> = linkedSetOf(
            "布丁",
            "布丁布丁",
            "你好布丁",
            "布丁你好",
        )
    }

    private sealed interface PlaybackItem {
        data class Audio(val pcm: ByteArray) : PlaybackItem
        data object End : PlaybackItem
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
    private val playbackGeneration = AtomicInteger(0)
    private val lastLocalCommandAt = AtomicLong(0)
    private val playbackQueue = LinkedBlockingQueue<PlaybackItem>()
    private val kwsQueue = LinkedBlockingQueue<ByteArray>(100)
    private val decoderLock = Any()

    @Volatile
    private var enabledWakeWords: Set<String> = setOf("布丁")

    @Volatile
    private var recorder: AudioRecord? = null

    @Volatile
    private var player: AudioTrack? = null

    @Volatile
    private var keywordSpotter: LocalCommandSpotter? = null

    private var recorderThread: Thread? = null
    private var playerThread: Thread? = null
    private var kwsThread: Thread? = null
    private var encoder: OpusEncoder? = null
    private var decoder: OpusDecoder? = null
    private var micVoiceGate: MicVoiceGate? = null

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
        uplinkEnabled.set(false)
        keywordDetectionPaused.set(false)

        try {
            encoder = OpusEncoder(INPUT_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP).apply {
                bitrate = 24_000
                complexity = 5
                useVBR = true
                useDTX = false
                signalType = OpusSignal.OPUS_SIGNAL_VOICE
            }
            decoder = OpusDecoder(OUTPUT_RATE, 1)
            micVoiceGate = MicVoiceGate().also { it.init(appContext.assets) }
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
        playbackGeneration.incrementAndGet()
        playbackQueue.clear()
        kwsQueue.clear()

        recorder?.let { current ->
            runCatching { current.stop() }
            runCatching { current.release() }
        }
        recorder = null
        player?.let { current ->
            runCatching { current.pause() }
            runCatching { current.flush() }
            runCatching { current.release() }
        }
        player = null

        recorderThread?.interrupt()
        playerThread?.interrupt()
        kwsThread?.interrupt()
        recorderThread = null
        playerThread = null
        kwsThread = null

        keywordSpotter?.release()
        keywordSpotter = null
        micVoiceGate?.release()
        micVoiceGate = null
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
        if (!active.get() || packet.isEmpty()) return
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
        if (samples > 0) playbackQueue.offer(PlaybackItem.Audio(pcm.copyOf(samples * 2)))
    }

    /** Marks that every binary packet for the current response has arrived. */
    fun endPlayback() {
        if (active.get()) playbackQueue.offer(PlaybackItem.End)
    }

    /** Immediately discards queued audio and invalidates the current AudioTrack. */
    fun interruptPlayback() {
        playbackActive.set(false)
        playbackQueue.clear()
        kwsQueue.clear()
        keywordSpotter?.reset()
        playbackGeneration.incrementAndGet()
        synchronized(decoderLock) { decoder?.resetState() }
        player?.let { current ->
            runCatching { current.pause() }
            runCatching { current.flush() }
        }
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

        fun open(source: Int): AudioRecord? = runCatching {
            AudioRecord(
                source,
                INPUT_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, 6_400),
            )
        }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }

        var initial = open(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        var canFallback = initial != null
        if (initial == null) initial = open(MediaRecorder.AudioSource.MIC)
        val firstRecorder: AudioRecord = initial ?: run {
            callback.onDiagnostic("microphone_initialization_failed", "AudioRecord")
            return false
        }
        recorder = firstRecorder
        try {
            firstRecorder.startRecording()
        } catch (error: Throwable) {
            runCatching { firstRecorder.release() }
            recorder = null
            callback.onDiagnostic("microphone_start_failed", error.message.orEmpty(), error)
            return false
        }

        recorderThread = Thread({
            val frame = ByteArray(FRAME_BYTES)
            val preRoll = ArrayDeque<ByteArray>(WAKE_PRE_ROLL_FRAMES)
            var framesRead = 0
            var peak = 0
            var currentRecorder = firstRecorder

            fun sendUplink(raw: ByteArray) {
                val gated = micVoiceGate?.process(raw) ?: ByteArray(raw.size)
                val encoded = ByteArray(1_275)
                val size = runCatching {
                    encoder?.encode(gated, 0, FRAME_SAMPLES, encoded, 0, encoded.size) ?: 0
                }.getOrElse {
                    callback.onDiagnostic("opus_encode_failed", it.message.orEmpty(), it)
                    0
                }
                if (size > 0) callback.onUplinkPacket(encoded.copyOf(size))
            }

            while (isActive(runGeneration)) {
                var offset = 0
                while (offset < frame.size && isActive(runGeneration)) {
                    val count = runCatching {
                        currentRecorder.read(frame, offset, frame.size - offset)
                    }.getOrElse { AudioRecord.ERROR_INVALID_OPERATION }
                    if (count <= 0) break
                    offset += count
                }
                if (offset != frame.size) continue

                if (canFallback) {
                    framesRead += 1
                    var index = 0
                    while (index < frame.size) {
                        val value = (frame[index].toInt() and 0xff) or
                            (frame[index + 1].toInt() shl 8)
                        val absolute = if (value > 32_767) 65_536 - value else kotlin.math.abs(value)
                        if (absolute > peak) peak = absolute
                        index += 2
                    }
                    if (framesRead == 75) {
                        if (peak < 50) {
                            runCatching { currentRecorder.stop() }
                            runCatching { currentRecorder.release() }
                            val microphone = open(MediaRecorder.AudioSource.MIC)
                            if (microphone == null) {
                                callback.onDiagnostic(
                                    "microphone_fallback_failed",
                                    "AudioRecord",
                                )
                                return@Thread
                            }
                            recorder = microphone
                            currentRecorder = microphone
                            runCatching { microphone.startRecording() }.onFailure {
                                callback.onDiagnostic(
                                    "microphone_fallback_failed",
                                    it.message.orEmpty(),
                                    it,
                                )
                                return@Thread
                            }
                            callback.onDiagnostic(
                                "microphone_source_fallback",
                                "VOICE_COMMUNICATION -> MIC",
                            )
                        }
                        canFallback = false
                    }
                }

                val captured = frame.copyOf()
                if (!kwsQueue.offer(captured)) {
                    kwsQueue.poll()
                    kwsQueue.offer(captured)
                }
                preRoll.addLast(captured)
                while (preRoll.size > WAKE_PRE_ROLL_FRAMES) preRoll.removeFirst()

                val detectedWord = pendingWakeWord.getAndSet(null)
                if (detectedWord != null) {
                    // The callback sends wake JSON synchronously before these packets.
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
        }, "fastvoice-recorder").apply {
            isDaemon = true
            start()
        }
        return true
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
        if (keyword in enabledWakeWords) {
            if (wakeArmed.compareAndSet(true, false)) {
                pendingWakeWord.compareAndSet(null, keyword)
            }
            return
        }
        if (!playbackActive.get()) return
        val now = SystemClock.elapsedRealtime()
        val previous = lastLocalCommandAt.get()
        if (now - previous < LOCAL_COMMAND_DEBOUNCE_MS) return
        if (lastLocalCommandAt.compareAndSet(previous, now)) {
            callback.onLocalCommandCandidate(keyword)
        }
    }

    private fun startPlayer(runGeneration: Long) {
        playerThread = Thread({
            var localGeneration = playbackGeneration.get()
            var track = createTrack()
            if (track == null) return@Thread
            player = track
            var started = false
            var bytesWritten = 0L

            fun replaceTrack(): AudioTrack? {
                runCatching { track?.pause() }
                runCatching { track?.flush() }
                runCatching { track?.release() }
                val replacement = createTrack()
                track = replacement
                player = replacement
                started = false
                bytesWritten = 0L
                return replacement
            }

            fun finishCurrentPlayback() {
                val current = track ?: return
                if (started) {
                    val expectedFrames = bytesWritten / 2
                    val maximumWait = (bytesWritten * 1_000L / (OUTPUT_RATE * 2L)) + 1_000L
                    val deadline = SystemClock.elapsedRealtime() + maximumWait
                    while (
                        isActive(runGeneration) &&
                        playbackGeneration.get() == localGeneration &&
                        SystemClock.elapsedRealtime() < deadline &&
                        current.playbackHeadPosition.toLong() < expectedFrames
                    ) {
                        Thread.sleep(10)
                    }
                }
                replaceTrack()
                playbackActive.set(false)
                kwsQueue.clear()
                keywordSpotter?.reset()
                callback.onPlaybackFinished()
            }

            try {
                while (isActive(runGeneration)) {
                    if (playbackGeneration.get() != localGeneration) {
                        localGeneration = playbackGeneration.get()
                        replaceTrack() ?: return@Thread
                    }
                    val first = playbackQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    if (playbackGeneration.get() != localGeneration) continue
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
                                    buffered.add(next.pcm)
                                    bufferedBytes += next.pcm.size
                                }
                                PlaybackItem.End -> {
                                    reachedEnd = true
                                    break
                                }
                            }
                        }
                        if (playbackGeneration.get() != localGeneration) continue
                        track?.play()
                        started = true
                        playbackActive.set(true)
                        callback.onPlaybackStarted()
                    }
                    buffered.forEach { pcm ->
                        val written = track?.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING) ?: 0
                        if (written > 0) bytesWritten += written
                    }
                    if (reachedEnd) finishCurrentPlayback()
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Throwable) {
                if (isActive(runGeneration)) {
                    callback.onDiagnostic("audio_playback_failed", error.message.orEmpty(), error)
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

    private fun createTrack(): AudioTrack? {
        val minBuffer = AudioTrack.getMinBufferSize(
            OUTPUT_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        return runCatching {
            AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
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
            ).apply { setVolume(1.0f) }
        }.onFailure {
            callback.onDiagnostic("speaker_initialization_failed", it.message.orEmpty(), it)
        }.getOrNull()?.takeIf { it.state == AudioTrack.STATE_INITIALIZED }
    }

    private fun configureAudioRoute() {
        previousAudioMode = audioManager.mode
        @Suppress("DEPRECATION")
        run { previousSpeakerphone = audioManager.isSpeakerphoneOn }
        if (Build.VERSION.SDK_INT >= 31) previousCommunicationDevice = audioManager.communicationDevice

        val usage = if (Build.VERSION.SDK_INT >= 26) {
            AudioAttributes.USAGE_ASSISTANT
        } else {
            AudioAttributes.USAGE_MEDIA
        }
        val attributes = AudioAttributes.Builder()
            .setUsage(usage)
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
                AudioManager.STREAM_MUSIC,
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
