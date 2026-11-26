package org.polyfrost.polyplus.client.dusk

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.apache.logging.log4j.LogManager
import org.polyfrost.compose.text.Font
import org.polyfrost.oneconfig.api.event.v1.eventHandler
import org.polyfrost.oneconfig.api.event.v1.events.TickEvent
import org.polyfrost.oneconfig.api.ui.v1.ExperimentalOneConfigUiApi
import org.polyfrost.oneconfig.api.ui.v1.theme.OneConfigTheme
import org.polyfrost.oneconfig.internal.ThemeConfig
import org.polyfrost.oneconfig.internal.ui.themes.PaletteAccentOption
import org.polyfrost.oneconfig.internal.ui.themes.PaletteInfo
import org.polyfrost.oneconfig.internal.ui.themes.ThemeRegistry
import org.polyfrost.oneconfig.internal.ui.themes.UIBranding
import org.polyfrost.oneconfig.internal.ui.themes.UITheme
import org.polyfrost.oneconfig.internal.ui.themes.UITypography
import org.polyfrost.oneconfig.internal.ui.themes.withPalette
import org.polyfrost.polyplus.client.PolyPlusConfig

/**
 * Dusk: the launcher's graphite look as a OneConfig theme, in dark and light, and the default one.
 * Colours come from the launcher's design tokens (`launcher/src/design/tokens.css`); text is Plus
 * Jakarta Sans like the launcher. Picked like any other theme, and grouped as "Dusk" with its own
 * accent choices.
 */
@OptIn(ExperimentalOneConfigUiApi::class)
object DuskTheme {
    const val DARK_NAME = "Dusk Dark"
    const val LIGHT_NAME = "Dusk Light"

    // OneConfig falls back to this one when the saved theme isn't registered yet
    private const val ONECONFIG_FALLBACK = "Polyfrosted Dark"

    private const val FONT = "assets/polyplus/font/dusk/"
    private const val LOGO = "assets/polyplus/brand/oneclient.svg"

    private val logger = LogManager.getLogger("Dusk/Theme")

    // the launcher ships 500 and 700 only: lighter weights use 500, heavier ones 700
    private val typography = UITypography(
        family = FontFamily(
            Font(FONT + "ui.ttf", FontWeight.Thin),
            Font(FONT + "ui.ttf", FontWeight.ExtraLight),
            Font(FONT + "ui.ttf", FontWeight.Light),
            Font(FONT + "ui.ttf", FontWeight.Normal),
            Font(FONT + "ui.ttf", FontWeight.Medium),
            Font(FONT + "ui_bold.ttf", FontWeight.SemiBold),
            Font(FONT + "ui_bold.ttf", FontWeight.Bold),
            Font(FONT + "ui_bold.ttf", FontWeight.ExtraBold),
            Font(FONT + "ui_bold.ttf", FontWeight.Black),
        ),
    )

    // controls are pills, cards and popovers rounded, like the launcher
    private val pill = RoundedCornerShape(50)

    val Dark: UITheme = UITheme(
        "dusk-dark", DARK_NAME,

        Color(0xE00B0B0D), // page: --bg, a little see-through for the blur behind
        Color(0xD6131316), // sidebar: --bg-2
        Color(0xCC232327), // chip: --raised
        Color(0xC718181B), // mod card: --surface
        Color(0xFF232327), // component: --raised
        Color(0xFF18181B), // popup: --surface

        Color(0x24FFFFFF), // border: --line-2
        Color(0xFFF4F4F5), // text
        Color(0xFFB4B4BB), // secondary text: --text-2
        Color(0xFF111113), // text on the accent: --acc-ink

        Color(0xFF000000),
        // the knob sits on the accent when a switch is on: dark on Dusk's white, like the launcher's switch
        Color(0xFF111113),
        Color(0xFFF5C451), // favourites: --warn

        true,

        RoundedCornerShape(20.dp),
        pill,
        RoundedCornerShape(14.dp),
        RoundedCornerShape(6.dp),
        pill,
        RoundedCornerShape(14.dp),
        CircleShape,

        UIBranding(LOGO),
        typography,
    ).withControlTrackColor(Color(0xFF45454C))
        .withShapes(RoundedCornerShape(10.dp), pill)
        .withoutGlow()
        .withPalette(
            PaletteInfo(
                "dusk", "Dusk",
                listOf(
                    PaletteAccentOption("White", Color(0xFFF4F4F5)),
                    PaletteAccentOption("Gold", Color(0xFFFFC600)),
                    PaletteAccentOption("Moss", Color(0xFF5FD38A)),
                    PaletteAccentOption("Sky", Color(0xFF7CB4FF)),
                    PaletteAccentOption("Violet", Color(0xFFA98BFF)),
                    PaletteAccentOption("Ember", Color(0xFFFF6B5E)),
                ),
                0,
            ),
        )

