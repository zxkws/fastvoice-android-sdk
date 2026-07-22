package com.zxkws.fastvoice.internal

/** Keeps display-only commands-v1 state events from mutating client transport state. */
internal object ServerStateSideEffectPolicy {
    fun appliesLegacySleepingSideEffects(commandProtocol: Boolean, stateValue: String): Boolean =
        !commandProtocol && stateValue == "sleeping"

    fun appliesLegacyListeningSideEffects(commandProtocol: Boolean, stateValue: String): Boolean =
        !commandProtocol && stateValue == "listening"
}
