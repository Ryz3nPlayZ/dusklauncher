package org.polyfrost.polyplus.client.network.eos

/** Dusk: no Epic SDK in this fork, so it is never supported. */
object EosNativeSupport {
    const val platform = "none"
    val unsupportedReason: String? get() = "Party voice isn't available."
}
