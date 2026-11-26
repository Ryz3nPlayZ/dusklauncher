package org.polyfrost.polyplus.client.launcher

import com.mojang.authlib.GameProfile
import net.minecraft.client.Minecraft
import org.apache.logging.log4j.LogManager
import org.polyfrost.polyplus.client.cosmetics.LocalAccountSkin
import org.polyfrost.polyplus.client.cosmetics.MojangSkinService
import org.polyfrost.polyplus.client.gui.lobbyshell.LobbyRealms
import org.polyfrost.polyplus.client.utils.ClientPlatform
//? if > 1.8.9
import org.polyfrost.polyplus.compat.AccountSwitchCompat
import java.util.UUID

/** Catches this mod up after DuskClient has signed the game in as another account. */
object AccountSwitch {
    private val LOGGER = LogManager.getLogger("PolyPlus/Accounts")

    fun onSwitched(newId: UUID) {
        val profile = fetchProfile(newId)
        ClientPlatform.runOnMainSync {
            LocalAccountSkin.onAccountSwitched(newId, profile)
            //? if > 1.8.9 {
            val user = Minecraft.getInstance().user
            AccountSwitchCompat.notifySwitch(user.profileId, user.name, user.accessToken)
            //?}
        }
        MojangSkinService.clearLocalOverride()
        LobbyRealms.onAccountSwitched()
    }

    private fun fetchProfile(id: UUID): GameProfile? = runCatching {
        //? if >= 1.21.10 {
        Minecraft.getInstance().services().sessionService().fetchProfile(id, true)?.profile()
        //?} elif > 1.8.9 {
        /*Minecraft.getInstance().minecraftSessionService.fetchProfile(id, true)?.profile()
        *///?} else {
        /*Minecraft.getInstance().sessionService.fillProfileProperties(GameProfile(id, Minecraft.getInstance().session.profile.name), true)
        *///?}
    }.onFailure {
        LOGGER.warn("Could not fetch the game profile for {}", id, it)
    }.getOrNull()
}
