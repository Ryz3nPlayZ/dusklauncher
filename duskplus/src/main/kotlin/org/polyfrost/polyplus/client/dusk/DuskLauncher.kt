package org.polyfrost.polyplus.client.dusk

import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/** The Dusk launcher's saved accounts, through DuskClient's `LauncherLink` and `AccountSwitcher`. */
object DuskLauncher {
    data class Account(val uuid: String, val username: String, val active: Boolean)

    private const val SWITCH_TIMEOUT_SECONDS = 70L

    private val link = find("dev.dusk.client.account.LauncherLink")
    private val switcher = find("dev.dusk.client.account.AccountSwitcher")

    private fun find(name: String): Class<*>? = runCatching { Class.forName(name) }.getOrNull()

    /** Whether the launcher started this game and can hand over accounts. */
    val available: Boolean
        get() = switcher != null && runCatching { link?.getMethod("available")?.invoke(null) == true }.getOrDefault(false)

    /** Blocking; not for the main thread. */
    fun accounts(): List<Account> {
        val rows = try {
            link?.getMethod("accounts")?.invoke(null) as? List<*>
        } catch (e: InvocationTargetException) {
            throw DuskException(e.targetException.message ?: "Couldn't reach the launcher", e.targetException)
        }
        return rows.orEmpty().filterNotNull().map { row ->
            fun get(name: String): Any? = row.javaClass.getMethod(name).invoke(row)
            Account(get("uuid") as String, get("username") as String, get("active") == true)
        }
    }

    /** Signs the game in as [uuid]. Blocking; not for the main thread. Throws [IOException] with the reason. */
    fun switchTo(uuid: String) {
        val switcher = switcher ?: throw DuskException("Dusk Client isn't installed")
        if (switcher.getMethod("busy").invoke(null) == true) throw DuskException("Already switching accounts")
        val done = CompletableFuture<String?>()
        switcher.getMethod("switchTo", String::class.java, Consumer::class.java)
            .invoke(null, uuid, Consumer<String?> { done.complete(it) })
        val error = runCatching { done.get(SWITCH_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            .getOrElse { throw DuskException("The launcher took too long to answer", it) }
        if (error != null) throw DuskException(error)
    }
}
