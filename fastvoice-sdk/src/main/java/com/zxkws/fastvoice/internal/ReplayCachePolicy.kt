package com.zxkws.fastvoice.internal

internal object ReplayCachePolicy {
    fun <T> select(
        currentGenerationActive: Boolean,
        current: List<T>,
        pending: List<T>,
        lastCompleted: List<T>,
    ): List<T> = if (currentGenerationActive) {
        pending.ifEmpty { current }
    } else {
        lastCompleted
    }
}
