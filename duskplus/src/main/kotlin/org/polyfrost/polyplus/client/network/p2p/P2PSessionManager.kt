package org.polyfrost.polyplus.client.network.p2p

import org.polyfrost.polyplus.client.network.http.PartyApi
import org.polyfrost.polyplus.client.party.Parties
//? if > 1.8.9
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.TitleScreen
//? if > 1.8.9 {
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
//?} else {
/*import net.minecraft.client.options.ServerListEntry
import org.polyfrost.polyplus.client.social.execute
import org.polyfrost.polyplus.client.social.setScreen
*///?}
import org.apache.logging.log4j.LogManager
import org.polyfrost.polyplus.client.PolyPlusClient
import org.polyfrost.polyplus.client.dusk.DuskHosting
import org.polyfrost.polyplus.client.network.eos.EosP2PSocketId
import org.polyfrost.polyplus.client.network.eos.EosProductUserId
import org.polyfrost.polyplus.client.network.eos.EosSdkBridge
import org.polyfrost.polyplus.client.network.http.SessionsApi
import org.polyfrost.polyplus.client.network.http.responses.SessionInvite
import org.polyfrost.polyplus.client.network.http.responses.SessionResponse
import org.polyfrost.polyplus.client.resourcepack.HostSharedPack
import org.polyfrost.polyplus.client.resourcepack.PackHttpBridge
import org.polyfrost.polyplus.client.social.SessionsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Dusk: upstream connected players over Epic Online Services. Here the host's own game serves the
 * world (opened to LAN, reachable through e4mc) and an accepted invite joins its address directly.
 */
object P2PSessionManager {
    // names hosted worlds in saved play stats, whose real address changes every time
    const val HOSTED_KEY = "127.6.6.6"

    private val LOGGER = LogManager.getLogger("PolyPlus/Hosting")

    private const val REJOIN_COOLDOWN_NANOS = 2_000_000_000L

    data class JoinTarget(val host: EosProductUserId, val socket: EosP2PSocketId)

    internal val activeBridge: EosSdkBridge? get() = null

    @Volatile
    var joinedSessionId: String? = null
        private set

    private val _rejoinSessionId = MutableStateFlow<String?>(null)
    val rejoinSessionId = _rejoinSessionId.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionIdFlow = _currentSessionId.asStateFlow()
    val currentSessionId: String? get() = _currentSessionId.value

    private val _status = MutableStateFlow<EosStatus>(EosStatus.Ready)
    val status = _status.asStateFlow()

    @Volatile
    private var lastInvite: SessionInvite? = null

    @Volatile
    private var joinedAddress: String? = null

    @Volatile
    private var lastRejoinNanos = 0L

    /** Whether [address] is a friend's world that was joined from an invite. */
    fun isHosted(address: String?): Boolean = address != null && address.trim() == joinedAddress

    fun earlyInitialize() {
        DuskHosting.initialize()
        SessionsRepository.acceptedInvites
            .onEach { invite -> PolyPlusClient.SCOPE.launch { join(invite) } }
            .launchIn(PolyPlusClient.SCOPE)
        HostSharedPack.registerConfigurationHook()
        //? if > 1.8.9 {
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> onDisconnected() }
        //?}
    }

    //? if = 1.8.9 {
    /*@JvmStatic
    fun onClientDisconnected() = onDisconnected()
    *///?}

    private fun onDisconnected() {
        joinedSessionId = null
        stopHosting()
        DuskHosting.closed()
        PackHttpBridge.setPackSource(null)
        if (_rejoinSessionId.value != null) SessionsRepository.refreshIncoming()
    }

    fun applyConsent() {
        // hosting needs no account of its own
    }

    fun reconnect() {
        stopHosting()
    }

    fun retryLoginNow() {
        // nothing to log in to
    }

    fun socketFor(sessionId: String): EosP2PSocketId = EosP2PSocketId(sessionId.replace("-", "").take(32))

    @JvmStatic
    fun canRejoin(): Boolean = _rejoinSessionId.value != null

    @JvmStatic
    fun rejoin() {
        if (_rejoinSessionId.value == null) return
        val invite = lastInvite ?: return
        val now = System.nanoTime()
        if (lastRejoinNanos != 0L && now - lastRejoinNanos < REJOIN_COOLDOWN_NANOS) return
        lastRejoinNanos = now
        join(invite)
    }

    fun forgetRejoin() {
        _rejoinSessionId.value = null
        lastInvite = null
    }

    @Suppress("UnusedParameter")
    fun setPrivateRelay(enabled: Boolean) = Unit

    @Suppress("UnusedParameter")
    fun setVoiceRelay(enabled: Boolean) = Unit

    @Suppress("UnusedParameter")
    fun setSceneRelay(enabled: Boolean) = Unit

    @Suppress("UnusedParameter")
    suspend fun beginHostingSession(privateRelay: Boolean = false, autoShareResourcePack: Boolean = false): Result<SessionResponse> =
        SessionsApi.create().onSuccess { session ->
            forgetRejoin()
            _currentSessionId.value = session.id
        }

    fun stopHosting() {
        HostSharedPack.disable()
        val session = _currentSessionId.value ?: return
        _currentSessionId.value = null
        SessionsRepository.close(session)
        if (Parties.ENABLED) PolyPlusClient.SCOPE.launch { PartyApi.closeSession(session) }
    }

    private fun join(invite: SessionInvite) {
        val address = invite.server
        if (address == null) {
            LOGGER.error("Invite {} has no server address", invite.id)
            return
        }
        joinedSessionId = invite.sessionId
        joinedAddress = address
        lastInvite = invite
        _rejoinSessionId.value = invite.sessionId
        LOGGER.info("Joining {} from an invite", address)
        val minecraft = Minecraft.getInstance()
        //? if = 1.8.9 {
        /*minecraft.execute {
            val host = address.substringBefore(':')
            val port = address.substringAfter(':', "").toIntOrNull() ?: 25565
            val screen = ConnectScreen(TitleScreen(), minecraft, host, port)
            minecraft.setCurrentServerEntry(ServerListEntry("Friend's world", address, false))
            minecraft.setScreen(screen)
        }
        *///?} else {
        minecraft.execute {
            ConnectScreen.startConnecting(
                TitleScreen(),
                minecraft,
                ServerAddress.parseString(address),
                ServerData("Friend's world", address, ServerData.Type.OTHER),
                false,
                null,
            )
        }
        //?}
    }
}
