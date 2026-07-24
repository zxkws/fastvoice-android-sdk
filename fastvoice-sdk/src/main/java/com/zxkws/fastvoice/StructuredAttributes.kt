package com.zxkws.fastvoice

import com.zxkws.fastvoice.internal.JsonEncoder
import java.util.Collections
import kotlin.text.Charsets.UTF_8

/** Shared validation and deep immutable copying for public structured attribute maps. */
internal object StructuredAttributes {
    private val IDENTIFIER_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    private val ATTRIBUTE_KEY_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_.:-]{0,127}$")
    private const val MAX_DEPTH = 4
    private const val MAX_NODES = 512
    private const val MAX_COLLECTION_SIZE = 128
    private const val MAX_STRING_CODE_POINTS = 2_048
    private const val MAX_ENCODED_BYTES = 16_384

    private class ValidationState(var nodes: Int = 0)

    fun requireIdentifier(label: String, value: String) {
        require(IDENTIFIER_PATTERN.matches(value)) {
            "$label must match ${IDENTIFIER_PATTERN.pattern}"
        }
    }

    fun freeze(value: Map<*, *>): Map<String, Any?> {
        val result = freezeObject(value, 0, ValidationState())
        require(JsonEncoder.encode(result).toByteArray(UTF_8).size <= MAX_ENCODED_BYTES) {
            "attributes exceed 16 KiB"
        }
        return result
    }

    private fun freezeObject(
        value: Map<*, *>,
        depth: Int,
        state: ValidationState,
    ): Map<String, Any?> {
        countNode(depth, state)
        require(value.size <= MAX_COLLECTION_SIZE) { "attributes object has too many fields" }
        val copy = LinkedHashMap<String, Any?>(value.size)
        value.forEach { (rawKey, child) ->
            require(rawKey is String && ATTRIBUTE_KEY_PATTERN.matches(rawKey)) {
                "attribute key is invalid: $rawKey"
            }
            copy[rawKey] = freezeValue(child, depth + 1, state)
        }
        return Collections.unmodifiableMap(copy)
    }

    private fun freezeValue(value: Any?, depth: Int, state: ValidationState): Any? = when (value) {
        null, is Boolean, is Byte, is Short, is Int, is Long -> {
            countNode(depth, state)
            value
        }
        is String -> {
            countNode(depth, state)
            require(value.codePointCount(0, value.length) <= MAX_STRING_CODE_POINTS) {
                "attribute string is too long"
            }
            require(value.none(::isRejectedControlCharacter)) {
                "attribute string contains a control character"
            }
            value
        }
        is Float -> {
            countNode(depth, state)
            require(value.isFinite()) { "attribute numbers must be finite" }
            value
        }
        is Double -> {
            countNode(depth, state)
            require(value.isFinite()) { "attribute numbers must be finite" }
            value
        }
        is Map<*, *> -> freezeObject(value, depth, state)
        is Iterable<*> -> freezeList(value.toList(), depth, state)
        is Array<*> -> freezeList(value.asList(), depth, state)
        else -> throw IllegalArgumentException(
            "unsupported attribute value: ${value::class.java.name}",
        )
    }

    private fun freezeList(
        value: List<*>,
        depth: Int,
        state: ValidationState,
    ): List<Any?> {
        countNode(depth, state)
        require(value.size <= MAX_COLLECTION_SIZE) { "attributes array is too long" }
        return Collections.unmodifiableList(
            value.map { freezeValue(it, depth + 1, state) },
        )
    }

    private fun countNode(depth: Int, state: ValidationState) {
        require(depth <= MAX_DEPTH) { "attributes exceed maximum depth" }
        state.nodes += 1
        require(state.nodes <= MAX_NODES) { "attributes contain too many nodes" }
    }

    private fun isRejectedControlCharacter(character: Char): Boolean =
        character.code in 0x00..0x08 ||
            character.code == 0x0b ||
            character.code == 0x0c ||
            character.code in 0x0e..0x1f ||
            character.code == 0x7f
}
