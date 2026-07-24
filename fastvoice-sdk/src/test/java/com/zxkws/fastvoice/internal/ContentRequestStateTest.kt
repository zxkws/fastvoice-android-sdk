package com.zxkws.fastvoice.internal

import com.zxkws.fastvoice.ContentRequest
import com.zxkws.fastvoice.SessionRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentRequestStateTest {
    private fun request(
        id: String,
        key: String = "welcome",
        session: SessionRef? = null,
    ) = ContentRequest(id, key, session, emptyMap())

    @Test
    fun exactRetryIsIdempotentButReusingAnIdForAnotherPayloadFails() {
        val state = ContentRequestState()
        val first = request("c1")

        assertEquals(ContentRequestState.EnqueueResult.ACCEPTED, state.enqueue(first))
        assertEquals(
            ContentRequestState.EnqueueResult.EXACT_RETRY,
            state.enqueue(request("c1")),
        )
        assertEquals(
            ContentRequestState.EnqueueResult.ID_CONFLICT,
            state.enqueue(request("c1", key = "different")),
        )
        assertEquals(listOf(first), state.pending())
    }

    @Test
    fun reconnectReplayPreservesInsertionSequenceUntilCompletion() {
        val state = ContentRequestState()
        val first = request("c1")
        val second = request("c2")
        state.enqueue(first)
        state.enqueue(second)

        assertEquals(listOf(first, second), state.pending())
        assertTrue(state.complete("c1"))
        assertFalse(state.complete("c1"))
        assertEquals(listOf(second), state.pending())
    }

    @Test
    fun endingASessionCancelsEveryPendingContentRequest() {
        val state = ContentRequestState()
        val target = request("c1", session = SessionRef("s1", 1))
        val another = request("c2", session = SessionRef("s2", 1))
        val unbound = request("c3")
        listOf(target, another, unbound).forEach(state::enqueue)

        assertEquals(listOf(target, another, unbound), state.cancelAll())
        assertTrue(state.pending().isEmpty())
    }
}
