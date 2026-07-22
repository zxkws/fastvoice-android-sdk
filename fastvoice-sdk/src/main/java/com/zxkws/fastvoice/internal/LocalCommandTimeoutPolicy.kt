package com.zxkws.fastvoice.internal

/** Derives the packet-loss fallback from the server's earlier confirmation deadline. */
internal object LocalCommandTimeoutPolicy {
    const val DEFAULT_SERVER_TIMEOUT_MS = 800L
    const val ACCEPTED_ACTION_TIMEOUT_MS = 1_500L
    private const val MIN_SERVER_TIMEOUT_MS = 200L
    private const val MAX_SERVER_TIMEOUT_MS = 3_000L
    private const val CLIENT_GRACE_MS = 250L

    fun clientTimeoutMs(advertisedServerTimeoutMs: Long): Long =
        advertisedServerTimeoutMs.coerceIn(MIN_SERVER_TIMEOUT_MS, MAX_SERVER_TIMEOUT_MS) +
            CLIENT_GRACE_MS
}