    val Light: UITheme = UITheme(
        "dusk-light", LIGHT_NAME,

        Color(0xE6F6F6F7),
        Color(0xDBEDEDEF),
        Color(0xCCE4E4E7),
        Color(0xD9FFFFFF),
        Color(0xFFE9E9EC),
        Color(0xFFFFFFFF),

        Color(0x1F000000),
        Color(0xFF111113),
        Color(0xFF5B5B63),
        Color(0xFFFFFFFF),

        Color(0xFF18181B),
        Color(0xFFFFFFFF),
        Color(0xFFC98A00),

        true,

        RoundedCornerShape(20.dp),
        pill,
        RoundedCornerShape(14.dp),
        RoundedCornerShape(6.dp),
        pill,
        RoundedCornerShape(14.dp),
        CircleShape,

        UIBranding(LOGO),
        typography,
    ).withControlTrackColor(Color(0xFFD4D4D8))
        .withShapes(RoundedCornerShape(10.dp), pill)
        .withoutGlow()
        .withPalette(
            PaletteInfo(
                "dusk", "Dusk",
                listOf(
                    PaletteAccentOption("Graphite", Color(0xFF18181B)),
                    PaletteAccentOption("Gold", Color(0xFFB7791F)),
                    PaletteAccentOption("Moss", Color(0xFF1F8A4C)),
                    PaletteAccentOption("Sky", Color(0xFF2563EB)),
                    PaletteAccentOption("Violet", Color(0xFF6D4AFF)),
                    PaletteAccentOption("Ember", Color(0xFFD93A2B)),
                ),
                0,
            ),
        )

    val all: List<UITheme> get() = listOf(Dark, Light)

    private fun byName(name: String?): UITheme? = all.firstOrNull { it.name == name }

    /** Whether a Dusk theme is the one in use; cheap enough to ask every frame. */
    val isActive: Boolean
        get() = OneConfigTheme.active?.name.let { it == DARK_NAME || it == LIGHT_NAME }

    @Volatile
    private var registered = false

    private var lastSeen: String? = null

    /**
     * Adds both themes to OneConfig and puts the saved one back. OneConfig may have read its theme
     * config before this ran, found no "Dusk Dark" and fallen back to Polyfrosted Dark, so the
     * choice is also kept in PolyPlus's own config. The first start makes Dusk the theme.
     */
    fun register() {
        if (registered) return
        registered = true
        all.forEach(ThemeRegistry::register)

        val saved = ThemeConfig.activeTheme
        val remembered = PolyPlusConfig.duskTheme
        val target = when {
            byName(saved) != null -> saved
            byName(remembered) != null && saved == ONECONFIG_FALLBACK -> remembered
            !PolyPlusConfig.duskThemeDefaulted -> if (PolyPlusConfig.onboardingLightTheme) LIGHT_NAME else DARK_NAME
            else -> null
        }
        if (!PolyPlusConfig.duskThemeDefaulted) {
            PolyPlusConfig.duskThemeDefaulted = true
            save()
        }
        byName(target)?.let { theme ->
            if (OneConfigTheme.active?.name != theme.name) {
                runCatching { OneConfigTheme.activate(theme) }
                    .onFailure { logger.warn("Dusk: could not switch to {}", theme.name, it) }
            }
        }

        // keep PolyPlus's copy of the choice current, whichever screen changed it
        lastSeen = ThemeConfig.activeTheme
        remember(lastSeen)
        eventHandler { _: TickEvent.End ->
            val now = ThemeConfig.activeTheme
            if (now != lastSeen) {
                lastSeen = now
                remember(now)
            }
        }
    }

    private fun remember(name: String?) {
        val value = if (byName(name) != null) name!! else ""
        if (PolyPlusConfig.duskTheme == value) return
        PolyPlusConfig.duskTheme = value
        save()
    }

    private fun save() {
        runCatching { PolyPlusConfig.save() }.onFailure { logger.warn("Dusk: could not save the theme choice", it) }
    }
}
