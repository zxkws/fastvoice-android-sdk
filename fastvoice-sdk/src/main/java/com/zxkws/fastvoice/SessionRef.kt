package com.zxkws.fastvoice

/** An exact revision of an application session. */
data class SessionRef(
    val id: String,
    val rev: Long,
) {
    init {
        StructuredAttributes.requireIdentifier("session id", id)
        require(rev >= 1L) { "session rev must be positive" }
    }

    companion object {
        @JvmStatic
        fun of(id: String, rev: Long): SessionRef = SessionRef(id, rev)
    }
}
