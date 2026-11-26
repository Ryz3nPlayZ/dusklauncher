package org.polyfrost.polyplus.client.dusk

import kotlinx.coroutines.delay
import net.fabricmc.loader.api.FabricLoader
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.appender.AbstractAppender
import java.util.regex.Pattern

/**
 * Where a hosted world can be reached. e4mc tunnels any world opened to LAN and says the address it
 * was given in the log; that line is its only output, so it's read from there (as the launcher does).
 */
object DuskHosting {
    const val NO_ADDRESS = "Your world has no address friends can join yet. Start the game from the Dusk launcher and host again."

    private const val WAIT_MS = 20_000L
    private const val POLL_MS = 250L
    private val DOMAIN = Pattern.compile("Domain assigned:\\s*([A-Za-z0-9.-]+)")

    @Volatile
    private var address: String? = null

    val tunnelInstalled: Boolean
        get() = FabricLoader.getInstance().isModLoaded("e4mc")

    /** Before any world opens: the line is logged once. */
    fun initialize() {
        if (!tunnelInstalled) return
        runCatching {
            val root = LogManager.getRootLogger() as Logger
            val appender = object : AbstractAppender("dusk-hosting", null, null) {
                override fun append(event: LogEvent) {
                    val line = event.message?.formattedMessage ?: return
                    val match = DOMAIN.matcher(line)
                    if (match.find()) address = match.group(1)
                }
            }
            appender.start()
            root.addAppender(appender)
        }
    }

    /** The world closed, and its address with it. */
    fun closed() {
        address = null
    }

    suspend fun awaitAddress(): String? {
        if (!tunnelInstalled) return null
        var waited = 0L
        while (address == null && waited < WAIT_MS) {
            delay(POLL_MS)
            waited += POLL_MS
        }
        return address
    }
}
