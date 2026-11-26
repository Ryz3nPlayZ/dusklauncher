package org.polyfrost.polyplus.client.social

import org.polyfrost.polyplus.client.network.http.responses.SpecialChatStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Dusk: the special chat is Polyfrost's, so nobody is a target. */
object SpecialChatRepository {
    val status: StateFlow<SpecialChatStatus?> = MutableStateFlow(null)

    fun refreshTargets() = Unit
}
