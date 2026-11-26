// OneConfig's public UI API is still marked experimental
@file:OptIn(ExperimentalOneConfigUiApi::class)

package org.polyfrost.polyplus.client.gui

import org.polyfrost.polyplus.client.gui.screens.ShellModal
import org.polyfrost.polyplus.client.gui.screens.shellModalSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.minecraft.client.Minecraft
//? if = 1.8.9 {
/*import net.minecraft.client.gui.screens.Screen
*///?}
import org.polyfrost.oneconfig.api.ui.v1.ExperimentalOneConfigUiApi
import org.polyfrost.oneconfig.api.ui.v1.a11y.A11yRole
import org.polyfrost.oneconfig.api.ui.v1.a11y.Announce
import org.polyfrost.oneconfig.api.ui.v1.a11y.Motion
import org.polyfrost.oneconfig.api.ui.v1.a11y.notFocusable
import org.polyfrost.oneconfig.api.ui.v1.components.Divider
import org.polyfrost.oneconfig.api.ui.v1.components.EscapeCloses
import org.polyfrost.oneconfig.api.ui.v1.components.PlayerHead
import org.polyfrost.oneconfig.api.ui.v1.components.Text
import org.polyfrost.oneconfig.api.ui.v1.components.rememberHoverState
import org.polyfrost.oneconfig.api.ui.v1.components.selectableRow
import org.polyfrost.oneconfig.api.ui.v1.theme.OneConfigTheme
import org.polyfrost.polyplus.client.PolyPlusMainMenuConfig
import org.polyfrost.polyplus.client.gui.lobbyshell.ConsentStep
import org.polyfrost.polyplus.client.gui.lobbyshell.CustomizeKit
import org.polyfrost.polyplus.client.gui.lobbyshell.LobbyIcon
import org.polyfrost.polyplus.client.gui.lobbyshell.PanelHeader
import org.polyfrost.polyplus.client.gui.lobbyshell.PanelHint
import org.polyfrost.polyplus.client.gui.lobbyshell.PanelPageColumn
import org.polyfrost.polyplus.client.gui.lobbyshell.PanelPageSwitch
import org.polyfrost.polyplus.client.gui.lobbyshell.PanelSection
import org.polyfrost.polyplus.client.gui.lobbyshell.headShape
import org.polyfrost.polyplus.client.gui.lobbyshell.panel
import org.polyfrost.polyplus.client.gui.preview.PlayerPreview
import org.polyfrost.polyplus.client.gui.preview.PlayerPreviewSource
import org.polyfrost.polyplus.client.dusk.DuskLauncher
import org.polyfrost.polyplus.client.launcher.OneLauncherAccounts
import java.util.UUID

internal enum class AccountsPage(val title: String) {
    Home("Accounts"),
    OnlineServices("Online services"),
}

internal class AccountsPanelState(val actions: AccountActions) {
    var page by mutableStateOf(AccountsPage.Home)
        private set
    var forward by mutableStateOf(true)
        private set
    var consentVersion by mutableIntStateOf(0)
        private set
    var pendingId by mutableStateOf<UUID?>(null)
        private set

    fun open(target: AccountsPage) {
        if (target == page) return
        if (target != AccountsPage.Home && !actions.idle) return
        forward = target != AccountsPage.Home
        page = target
        actions.clearError()
    }

    fun back(): Boolean {
        if (page == AccountsPage.Home) return false
        open(AccountsPage.Home)
        return true
    }

    fun reset() {
        forward = false
        page = AccountsPage.Home
    }

    fun switchTo(account: OneLauncherAccounts.Account) {
        if (!actions.idle || account.active) return
        pendingId = account.id
        actions.switchTo(account)
    }

    fun consentGiven() {
        consentVersion++
        open(AccountsPage.Home)
    }

    internal fun sync() {
        if (actions.idle) pendingId = null
    }
}

@Composable
internal fun rememberAccountsPanelState(): AccountsPanelState {
    val actions = rememberAccountActions()
    return remember(actions) { AccountsPanelState(actions) }
}

@Composable
internal fun AccountsPanelContent(state: AccountsPanelState) {
    val actions = state.actions
    LaunchedEffect(actions.busy, actions.error) { state.sync() }
    PanelPageSwitch(state.page, state.forward) { page ->
        when (page) {
            AccountsPage.Home -> AccountsHome(state)
            AccountsPage.OnlineServices -> PanelPageColumn {
                ConsentStep("Friends and messages need online services.", Modifier.fillMaxWidth(), { state.back() }, state::consentGiven)
            }
        }
    }
}

