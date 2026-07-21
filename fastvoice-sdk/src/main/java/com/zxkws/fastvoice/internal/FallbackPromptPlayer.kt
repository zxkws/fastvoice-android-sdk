package com.zxkws.fastvoice.internal

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.Closeable
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal interface FallbackPromptPlayer : Closeable {
    /** Returns whether this prompt was accepted for asynchronous local playback. */
    fun speak(prompt: String, onComplete: (Boolean) -> Unit): Boolean

    fun stop()
}

/** Default last-resort player backed by the device's installed Android TextToSpeech engine. */
internal class AndroidTextToSpeechPromptPlayer(
    context: Context,
    private val onDiagnostic: (String, String, Throwable?) -> Unit,
) : FallbackPromptPlayer {
    private companion object {
        const val INITIALIZATION_TIMEOUT_MS = 8_000L
        const val UTTERANCE_TIMEOUT_MS = 30_000L
    }

    private data class Request(
        val id: String,
        val prompt: String,
        val onComplete: (Boolean) -> Unit,
    )

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private var engine: TextToSpeech? = null
    private var initialized = false
    private var request: Request? = null
    private var engineGeneration = 0L
    private var watchdog: Runnable? = null

    override fun speak(prompt: String, onComplete: (Boolean) -> Unit): Boolean {
        if (prompt.isBlank() || closed.get()) return false
        val next = Request(UUID.randomUUID().toString(), prompt, onComplete)
        mainHandler.post {
            if (closed.get()) {
                onComplete(false)
                return@post
            }
            request = next
            cancelWatchdog()
            runCatching { engine?.stop() }
            if (initialized) {
                start(next)
            } else {
                scheduleWatchdog(next.id, INITIALIZATION_TIMEOUT_MS)
                if (engine == null) initialize()
            }
        }
        return true
    }

    override fun stop() {
        if (closed.get()) return
        mainHandler.post {
            request = null
            cancelWatchdog()
            releaseEngine()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        mainHandler.post {
            request = null
            cancelWatchdog()
            releaseEngine()
        }
    }

    private fun initialize() {
        if (closed.get() || engine != null) return
        val generation = ++engineGeneration
        engine = TextToSpeech(appContext) { status ->
            // Some engines invoke this callback on a binder thread. Keep every engine operation on
            // the main looper and post once so the field assignment above has completed.
            mainHandler.post { finishInitialization(generation, status) }
        }
    }

    private fun finishInitialization(generation: Long, status: Int) {
        if (closed.get() || generation != engineGeneration) return
        val current = engine ?: return
        if (status != TextToSpeech.SUCCESS) {
            failCurrent("fallback_tts_initialization_failed", status.toString())
            return
        }
        val language = runCatching { current.setLanguage(Locale.SIMPLIFIED_CHINESE) }
            .getOrElse { error ->
                failCurrent("fallback_tts_language_failed", error.message.orEmpty(), error)
                return
            }
        if (language == TextToSpeech.LANG_MISSING_DATA ||
            language == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            failCurrent("fallback_tts_language_unavailable", language.toString())
            return
        }
        runCatching {
            current.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
        }
        current.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit

            override fun onDone(utteranceId: String) {
                mainHandler.post { complete(utteranceId, true) }
            }

            @Deprecated("Deprecated by Android")
            override fun onError(utteranceId: String) {
                mainHandler.post {
                    onDiagnostic("fallback_tts_playback_failed", "TextToSpeech.ERROR", null)
                    complete(utteranceId, false)
                }
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                mainHandler.post {
                    onDiagnostic("fallback_tts_playback_failed", errorCode.toString(), null)
                    complete(utteranceId, false)
                }
            }
        })
        initialized = true
        cancelWatchdog()
        request?.let(::start)
    }

    private fun start(next: Request) {
        if (closed.get() || request?.id != next.id) return
        scheduleWatchdog(next.id, UTTERANCE_TIMEOUT_MS)
        val result = runCatching {
            engine?.speak(next.prompt, TextToSpeech.QUEUE_FLUSH, null, next.id)
                ?: TextToSpeech.ERROR
        }.getOrElse { error ->
            onDiagnostic("fallback_tts_playback_failed", error.message.orEmpty(), error)
            TextToSpeech.ERROR
        }
        if (result == TextToSpeech.ERROR) {
            onDiagnostic("fallback_tts_playback_failed", result.toString(), null)
            complete(next.id, false)
        }
    }

    private fun complete(utteranceId: String, success: Boolean) {
        val completed = request?.takeIf { it.id == utteranceId } ?: return
        request = null
        cancelWatchdog()
        completed.onComplete(success)
    }

    private fun failCurrent(code: String, message: String, error: Throwable? = null) {
        onDiagnostic(code, message, error)
        val failed = request
        request = null
        cancelWatchdog()
        releaseEngine()
        failed?.let { it.onComplete(false) }
    }

    private fun scheduleWatchdog(utteranceId: String, timeoutMillis: Long) {
        cancelWatchdog()
        val timeout = Runnable {
            if (!closed.get() && request?.id == utteranceId) {
                failCurrent("fallback_tts_timeout", timeoutMillis.toString())
            }
        }
        watchdog = timeout
        mainHandler.postDelayed(timeout, timeoutMillis)
    }

    private fun cancelWatchdog() {
        watchdog?.let(mainHandler::removeCallbacks)
        watchdog = null
    }

    private fun releaseEngine() {
        engineGeneration += 1
        initialized = false
        engine?.let { current ->
            runCatching { current.stop() }
            runCatching { current.shutdown() }
        }
        engine = null
    }
}
