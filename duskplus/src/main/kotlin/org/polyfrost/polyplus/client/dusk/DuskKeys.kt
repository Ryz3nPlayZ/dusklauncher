package org.polyfrost.polyplus.client.dusk

import org.polyfrost.oneconfig.internal.OneConfigConfig
import org.apache.logging.log4j.LogManager

/**
 * Dusk: right shift belongs to Dusk's settings key. OneConfig also defaults to right shift, and its
 * conflict detector then unbinds one of the two. Moving OneConfig off right shift before any key is
 * pressed avoids that.
 */
object DuskKeys {
    private const val GLFW_KEY_RIGHT_SHIFT = 344

    fun initialize() {
        val config = OneConfigConfig.INSTANCE ?: return
        val bind = OneConfigConfig.oneConfigKeybind ?: return
        val keys = bind.keyCodes ?: return
        if (keys.size != 1 || keys[0] != GLFW_KEY_RIGHT_SHIFT) return
        bind.keyCodes = IntArray(0)
        runCatching { config.save() }
            .onFailure { LogManager.getLogger("Dusk").warn("Dusk: could not save OneConfig after moving its key off right shift", it) }
    }
}