@Composable
internal fun AccountSwitcherContent(actions: AccountActions, onClose: () -> Unit) {
    val state = remember(actions) { AccountsPanelState(actions) }
    LaunchedEffect(Unit) { actions.reload() }
    val close = {
        state.reset()
        onClose()
    }
    EscapeCloses { if (!state.back()) close() }
    CompositionLocalProvider(LocalDensity provides scaledDensity(ShellModal.UI_SCALE)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(SocialWindowScrim)
                .notFocusable()
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = close),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier
                    .shellModalSize()
                    .panel(CARD_RADIUS)
                    .notFocusable()
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
            ) {
                if (!PolyPlusMainMenuConfig.hideMainMenuAltManager) {
                    key(actions.accounts?.firstOrNull { it.active }?.id) {
                        PlayerPreview(
                            Modifier.fillMaxWidth(PREVIEW_SHARE).widthIn(max = PREVIEW_MAX_WIDTH).fillMaxHeight(),
                            source = PlayerPreviewSource.LocalLive,
                            initialYaw = 180f + 22.9f,
                            live = true,
                            modelScale = 0.35F,
                        )
                    }
                    Divider(vertical = true)
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Announce("Accounts")
                    val page = state.page
                    PanelHeader(page.title, close, if (page != AccountsPage.Home) ({ state.back() }) else null)
                    Box(Modifier.weight(1f).fillMaxWidth()) { AccountsPanelContent(state) }
                }
            }
        }
    }
}

@Composable
private fun AccountsHome(state: AccountsPanelState) {
    val actions = state.actions
    val showAccounts = !PolyPlusMainMenuConfig.hideMainMenuAltManager
    PanelPageColumn {
        if (showAccounts) {
            PanelSection("Minecraft accounts", divider = false) {
                val accounts = actions.accounts
                when {
                    accounts == null -> repeat(SKELETON_ROWS) { SkeletonRow() }
                    accounts.isEmpty() -> PanelHint("No saved accounts.")
                    else -> accounts.forEach { account -> key(account.id) { AccountRow(state, account) } }
                }
                if (state.page == AccountsPage.Home) actions.error?.let { DangerText(it) }
                PanelHint(if (DuskLauncher.available) "Add or remove accounts in the Dusk launcher." else "Start the game from the Dusk launcher to switch accounts.")
            }
        }
    }
}

@Composable
private fun AccountRow(state: AccountsPanelState, account: OneLauncherAccounts.Account) {
    val actions = state.actions
    val theme = OneConfigTheme.current
    val hover = rememberHoverState()
    val busy = state.pendingId == account.id
    val idle = actions.idle
    val shape = OneConfigTheme.shape(CustomizeKit.RowRadius)
    val subtitle = if (busy) actions.busy else if (account.active) "In use" else null
    Row(
        Modifier
            .fillMaxWidth()
            .height(CustomizeKit.RowHeight)
            .graphicsLayer { alpha = if (idle || busy) 1f else theme.disabledAlpha }
            .hoverable(hover.interactionSource)
            .selectableRow(
                false,
                { state.switchTo(account) },
                account.username + if (account.active) ", active" else "",
                shape,
                idle && !account.active,
                role = A11yRole.Button,
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PlayerHead(account.id, HEAD_SIZE, Modifier, shape = headShape(HEAD_SIZE), contentDescription = null)
        Column(Modifier.weight(1f)) {
            Text(
                account.username,
                color = theme.textColor,
                style = theme.typography.body.copy(fontWeight = if (account.active) FontWeight.Medium else FontWeight.Normal),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
            )
            subtitle?.let { Text(
                it,
                color = theme.textColorSecondary,
                style = theme.typography.small,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
            ) }
        }
        when {
            busy -> Spinner(OneConfigTheme.accent)
            account.active -> LobbyIcon("check", OneConfigTheme.accent, 18.dp)
        }
    }
}

@Composable
private fun DangerText(text: String, secondary: Boolean = false) {
    Text(
        text,
        Modifier.fillMaxWidth(),
        color = if (secondary) SocialDangerText.copy(alpha = 0.8f) else SocialDangerText,
        style = OneConfigTheme.current.typography.small,
    )
}

@Composable
private fun SkeletonRow() {
    val pulse by Motion.animateInfiniteFloat(
        initial = SKELETON_LOW,
        target = SKELETON_HIGH,
        spec = infiniteRepeatable(tween(SKELETON_MS), RepeatMode.Reverse),
    )
    val fill = OneConfigTheme.current.textColor.copy(alpha = pulse)
    val bar = OneConfigTheme.shape(4.dp)
    Row(
        Modifier.fillMaxWidth().height(CustomizeKit.RowHeight).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(HEAD_SIZE).clip(headShape(HEAD_SIZE)).background(fill))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.width(120.dp).height(10.dp).clip(bar).background(fill))
            Box(Modifier.width(70.dp).height(8.dp).clip(bar).background(fill))
        }
    }
}

@Composable
private fun Spinner(color: Color, size: Dp = 16.dp) {
    val angle by Motion.animateInfiniteFloat(
        initial = 0f,
        target = 360f,
        spec = infiniteRepeatable(tween(SPIN_MS, easing = LinearEasing)),
    )
    LobbyIcon("loading-02", color, size, Modifier.rotate(angle))
}

private val HEAD_SIZE = 28.dp
private val PREVIEW_MAX_WIDTH = 320.dp
private val CARD_RADIUS = 20.dp
private const val PREVIEW_SHARE = 0.38f
private const val SKELETON_ROWS = 2
private const val SKELETON_MS = 700
private const val SKELETON_LOW = 0.06f
private const val SKELETON_HIGH = 0.14f
private const val SPIN_MS = 800
