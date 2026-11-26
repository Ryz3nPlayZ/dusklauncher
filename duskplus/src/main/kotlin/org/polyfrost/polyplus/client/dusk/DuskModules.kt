package org.polyfrost.polyplus.client.dusk

//? if > 1.8.9 {
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
//?}
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import org.apache.logging.log4j.LogManager
import org.polyfrost.oneconfig.api.config.v1.ConfigManager
import org.polyfrost.oneconfig.api.config.v1.Properties
import org.polyfrost.oneconfig.api.config.v1.Property
import org.polyfrost.oneconfig.api.config.v1.Property.Display
import org.polyfrost.oneconfig.api.config.v1.Tree
import org.polyfrost.oneconfig.api.config.v1.ValueFormatter
import org.polyfrost.oneconfig.api.config.v1.Visualizer
import org.polyfrost.oneconfig.api.config.v1.dsl.category
import org.polyfrost.oneconfig.api.config.v1.dsl.noCache
import org.polyfrost.oneconfig.api.config.v1.dsl.saveFunction
import org.polyfrost.oneconfig.api.config.v1.dsl.subcategory
import org.polyfrost.oneconfig.api.config.v1.dsl.visualizer
import org.polyfrost.oneconfig.api.ui.v1.ExperimentalOneConfigUiApi
import org.polyfrost.oneconfig.api.ui.v1.keybind.KeyModifiers
import org.polyfrost.oneconfig.api.ui.v1.keybind.KeybindMetadata
import org.polyfrost.oneconfig.api.ui.v1.keybind.OneConfigKeybind
import org.polyfrost.polyplus.client.utils.ClientPlatform
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

/**
 * Dusk: DuskClient's modules as one OneConfig page, so the Dusk+ settings screen and OneConfig both
 * show them next to everything else. Each option reads and writes the module's own setting, and
 * saving goes to DuskClient's config file: OneConfig keeps no copy. Reached by name, like
 * [DuskBridge], so neither mod needs the other to build.
 *
 * Key bindings, toggle keys and the crosshair pixel grid are edited here too.
 */
@OptIn(ExperimentalOneConfigUiApi::class)
object DuskModules {
    const val TREE_ID = "duskclient"

    private val logger = LogManager.getLogger("Dusk/Modules")
    private const val PKG = "dev.dusk.client.module.setting."

    private fun type(name: String): Class<*>? = runCatching { Class.forName(PKG + name) }.getOrNull()
    private val boolType = type("BoolSetting")
    private val intType = type("IntSetting")
    private val choiceType = type("ChoiceSetting")
    private val colorType = type("ColorSetting")
    private val textType = type("TextSetting")
    private val actionType = type("ActionSetting")
    private val keyType = type("KeySetting")
    private val pixelType = type("PixelGridSetting")

    @Volatile
    private var registeredCount = -1

    /** The module manager, once DuskClient has started; null without DuskClient. */
    private fun manager(): Any? = runCatching {
        Class.forName("dev.dusk.client.DuskClient").getMethod("modules").invoke(null)
    }.getOrNull()

    private fun modules(manager: Any): List<Any> = (manager.call("all") as? List<*>)?.filterNotNull().orEmpty()

    /**
     * Registers the page if DuskClient has loaded its modules, or rebuilds it when the module list
     * changed. Cheap to call often.
     */
    fun ensureRegistered() {
        val manager = manager() ?: return
        val modules = modules(manager)
        if (modules.isEmpty() || modules.size == registeredCount) return
        registeredCount = modules.size
        runCatching {
            val active = ConfigManager.active()
            if (active.exists(TREE_ID)) active.unregister(TREE_ID)
            active.register(tree(manager, modules))
        }.onFailure { logger.error("Dusk: could not add Dusk Client's settings to OneConfig", it) }
    }

    /** Writes DuskClient's config now. */
    fun saveNow() {
        pending?.cancel(false)
        val manager = manager() ?: return
        runCatching { manager.call("saveConfig") }.onFailure { logger.warn("Dusk: could not save Dusk Client's settings", it) }
    }

