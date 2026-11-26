package org.polyfrost.polyplus.client.network.http

import org.polyfrost.polyplus.client.dusk.DuskBridge
import org.polyfrost.polyplus.client.dusk.bool
import org.polyfrost.polyplus.client.network.http.responses.PlayerStatus
import org.polyfrost.polyplus.client.utils.runSuspendCatching

object AccountApi {
    suspend fun status(): Result<PlayerStatus> = runSuspendCatching {
        if (DuskBridge.obj("GET", "/v1/me/privacy").bool("appearOffline")) PlayerStatus.Offline else PlayerStatus.Online
    }

    suspend fun setStatus(status: PlayerStatus): Result<Unit> = runSuspendCatching {
        // the whole object goes back, so the other choices stay as they were
        val privacy = DuskBridge.obj("GET", "/v1/me/privacy")
        privacy.addProperty("appearOffline", status == PlayerStatus.Offline)
        DuskBridge.call("PUT", "/v1/me/privacy", privacy)
        Unit
    }
}
