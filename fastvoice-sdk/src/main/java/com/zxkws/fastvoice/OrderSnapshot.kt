package com.zxkws.fastvoice

import java.util.Collections

/**
 * One complete, immutable order snapshot.
 *
 * [context] is replaced as a whole by the server. It is never interpreted as a merge patch.
 */
class OrderSnapshot(
    val id: String,
    val rev: Long,
    context: Map<String, Any?>,
) {
    val context: Map<String, Any?> = freezeObject(context, 0)

    init {
        require(ID_PATTERN.matches(id)) {
            "order id must match ${ID_PATTERN.pattern}"
        }
        require(rev >= 0L) { "order rev must be non-negative" }
    }

    override fun equals(other: Any?): Boolean =
        other is OrderSnapshot && id == other.id && rev == other.rev && context == other.context

    override fun hashCode(): Int = 31 * (31 * id.hashCode() + rev.hashCode()) + context.hashCode()

    override fun toString(): String = "OrderSnapshot(id=$id, rev=$rev, context=$context)"

    class Builder(private val id: String, private val rev: Long) {
        private val context = linkedMapOf<String, Any?>()

        fun put(name: String, value: Any?) = apply {
            require(name.isNotBlank()) { "order context key must not be blank" }
            context[name] = value
        }

        fun putAll(values: Map<String, Any?>) = apply {
            require(values.keys.none(String::isBlank)) {
                "order context keys must not be blank"
            }
            context.putAll(values)
        }

        fun build(): OrderSnapshot = OrderSnapshot(id, rev, context)
    }

    companion object {
        private val ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
        private val KEY_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_.:-]{0,127}$")
        private const val MAX_DEPTH = 4

        private fun freezeObject(value: Map<*, *>, depth: Int): Map<String, Any?> {
            require(depth <= MAX_DEPTH) { "order context exceeds maximum depth" }
            val copy = LinkedHashMap<String, Any?>(value.size)
            value.forEach { (rawKey, child) ->
                require(rawKey is String && KEY_PATTERN.matches(rawKey)) {
                    "order context key is invalid: $rawKey"
                }
                copy[rawKey] = freezeValue(child, depth + 1)
            }
            return Collections.unmodifiableMap(copy)
        }

        private fun freezeValue(value: Any?, depth: Int): Any? = when (value) {
            null, is String, is Boolean, is Byte, is Short, is Int, is Long -> value
            is Float -> {
                require(value.isFinite()) { "order context numbers must be finite" }
                value
            }
            is Double -> {
                require(value.isFinite()) { "order context numbers must be finite" }
                value
            }
            is Map<*, *> -> freezeObject(value, depth)
            is Iterable<*> -> {
                require(depth <= MAX_DEPTH) { "order context exceeds maximum depth" }
                Collections.unmodifiableList(value.map { freezeValue(it, depth + 1) })
            }
            is Array<*> -> {
                require(depth <= MAX_DEPTH) { "order context exceeds maximum depth" }
                Collections.unmodifiableList(value.map { freezeValue(it, depth + 1) })
            }
            else -> throw IllegalArgumentException(
                "unsupported order context value: ${value::class.java.name}",
            )
        }

        @JvmStatic
        fun builder(id: String, rev: Long): Builder = Builder(id, rev)
    }
}
