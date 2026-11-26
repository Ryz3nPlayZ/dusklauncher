package org.polyfrost.polyplus.client.network.eos

/** Dusk: Epic's voice rooms are not part of this fork. */
object EosRtcSupport {
    val platformSupported: Boolean get() = false
    val isMac: Boolean get() = false
    val isLinux: Boolean get() = false
    val unavailableReason: String get() = "not available"
}
