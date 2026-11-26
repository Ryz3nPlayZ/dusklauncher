package org.polyfrost.polyplus.client.launcher

import net.minecraft.client.Minecraft

object SessionAccounts {
    /** The account the game is signed in as. */
    fun current(): OneLauncherAccounts.Account? = runCatching {
        val user = Minecraft.getInstance().user
        OneLauncherAccounts.Account(user.profileId, user.name, active = true)
    }.getOrNull()
}
