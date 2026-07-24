package com.zxkws.fastvoice

/**
 * Requests server-owned content by [key].
 *
 * [session] optionally binds the request to one exact session revision. The SDK deliberately
 * does not interpret [key] or [attributes].
 */
class ContentRequest @JvmOverloads constructor(
    val id: String,
    val key: String,
    val session: SessionRef? = null,
    attributes: Map<String, Any?> = emptyMap(),
) {
    val attributes: Map<String, Any?> = StructuredAttributes.freeze(attributes)

    init {
        StructuredAttributes.requireIdentifier("content request id", id)
        StructuredAttributes.requireIdentifier("content key", key)
    }

    override fun equals(other: Any?): Boolean =
        other is ContentRequest &&
            id == other.id && key == other.key &&
            session == other.session && attributes == other.attributes

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + key.hashCode()
        result = 31 * result + (session?.hashCode() ?: 0)
        return 31 * result + attributes.hashCode()
    }

    override fun toString(): String =
        "ContentRequest(id=$id, key=$key, session=$session, attributes=$attributes)"

    class Builder(private val id: String, private val key: String) {
        private var session: SessionRef? = null
        private val attributes = linkedMapOf<String, Any?>()

        fun session(value: SessionRef?) = apply {
            session = value
        }

        fun putAttribute(name: String, value: Any?) = apply {
            attributes[name] = value
        }

        fun putAllAttributes(values: Map<String, Any?>) = apply {
            attributes.putAll(values)
        }

        fun build(): ContentRequest = ContentRequest(id, key, session, attributes)
    }

    companion object {
        @JvmStatic
        fun builder(id: String, key: String): Builder = Builder(id, key)
    }
}
