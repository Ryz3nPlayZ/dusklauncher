package org.polyfrost.polyplus.client.network.http

import org.polyfrost.polyplus.client.dusk.DuskBridge
import org.polyfrost.polyplus.client.dusk.DuskGroups
import org.polyfrost.polyplus.client.dusk.DuskSocial
import org.polyfrost.polyplus.client.privacy.OnlineServicesDisabledException

/** Dusk signs in through DuskClient; there's no second service left to hold a token for. */
object PolyAuthorization {
    class SessionRejectedException : Exception("Mojang rejected the current session")

    fun isSessionRejected(): Boolean = false

    fun current(): String = throw OnlineServicesDisabledException("account/login")

    fun refresh(): String = current()

    /** Another account is in use: sign in again as it. */
    fun reset() {
        DuskBridge.forget()
        DuskSocial.reset()
        DuskGroups.reset()
    }
}
