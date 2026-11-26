package org.polyfrost.polyplus.client.launcher

import org.polyfrost.polyplus.client.PolyPlusClient
import org.polyfrost.polyplus.client.dusk.DuskLauncher
import java.util.UUID

/** The accounts saved in the Dusk launcher. Adding and removing them is the launcher's job. */
object OneLauncherAccounts {
    data class Account(val id: UUID, val username: String, val active: Boolean)

    fun list(): List<Account> {
        val active = SessionAccounts.current()
        val saved = if (DuskLauncher.available) runCatching { DuskLauncher.accounts() }.getOrDefault(emptyList()) else emptyList()
        val rows = saved.mapNotNull { row -> parseUuid(row.uuid)?.let { Account(it, row.username, it == active?.id) } }
        return if (active == null || rows.any { it.id == active.id }) rows else listOf(active) + rows
    }

    /** Throws with a reason fit for the player. */
    fun switchTo(id: UUID, refreshClient: Boolean = true) {
        DuskLauncher.switchTo(id.toString())
        AccountSwitch.onSwitched(id)
        if (refreshClient) PolyPlusClient.refresh()
    }

    private fun parseUuid(value: String): UUID? = runCatching {
        UUID.fromString(value.replace("-", "").replaceFirst(Regex("(.{8})(.{4})(.{4})(.{4})(.{12})"), "$1-$2-$3-$4-$5"))
    }.getOrNull()
}
