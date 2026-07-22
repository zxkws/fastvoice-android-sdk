package com.zxkws.fastvoice.internal

import java.util.concurrent.atomic.AtomicLong

/** Serializes WebSocket callback effects with stop/restart invalidation. */
internal class ClientSessionEpoch {
    private val epoch = AtomicLong()

    @Synchronized
    fun begin(): Long = epoch.incrementAndGet()

    @Synchronized
    fun invalidate() {
        epoch.incrementAndGet()
    }

    @Synchronized
    fun runIfCurrent(token: Long, effect: () -> Unit): Boolean {
        if (token != epoch.get()) return false
        effect()
        return true
    }

    fun isCurrent(token: Long): Boolean = token == epoch.get()
}
