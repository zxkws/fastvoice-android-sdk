package com.zxkws.fastvoice

/**
 * One complete, immutable application session snapshot.
 *
 * [attributes] is replaced as a whole by the server. It is never interpreted as a merge patch.
 */
class SessionSnapshot @JvmOverloads constructor(
    val id: String,
    val rev: Long,
    attributes: Map<String, Any?> = emptyMap(),
) {
    val attributes: Map<String, Any?> = StructuredAttributes.freeze(attributes)

    init {
        StructuredAttributes.requireIdentifier("session id", id)
        require(rev >= 1L) { "session rev must be positive" }
    }

    override fun equals(other: Any?): Boolean =
        other is SessionSnapshot &&
            id == other.id && rev == other.rev && attributes == other.attributes

    override fun hashCode(): Int =
        31 * (31 * id.hashCode() + rev.hashCode()) + attributes.hashCode()

    override fun toString(): String =
        "SessionSnapshot(id=$id, rev=$rev, attributes=$attributes)"

    class Builder(private val id: String, private val rev: Long) {
        private val attributes = linkedMapOf<String, Any?>()

        fun putAttribute(name: String, value: Any?) = apply {
            attributes[name] = value
        }

        fun putAllAttributes(values: Map<String, Any?>) = apply {
            attributes.putAll(values)
        }

        fun build(): SessionSnapshot = SessionSnapshot(id, rev, attributes)
    }

    companion object {
        @JvmStatic
        fun builder(id: String, rev: Long): Builder = Builder(id, rev)
    }
}
