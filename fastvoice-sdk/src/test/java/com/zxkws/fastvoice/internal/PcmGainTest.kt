package com.zxkws.fastvoice.internal

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PcmGainTest {
    @Test
    fun boostPreservesSignAndSaturates() {
        val input = pcm(1_000, -1_000, 10_000, -10_000)

        assertArrayEquals(
            shortArrayOf(4_000, -4_000, Short.MAX_VALUE, Short.MIN_VALUE),
            samples(boostPcm16(input, 4)),
        )
    }

    @Test
    fun boostRejectsNonPositiveGain() {
        assertThrows(IllegalArgumentException::class.java) {
            boostPcm16(byteArrayOf(0, 0), 0)
        }
    }

    private fun pcm(vararg samples: Int): ByteArray =
        ByteBuffer.allocate(samples.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { samples.forEach { putShort(it.toShort()) } }
            .array()

    private fun samples(pcm: ByteArray): ShortArray =
        ShortArray(pcm.size / 2).also {
            ByteBuffer.wrap(pcm)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(it)
        }
}
