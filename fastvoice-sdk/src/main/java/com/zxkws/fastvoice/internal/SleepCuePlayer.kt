package com.zxkws.fastvoice.internal

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.zxkws.fastvoice.R
import java.io.Closeable

/** Plays the tiny SDK-local inactivity cue. */
internal class SleepCuePlayer(
    private val context: Context,
    private val canPlay: () -> Boolean,
    private val onFailure: (Exception) -> Unit,
) : Closeable {
    private var player: MediaPlayer? = null
    private var closed = false

    @Synchronized
    fun play() {
        if (closed) return
        playLocked()
    }

    @Synchronized
    fun cancel() {
        releaseLocked()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        releaseLocked()
    }

    private fun playLocked() {
        if (!canPlay()) return
        releaseLocked()

        try {
            val sound = MediaPlayer.create(
                context,
                R.raw.sleep_cue,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
                0,
            ) ?: error("failed to create sleep cue player")

            player = sound
            sound.setOnCompletionListener { finished -> release(finished) }
            sound.setOnErrorListener { failed, what, extra ->
                release(failed)
                if (!closed) {
                    onFailure(IllegalStateException("sleep cue playback failed: $what/$extra"))
                }
                true
            }

            if (!canPlay()) {
                releaseLocked()
                return
            }
            sound.start()
        } catch (error: Exception) {
            releaseLocked()
            if (!closed) onFailure(error)
        }
    }

    private fun release(sound: MediaPlayer) {
        synchronized(this) {
            if (player !== sound) return
            player = null
            runCatching { sound.release() }
        }
    }

    private fun releaseLocked() {
        val sound = player ?: return
        player = null
        runCatching { sound.stop() }
        runCatching { sound.release() }
    }
}
