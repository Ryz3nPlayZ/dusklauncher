package org.polyfrost.polyplus.client.lobby

//? if > 1.8.9 {
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.GenericMessageScreen
import net.minecraft.client.gui.screens.LevelLoadingScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
//? if < 1.21.9
/*import net.minecraft.client.gui.screens.ReceivingLevelScreen*/
import org.polyfrost.polyplus.client.utils.ClientPlatform
import org.polyfrost.polyplus.mixin.client.access.MinecraftAccessor

/**
 * Keeps singleplayer joins from being left half done.
 *
 * Vanilla stops the integrated server only when its owner leaves from the play phase. A join dropped earlier (during
 * login or configuration) leaves that server running with the world locked, and the next world open then waits on it
 * forever inside `disconnect`.
 */
object LocalJoin {
    private var warnedFor: Any? = null

    /** A local server is up, the client has no level yet, and the join has not failed. */
    @JvmStatic
    fun inFlight(): Boolean {
        val mc = Minecraft.getInstance()
        val server = mc.singleplayerServer ?: return false
        if (mc.level != null || server.isShutdown) return false
        val pending = (mc as MinecraftAccessor).`polyplus$getPendingConnection`() ?: return !server.isReady
        return pending.isConnected
    }

    /** A local server outlived its join: no level, and no join still on the loading screen. */
    @JvmStatic
    fun stranded(): Boolean {
        val mc = Minecraft.getInstance()
        val server = mc.singleplayerServer ?: return false
        return mc.level == null && !server.isShutdown && !onLoadingScreen()
    }

    /** The world loading screen is up for a join that is still going; something asked to swap it for the menu. */
    @JvmStatic
    fun holdsLoading(): Boolean {
        if (!onLoadingScreen()) return false
        val server = Minecraft.getInstance().singleplayerServer
        if (warnedFor !== server) {
            warnedFor = server
            LOBBY_LOGGER.warn("Kept the world loading screen: something tried to leave a singleplayer join for the menu", Throwable("caller"))
        }
        return true
    }

    @JvmStatic
    fun onLoadingScreen(): Boolean = inFlight() && loading(ClientPlatform.currentScreen())

    /**
     * Runs at the start of `disconnect`. When the local server's owner never reached the play phase, nothing else
     * will stop it, so close the half-open connection and halt the server so the wait for shutdown can finish.
     */
    @JvmStatic
    fun dropUnfinished(mc: Minecraft) {
        val server = mc.singleplayerServer ?: return
        if (mc.connection != null || server.isShutdown) return
        LOBBY_LOGGER.warn("Stopping a singleplayer server whose join never finished")
        val access = mc as MinecraftAccessor
        val pending = access.`polyplus$getPendingConnection`()
        if (pending != null) {
            if (pending.isConnected) pending.disconnect(Component.translatable("multiplayer.disconnect.generic"))
            access.`polyplus$setPendingConnection`(null)
        }
        server.halt(false)
    }

    /** Before a world opens: stop a stranded server first so it lets go of its world and the new load cannot hang. */
    @JvmStatic
    fun clearStranded(mc: Minecraft) {
        if (!stranded()) return
        LOBBY_LOGGER.warn("Closing a leftover singleplayer server before opening a world")
        mc.disconnect(ClientPlatform.currentScreen() ?: GenericMessageScreen(Component.translatable("menu.savingLevel")), false)
    }

    private fun loading(screen: Screen?): Boolean {
        //? if >= 1.21.9 {
        return screen is LevelLoadingScreen
        //?} else {
        /*return screen is LevelLoadingScreen || screen is ReceivingLevelScreen
        *///?}
    }
}
//?} else {
/*object LocalJoin {
    @JvmStatic
    fun holdsLoading(): Boolean = false
}
*///?}
