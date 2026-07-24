package com.zxkws.fastvoice.internal

import com.zxkws.fastvoice.ContentRequest

/** Connection-independent state for content requests awaiting an acknowledgement or error. */
internal class ContentRequestState {
    enum class EnqueueResult {
        ACCEPTED,
        EXACT_RETRY,
        ID_CONFLICT,
    }

    private val requests = linkedMapOf<String, ContentRequest>()

    @Synchronized
    fun enqueue(request: ContentRequest): EnqueueResult {
        val existing = requests[request.id]
        if (existing != null) {
            return if (existing == request) EnqueueResult.EXACT_RETRY else EnqueueResult.ID_CONFLICT
        }
        requests[request.id] = request
        return EnqueueResult.ACCEPTED
    }

    /** Returns a stable copy in original insertion sequence for reconnect replay. */
    @Synchronized
    fun pending(): List<ContentRequest> = requests.values.toList()

    @Synchronized
    fun complete(id: String): Boolean = requests.remove(id) != null

    @Synchronized
    fun cancelAll(): List<ContentRequest> {
        val cancelled = requests.values.toList()
        requests.clear()
        return cancelled
    }
}
