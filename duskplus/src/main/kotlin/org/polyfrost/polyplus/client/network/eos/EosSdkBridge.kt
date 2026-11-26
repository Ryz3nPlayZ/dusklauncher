package org.polyfrost.polyplus.client.network.eos

import java.nio.ByteBuffer

/**
 * Dusk: the Epic Online Services transport is not part of this fork. The type stays so the code written against it
 * still compiles; nothing creates one, and `P2PSessionManager.activeBridge` is always null.
 */
class EosSdkBridge private constructor() {
    val localUser: EosProductUserId? get() = null
    val isLoggedIn: Boolean get() = false
    val rtcEnabled: Boolean get() = false

    fun outboundQueueBytes(): Long = 0L

    @Suppress("UNUSED_PARAMETER")
    fun sendPacket(
        socket: EosP2PSocketId,
        remote: EosProductUserId,
        data: ByteBuffer,
        reliability: PacketReliability = PacketReliability.ReliableOrdered,
        onFailure: (reason: String) -> Unit = {},
    ) = onFailure("no peer-to-peer transport")

    @Suppress("UNUSED_PARAMETER")
    fun addConnectionRequestHandler(socket: EosP2PSocketId, handler: (remote: EosProductUserId) -> Unit) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun addSessionRequestHandler(session: EosP2PSocketId, handler: (socket: EosP2PSocketId, remote: EosProductUserId) -> Unit) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun removeSessionRequestHandler(session: EosP2PSocketId) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun acceptConnection(socket: EosP2PSocketId, remote: EosProductUserId) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun closeConnection(socket: EosP2PSocketId, remote: EosProductUserId?) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun addConnectionStateHandler(
        socket: EosP2PSocketId,
        handler: (event: ConnectionStateEvent) -> Unit,
    ): EosNotificationHandle = EosNotificationHandle(0)

    @Suppress("UNUSED_PARAMETER")
    fun removeNotificationHandler(handle: EosNotificationHandle) = Unit

    data class Received(val socket: EosP2PSocketId, val remote: EosProductUserId, val data: ByteBuffer)

    sealed interface ConnectionStateEvent {
        val remote: EosProductUserId

        data class Established(override val remote: EosProductUserId, val direct: Boolean) : ConnectionStateEvent
        data class Interrupted(override val remote: EosProductUserId) : ConnectionStateEvent
        data class Closed(override val remote: EosProductUserId, val reason: String) : ConnectionStateEvent
    }

    enum class PacketReliability { UnreliableUnordered, ReliableUnordered, ReliableOrdered }
}