    private val saver = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "Dusk config save").apply { isDaemon = true }
    }

    @Volatile
    private var pending: java.util.concurrent.ScheduledFuture<*>? = null

    /** Saves shortly after the last change, so dragging a slider doesn't write the file every frame. */
    private fun save() {
        pending?.cancel(false)
        pending = saver.schedule({ saveNow() }, 750, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun tree(manager: Any, modules: List<Any>): Tree {
        val tree = Tree.tree()
        tree.id = TREE_ID
        tree.title = "Dusk Client"
        tree.noCache = true
        tree.saveFunction = Runnable { saveNow() }
        val used = HashSet<String>()
        // the HUD layout is dragged around on its own screen, and Dusk's menu stays one click away
        for ((label, button, screen) in listOf(
            Triple("HUD layout", "Edit HUD", "dev.dusk.client.gui.HudEditorScreen"),
            Triple("Dusk menu", "Open Dusk menu", "dev.dusk.client.gui.DuskSettingsScreen"),
        )) {
            val prop = Properties.dummy(id = unique(used, "open/$button"), name = label).apply {
                visualizer = Visualizer.ButtonVisualizer::class.java
                addMetadata("text", button)
                addMetadata("runnable", Runnable { openScreen(screen) })
            }
            prop.category = "General"
            prop.subcategory = "Dusk Client"
            tree.put(prop)
        }
        // the categories in the order DuskClient's own screen lists them
        val order = listOf("HUD", "Movement", "Render", "Utility")
        val sorted = modules.withIndex().sortedWith(compareBy({ order.indexOf(category(it.value)).let { i -> if (i < 0) order.size else i } }, { it.index }))
        for ((_, module) in sorted) {
            val id = module.call("id") as? String ?: continue
            val name = module.call("name") as? String ?: id
            val props = ArrayList<Property<*>>()
            props += Properties.functional<Boolean>(
                { module.call("enabled") == true },
                { on -> if (on != (module.call("enabled") == true)) { module.call("setEnabled", on); save() } },
                id = unique(used, "$id/enabled"), type = Boolean::class.javaPrimitiveType,
                name = "Enabled", description = module.call("description") as? String,
            ).apply { visualizer = Visualizer.SwitchVisualizer::class.java }
            // key rows need modern input classes; on 1.8.9 Dusk Client is a Forge mod anyway
            //? if > 1.8.9 {
            module.call("toggleKey")?.let { toggle ->
                runCatching { toggleKeyProperty(toggle, unique(used, "$id/togglekey")) }
                    .onFailure { logger.debug("Dusk: skipped {}/toggleKey", id, it) }
                    .getOrNull()?.let(props::add)
            }
            //?}
            for (setting in (module.call("settings") as? List<*>).orEmpty()) {
                setting ?: continue
                val settingId = setting.call("id") as? String ?: continue
                runCatching { property(module, setting, unique(used, "$id/$settingId")) }
                    .onFailure { logger.debug("Dusk: skipped {}/{}", id, settingId, it) }
                    .getOrNull()?.let(props::add)
            }
            linkDisplays(props)
            val label = category(module)
            for (prop in props) {
                prop.category = label
                prop.subcategory = name
                tree.put(prop)
            }
        }
        return tree
    }

    /** Opens one of DuskClient's screens over the current one; closing it comes back here. */
    private fun openScreen(name: String) = ClientPlatform.runOnMain {
        runCatching {
            val parent = ClientPlatform.currentScreen()
            val screen = Class.forName(name).getConstructor(Screen::class.java).newInstance(parent) as Screen
            ClientPlatform.setScreen(screen)
        }.onFailure { logger.warn("Dusk: could not open {}", name, it) }
    }

    private fun category(module: Any): String =
        runCatching { module.call("category")!!.let { it.javaClass.getField("label").get(it) as String } }.getOrDefault("Utility")

    private fun property(module: Any, setting: Any, id: String): Property<*>? {
        val name = setting.call("name") as? String ?: return null
        val prop: Property<*> = when {
            boolType?.isInstance(setting) == true -> Properties.functional<Boolean>(
                { setting.call("get") == true }, { setting.call("set", it); save() },
                id = id, type = Boolean::class.javaPrimitiveType, name = name,
            ).apply { visualizer = Visualizer.SwitchVisualizer::class.java }

            intType?.isInstance(setting) == true -> {
                val decimals = runCatching {
                    intType!!.getDeclaredField("decimals").apply { isAccessible = true }.getInt(setting)
                }.getOrDefault(0)
                val scale = 10.0.pow(decimals).toFloat()
                val suffix = (setting.call("suffix") as? String).orEmpty()
                @Suppress("UNCHECKED_CAST")
                val format = runCatching {
                    intType!!.getDeclaredField("format").apply { isAccessible = true }.get(setting) as java.util.function.IntFunction<String>?
                }.getOrNull()
                // DuskClient prints its own values ("60 FPS", "1.25x", "Off"), for whatever the slider is on
                val formatter = ValueFormatter { n ->
                    val raw = Math.round(n.toDouble() * scale).toInt()
                    format?.apply(raw)
                        ?: if (decimals == 0) "$raw$suffix"
                        else java.math.BigDecimal.valueOf(raw.toLong(), decimals).stripTrailingZeros().toPlainString() + suffix
                }
                Properties.functional<Float>(
                    { (setting.call("asFloat") as? Float) ?: 0f },
                    { setting.call("setFloat", it.toDouble()); save() },
                    id = id, type = Float::class.javaPrimitiveType, name = name,
                ).apply {
                    visualizer = Visualizer.SliderVisualizer::class.java
                    addMetadata("min", (setting.call("min") as Int) / scale)
                    addMetadata("max", (setting.call("max") as Int) / scale)
                    addMetadata("step", (setting.call("step") as Int) / scale)
                    addMetadata("formatter", formatter)
                }
            }

            choiceType?.isInstance(setting) == true -> {
                val options = (setting.call("options") as? List<*>).orEmpty().map { it.toString() }
                Properties.functional<Int>(
                    { (setting.call("index") as? Int) ?: 0 },
                    { i -> options.getOrNull(i)?.let { setting.call("set", it); save() } },
                    id = id, type = Int::class.javaPrimitiveType, name = name,
                ).apply {
                    visualizer = Visualizer.DropdownVisualizer::class.java
                    addMetadata("options", options.map { (setting.call("label", it) as? String) ?: it })
                }
            }

            colorType?.isInstance(setting) == true -> Properties.functional<Int>(
                { (setting.call("argb") as? Int) ?: -1 }, { setting.call("set", it); save() },
                id = id, type = Int::class.javaPrimitiveType, name = name,
            ).apply {
                visualizer = Visualizer.ColorVisualizer::class.java
                if (setting.call("isOpaque") == true) addMetadata("noAlpha", Unit)
            }

            textType?.isInstance(setting) == true -> Properties.functional<String>(
                { (setting.call("get") as? String).orEmpty() }, { setting.call("set", it); save() },
                id = id, type = String::class.java, name = name,
            ).apply { visualizer = Visualizer.TextVisualizer::class.java }

            actionType?.isInstance(setting) == true -> Properties.dummy(id = id, name = name).apply {
                visualizer = Visualizer.ButtonVisualizer::class.java
                addMetadata("text", (setting.call("button") as? String) ?: name)
                addMetadata("runnable", Runnable { runCatching { setting.call("run") } })
            }

            //? if > 1.8.9 {
            keyType?.isInstance(setting) == true -> {
                val mapping = setting.call("mapping") as KeyMapping
                keybindProperty(
                    id, name, mapping.defaultKey,
                    { runCatching { InputConstants.getKey(mapping.saveString()) }.getOrDefault(InputConstants.UNKNOWN) },
                    { key ->
                        mapping.setKey(key)
                        KeyMapping.resetMapping()
                        Minecraft.getInstance().options.save()
                    },
                )
            }
            //?}

            pixelType?.isInstance(setting) == true -> pixelGridProperty(module, setting, id, name)

            else -> return null
        }
        prop.addDisplayCondition { if (runCatching { setting.call("visible") != false }.getOrDefault(true)) Display.SHOWN else Display.HIDDEN }
        return prop
    }

    /** The crosshair grid, painted on the page; the module keeps its preset and colour rows. */
    @Suppress("UNCHECKED_CAST")
    private fun pixelGridProperty(module: Any, setting: Any, id: String, name: String): Property<*> {
        val grid = DuskPixelGrid(
            setting.call("width") as Int, setting.call("height") as Int,
            grid = { setting.call("get") as Array<IntArray> },
            replace = { setting.call("setGrid", it) },
            paintColor = { runCatching { module.call("pixelColor")?.call("argb") as? Int }.getOrNull() ?: -1 },
            edited = { runCatching { module.call("markCustom") }; save() },
        )
        return Properties.dummy(id = id, name = name).apply {
            addMetadata("visualizer", grid)
            addMetadata("fullWidth", true)
        }
    }

    //? if > 1.8.9 {
    /** The key that switches a module on and off: keyboard only, kept in DuskClient's module config. */
    private fun toggleKeyProperty(toggle: Any, id: String): Property<*> = keybindProperty(
        id, "Toggle key", InputConstants.UNKNOWN,
        { ((toggle.call("get") as? Int) ?: InputConstants.UNKNOWN.value).let(KEYBOARD::getOrCreate) },
        { key ->
            if (key.type != InputConstants.Type.MOUSE) {
                toggle.call("set", key.value)
                save()
            }
        },
    )

    /**
     * A key-binding row. It only edits the key: [KeybindMetadata.NO_MIRROR] keeps OneConfig from
     * registering it as a live bind or adding a second row to Controls, since DuskClient listens for
     * the key itself.
     */
    private fun keybindProperty(
        id: String, name: String, default: InputConstants.Key,
        read: () -> InputConstants.Key, write: (InputConstants.Key) -> Unit,
    ): Property<*> = Properties.functional<OneConfigKeybind>(
        { bindOf(read()) },
        { bind -> keyOf(bind).let { if (it != read()) write(it) } },
        id = id, type = OneConfigKeybind::class.java, name = name,
    ).apply {
        visualizer = Visualizer.KeybindVisualizer::class.java
        addMetadata("singleKey", true)
        addMetadata("default", bindOf(default))
        addMetadata(KeybindMetadata.NO_MIRROR, true)
    }

    // 26.3 moved to SDL and renamed the keyboard key type
    //? if >= 26.3 {
    private val KEYBOARD = InputConstants.Type.KEYBOARD
    //?} else
    //private val KEYBOARD = InputConstants.Type.KEYSYM

    private fun bindOf(key: InputConstants.Key): OneConfigKeybind = when {
        key.value == InputConstants.UNKNOWN.value -> OneConfigKeybind(null, null, KeyModifiers.NONE, 0L) { false }
        key.type == InputConstants.Type.MOUSE -> OneConfigKeybind(null, intArrayOf(key.value), KeyModifiers.NONE, 0L) { false }
        else -> OneConfigKeybind(intArrayOf(key.value), null, KeyModifiers.NONE, 0L) { false }
    }

    private fun keyOf(bind: OneConfigKeybind?): InputConstants.Key = when {
        bind == null || !bind.isBound -> InputConstants.UNKNOWN
        bind.isMousePrimary -> InputConstants.Type.MOUSE.getOrCreate(bind.boundCode)
        else -> KEYBOARD.getOrCreate(bind.boundCode)
    }
    //?}

    /** A setting shown only while another has some value: re-check every option after any change. */
    @Suppress("UNCHECKED_CAST")
    private fun linkDisplays(props: List<Property<*>>) {
        for (prop in props) (prop as Property<Any?>).addCallback { props.forEach(Property<*>::revaluateDisplay); false }
    }

    private fun unique(used: MutableSet<String>, base: String): String {
        val clean = base.lowercase().replace(Regex("[^a-z0-9/_]+"), "_")
        var id = clean
        var n = 2
        while (!used.add(id)) id = "${clean}_${n++}"
        return id
    }

    private val methods = ConcurrentHashMap<String, Method>()

    /** Calls a public method of a DuskClient object by name and argument count. */
    private fun Any.call(name: String, vararg args: Any?): Any? {
        val cls = javaClass
        val method = methods.getOrPut("${cls.name}#$name/${args.size}") {
            cls.methods.filter { it.name == name && it.parameterCount == args.size }
                .let { found -> found.firstOrNull { !it.isBridge } ?: found.firstOrNull() }
                ?: throw NoSuchMethodException("${cls.name}.$name")
        }.also {
            // a module class that isn't public still overrides public methods: let reflection call them
            if (!java.lang.reflect.Modifier.isPublic(it.declaringClass.modifiers)) runCatching { it.isAccessible = true }
        }
        return method.invoke(this, *args)
    }
}
