package com.zxkws.fastvoice.internal

import android.content.Context
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Persists anti-replay versions. Wall-clock milliseconds make a clean reinstall very unlikely to
 * reuse an older server watermark, while the stored value protects against clock rollback.
 */
internal class MonotonicVersionStore(
    context: Context,
    endpoint: String,
    deviceId: String,
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "com.zxkws.fastvoice.protocol_versions",
        Context.MODE_PRIVATE,
    )
    private val scope = sha256("$endpoint\u0000$deviceId")

    @Synchronized
    fun nextContextVersion(): Long = next("$scope.context")

    @Synchronized
    fun nextArrivalVersion(): Long = next("$scope.arrival")

    private fun next(key: String): Long {
        val previous = preferences.getLong(key, -1L)
        val next = maxOf(previous + 1L, System.currentTimeMillis())
        check(preferences.edit().putLong(key, next).commit()) {
            "Unable to persist protocol version"
        }
        return next
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
