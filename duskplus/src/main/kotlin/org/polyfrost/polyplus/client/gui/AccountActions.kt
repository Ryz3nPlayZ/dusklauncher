package org.polyfrost.polyplus.client.gui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.polyfrost.polyplus.client.launcher.OneLauncherAccounts
import org.polyfrost.polyplus.client.utils.runSuspendCatching

internal class AccountActions(private val scope: CoroutineScope) {
    var accounts by mutableStateOf<List<OneLauncherAccounts.Account>?>(null)
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val idle: Boolean get() = busy == null

    suspend fun reload() {
        accounts = withContext(Dispatchers.IO) { OneLauncherAccounts.list() }
    }

    fun clearError() {
        error = null
    }

    fun switchTo(account: OneLauncherAccounts.Account) {
        if (account.active || busy != null) return
        scope.launch {
            busy = "Switching…"
            clearError()
            try {
                runSuspendCatching { withContext(Dispatchers.IO) { OneLauncherAccounts.switchTo(account.id) } }
                    .onFailure { error = it.message ?: "Couldn't switch to that account" }
                reload()
            } finally {
                busy = null
            }
        }
    }
}

@Composable
internal fun rememberAccountActions(): AccountActions {
    val scope = rememberCoroutineScope()
    return remember(scope) { AccountActions(scope) }
}
