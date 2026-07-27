package com.zxkws.fastvoice.internal

import android.os.SystemClock
import java.io.Closeable

/** Software AEC3 with a 48 kHz render reference and a 16 kHz microphone stream. */
internal class WebRtcEchoCanceller(
    private val captureRate: Int,
    private val renderRate: Int,
) : Closeable {
    companion object {
        private const val SUBFRAME_MS = 10
        const val DEFAULT_DELAY_MS = 240
        const val RENDER_TAIL_MS = 600L

        init {
            System.loadLibrary("webrtc-audio-processing-2")
            System.loadLibrary("fastvoice_webrtc_aec3")
        }
    }

    private val captureSamples = captureRate / 1_000 * SUBFRAME_MS
    private val renderSamples = renderRate / 1_000 * SUBFRAME_MS
    private val captureBytes = captureSamples * 2
    private val renderBytes = renderSamples * 2
    private val captureInput = ShortArray(captureSamples)
    private val captureOutput = ShortArray(captureSamples)
    private val renderInput = ShortArray(renderSamples)
    private val renderPending = ByteArray(renderBytes)
    private var renderPendingSize = 0
    private var lastRenderAtMs = Long.MIN_VALUE
    private var handle = nativeCreate(captureRate, renderRate).also {
        check(it != 0L) { "WebRTC AEC3 create failed" }
    }

    @Synchronized
    fun reset() {
        requireOpen()
        val replacement = nativeCreate(captureRate, renderRate)
        check(replacement != 0L) { "WebRTC AEC3 reset failed" }
        val previous = handle
        handle = replacement
        renderPendingSize = 0
        lastRenderAtMs = Long.MIN_VALUE
        nativeClose(previous)
    }

    /** Feeds exactly the PCM bytes accepted by AudioTrack, preserving partial 10 ms frames. */
    @Synchronized
    fun acceptRender(pcm: ByteArray, offset: Int, length: Int) {
        requireOpen()
        require(offset >= 0 && length >= 0 && offset + length <= pcm.size) {
            "invalid render PCM range"
        }
        var sourceOffset = offset
        var remaining = length
        while (remaining > 0) {
            val copied = minOf(remaining, renderBytes - renderPendingSize)
            pcm.copyInto(
                destination = renderPending,
                destinationOffset = renderPendingSize,
                startIndex = sourceOffset,
                endIndex = sourceOffset + copied,
            )
            renderPendingSize += copied
            sourceOffset += copied
            remaining -= copied
            if (renderPendingSize == renderBytes) {
                bytesToShorts(renderPending, 0, renderInput)
                check(nativeProcessRender(handle, renderInput) == 0) {
                    "WebRTC AEC3 render failed"
                }
                renderPendingSize = 0
                lastRenderAtMs = SystemClock.elapsedRealtime()
            }
        }
    }

    /** Processes one 20 ms mono PCM frame as two WebRTC-native 10 ms subframes. */
    @Synchronized
    fun processCapture(pcm: ByteArray): ByteArray {
        requireOpen()
        require(pcm.size == captureBytes * 2) {
            "capture PCM must contain exactly 20 ms"
        }
        val processed = ByteArray(pcm.size)
        repeat(2) { subframe ->
            val byteOffset = subframe * captureBytes
            bytesToShorts(pcm, byteOffset, captureInput)
            check(
                nativeProcessCapture(
                    handle,
                    captureInput,
                    captureOutput,
                    DEFAULT_DELAY_MS,
                ) == 0,
            ) {
                "WebRTC AEC3 capture failed"
            }
            shortsToBytes(captureOutput, processed, byteOffset)
        }
        return processed
    }

    @Synchronized
    fun hasRecentRender(nowMs: Long = SystemClock.elapsedRealtime()): Boolean =
        lastRenderAtMs != Long.MIN_VALUE &&
            nowMs >= lastRenderAtMs &&
            nowMs - lastRenderAtMs <= RENDER_TAIL_MS

    @Synchronized
    fun delayEstimateMs(): Int {
        requireOpen()
        return nativeDelayEstimateMs(handle)
    }

    @Synchronized
    override fun close() {
        val previous = handle
        handle = 0
        renderPendingSize = 0
        lastRenderAtMs = Long.MIN_VALUE
        if (previous != 0L) nativeClose(previous)
    }

    private fun requireOpen() {
        check(handle != 0L) { "WebRTC AEC3 is closed" }
    }

    private fun bytesToShorts(source: ByteArray, offset: Int, destination: ShortArray) {
        destination.indices.forEach { index ->
            val low = source[offset + index * 2].toInt() and 0xff
            val high = source[offset + index * 2 + 1].toInt()
            destination[index] = (low or (high shl 8)).toShort()
        }
    }

    private fun shortsToBytes(source: ShortArray, destination: ByteArray, offset: Int) {
        source.indices.forEach { index ->
            val sample = source[index].toInt()
            destination[offset + index * 2] = sample.toByte()
            destination[offset + index * 2 + 1] = (sample shr 8).toByte()
        }
    }

    private external fun nativeCreate(captureRate: Int, renderRate: Int): Long

    private external fun nativeProcessRender(handle: Long, input: ShortArray): Int

    private external fun nativeProcessCapture(
        handle: Long,
        input: ShortArray,
        output: ShortArray,
        delayMs: Int,
    ): Int

    private external fun nativeDelayEstimateMs(handle: Long): Int

    private external fun nativeClose(handle: Long)
}
