package org.polyfrost.polyplus.client.party

/** Dusk: Epic's voice rooms are not part of this fork, so a room never starts. */
internal object RtcVoice {
    sealed interface Outcome {
        data object Left : Outcome
        data object WrongTransport : Outcome
        data class Failed(val reason: String) : Outcome
    }

    val joined: Boolean get() = false
    val running: Boolean get() = false

    @Suppress("UNUSED_PARAMETER")
    fun start(party: String, onOutcome: (Outcome) -> Unit) = onOutcome(Outcome.Failed("not available"))
    fun stop() = Unit
    @Suppress("UNUSED_PARAMETER") fun setTransmit(on: Boolean) = Unit
    @Suppress("UNUSED_PARAMETER") fun setDeafened(on: Boolean) = Unit
    @Suppress("UNUSED_PARAMETER") fun setInputGain(gain: Float) = Unit
    @Suppress("UNUSED_PARAMETER") fun apply(mix: Map<String, PeerMix>, outputGain: Float) = Unit
    fun speaking(): Set<String> = emptySet()
    fun deviceCounts(): Pair<Int, Int>? = null
    @Suppress("UNUSED_PARAMETER") fun applyDevices(input: String?, output: String?) = Unit
}
