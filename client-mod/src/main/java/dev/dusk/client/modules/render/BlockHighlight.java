package dev.dusk.client.modules.render;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.ActionSetting;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.ColorSetting;
import dev.dusk.client.module.setting.IntSetting;
import dev.dusk.client.module.setting.Setting;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * The block highlight, rebuilt to work like Custom Block Highlight by
 * tektonikal (GPL-3.0; behaviour only, none of its code): up to three
 * layered outlines and a fill, each with its own shape, faces, depth test,
 * colours (gradient or rainbow) and cuts, eased between blocks with fades,
 * scaling and rotation. Setting ids are CBH's config keys, so its configs
 * paste straight in and ours paste into it. The drawing lives in the
 * 1.21.11+ layer's render/highlight package.
 */
public class BlockHighlight extends Module {
    public enum Shape { CLASSIC_BOX, COLLISION_SHAPE, MODEL_SHAPE }

    public enum Faces { AIR_EXPOSED, ALL, CONCEALED, LOOKAT }

    public enum Depth { NORMAL, ALWAYS_PASS, HIDDEN_ONLY }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static BlockHighlight instance;

    /** Every setting that goes into the shared config format, with how it's written there. */
    private final List<Setting<?>> shared = new ArrayList<>();

    public final BoolSetting enableModRendering = keep(add(new BoolSetting("enableModRendering", "Custom highlight", true), "General"));
    public final BoolSetting drawVanillaOutline = keep(add(new BoolSetting("drawVanillaOutline", "Vanilla outline as well", false), "General"));

    public final Layer primary = new Layer("primary", "Outline", 128, false, 1f, 25, 5, 99, 100, 100);
    public final Layer secondary = new Layer("secondary", "Second outline", 255, true, 0.75f, 50, 10, 95, 50, 200);
    public final Layer tertiary = new Layer("tertiary", "Third outline", 255, false, 1f, 25, 10, 95, 100, 100);

    private final BooleanSupplier on = this::customOn;

    public final BoolSetting fillEnabled = keep(add(new BoolSetting("fillEnabled", "Enabled", true), "Fill")).visibleWhen(on);
    private final BooleanSupplier fillOn = () -> customOn() && fillEnabled.get();
    public final Pick<Faces> fillType = keep(add(faces("fillType", Faces.ALL), "Fill")).visibleWhen(fillOn);
    public final Pick<Depth> fillDepthTest = keep(add(depth("fillDepthTest", Depth.HIDDEN_ONLY), "Fill")).visibleWhen(fillOn);
    public final IntSetting fillExpandBlocks = keep(add(expandBlocks("fillExpandBlocks"), "Fill")).visibleWhen(fillOn);
    public final IntSetting fillExpandPercent = keep(add(percent("fillExpandPercent", "Grow (percent)", 100, 0, 200, 1), "Fill")).visibleWhen(fillOn);
    public final Paint fillCol = new Paint("fillCol", "Fill colour", 0xFF00FFFF, 0xFF0000FF, 39, 1, false, 1f, fillOn);

    public final BoolSetting doEasing = extra(new BoolSetting("doEasing", "Glide between blocks", true), "Animation", on);
    public final IntSetting easeSpeed = extra(speed("easeSpeed", "Glide speed", 350, 50, 1000, 5), "Animation", () -> customOn() && doEasing.get());
    public final BoolSetting improvedEasing = extra(new BoolSetting("improvedEasing", "Time glide by frame (VSync off)", false), "Animation", () -> customOn() && doEasing.get());
    public final BoolSetting fadeIn = extra(new BoolSetting("fadeIn", "Fade in", true), "Animation", on);
    public final IntSetting fadeInSpeed = extra(speed("fadeInSpeed", "Fade in speed", 150, 50, 250, 1), "Animation", () -> customOn() && fadeIn.get());
    public final BoolSetting fadeOut = extra(new BoolSetting("fadeOut", "Fade out", true), "Animation", on);
    public final IntSetting fadeOutSpeed = extra(speed("fadeOutSpeed", "Fade out speed", 150, 50, 250, 1), "Animation", () -> customOn() && fadeOut.get());
    public final BoolSetting scale = extra(new BoolSetting("scale", "Grow in and shrink out", true), "Animation", on);
    public final IntSetting scaleSpeed = extra(speed("scaleSpeed", "Grow speed", 150, 50, 250, 1), "Animation", () -> customOn() && scale.get());
    public final BoolSetting animateLineThickness = extra(new BoolSetting("animateLineThickness", "Thicken lines in and out", true), "Animation", on);
    public final IntSetting lineThicknessAnimationSpeed = extra(speed("lineThicknessAnimationSpeed", "Thicken speed", 150, 50, 250, 1), "Animation", () -> customOn() && animateLineThickness.get());

    public final BoolSetting crystalHelper = extra(new BoolSetting("crystalHelper", "Enabled", false), "Crystal helper", on);
    public final ColorSetting crystalHelperLineColor = extra(new ColorSetting("crystalHelperLineColor", "Outline colour", 0xFFFF0000).opaque(), "Crystal helper", () -> customOn() && crystalHelper.get());
    public final ColorSetting crystalHelperFillColor = extra(new ColorSetting("crystalHelperFillColor", "Fill colour", 0xFFFF0000).opaque(), "Crystal helper", () -> customOn() && crystalHelper.get());

    public final BoolSetting allowEntities = extra(new BoolSetting("allowEntities", "Entities", true), "Show on", on);
    public final BoolSetting allowLiquids = extra(new BoolSetting("allowLiquids", "Fluids", true), "Show on", on);
    public final BoolSetting onlySourceBlocks = extra(new BoolSetting("onlySourceBlocks", "Only source fluids", true), "Show on", () -> customOn() && allowLiquids.get());
    public final BoolSetting onlyWhenHoldingAppropriate = extra(new BoolSetting("onlyWhenHoldingAppropriate", "Fluids only with a bucket, boat or lily pad", true), "Show on", () -> customOn() && allowLiquids.get());
    public final BoolSetting showWhenNoHud = extra(new BoolSetting("showWhenNoHud", "With the HUD hidden (F1)", false), "Show on", on);
    public final BoolSetting showWhenNoInteraction = extra(new BoolSetting("showWhenNoInteraction", "When you can't build (adventure, spectator)", false), "Show on", on);

    public final BoolSetting connectedBlocks = extra(new BoolSetting("connectedBlocks", "Join beds, doors, double chests and pistons", true), "Misc", on);
    public final BoolSetting updateWhenUnfocused = extra(new BoolSetting("updateWhenUnfocused", "Keep gliding while looking at nothing", false), "Misc", on);
    public final BoolSetting rotations = extra(new BoolSetting("rotations", "Turn to face the side you look at (beta)", false), "Misc", on);
    public final IntSetting rotationSpeed = extra(speed("rotationSpeed", "Turn speed", 200, 50, 500, 1), "Misc", () -> customOn() && rotations.get());

    public BlockHighlight() {
        super("blockhighlight", "Block Highlight", Category.RENDER,
                "Custom Block Highlight's outlines: layered, gradient or rainbow, filled, animated.");
        instance = this;
        String presets = "Presets and sharing";
        add(new ActionSetting("presetVanilla", "Preset: plain, like vanilla", "Apply", () -> applyPreset(VANILLA)), presets);
        add(new ActionSetting("presetSweat", "Preset: PvP", "Apply", () -> applyPreset(SWEAT)), presets);
        add(new ActionSetting("presetTrans", "Preset: trans flag", "Apply", () -> applyPreset(TRANS)), presets);
        add(new ActionSetting("presetClassic", "Preset: classic CBH", "Apply", () -> applyPreset(CLASSIC)), presets);
        add(new ActionSetting("presetFancy", "Preset: everything on", "Apply", () -> applyPreset(Map.of())), presets);
        add(new ActionSetting("copy", "Copy settings (works in CBH too)", "Copy",
                () -> Minecraft.getInstance().keyboardHandler.setClipboard(exportJson())), presets);
        add(new ActionSetting("paste", "Paste settings from the clipboard", "Paste",
                () -> importJson(Minecraft.getInstance().keyboardHandler.getClipboard())), presets);
    }

    /** The module while it is on, else null. */
    public static BlockHighlight active() {
        BlockHighlight m = instance;
        return m != null && m.enabled() ? m : null;
    }

    /** Whether the game's own outline should be drawn this frame. */
    public static boolean drawVanilla() {
        BlockHighlight m = active();
        return m == null || m.drawVanillaOutline.get();
    }

    public boolean customOn() { return enableModRendering.get(); }

    /** CBH's layer numbering: 2 is the primary outline, 0 the tertiary. */
    public Layer layer(int i) {
        return i == 2 ? primary : i == 1 ? secondary : tertiary;
    }

    // ---- one outline layer ----

    public final class Layer {
        public final BoolSetting enabled;
        public final Paint color;
        public final IntSetting lineWidth, lineExpandBlocks, lineExpandPercentage;
        public final IntSetting cutFromCenter, cutFromCorner, innerThicknessMult, outerThicknessMult;
        public final Pick<Depth> lineDepthTest;
        public final Pick<Faces> outlineType;
        public final Pick<Shape> shapeStyle;

        Layer(String key, String title, int alpha, boolean rainbow, float brightness,
              int width, int minWidth, int maxCut, int inner, int outer) {
            boolean isPrimary = key.equals("primary");
            BooleanSupplier shown = isPrimary ? BlockHighlight.this::customOn : () -> customOn() && primary.enabled.get();
            BooleanSupplier active = () -> shown.getAsBoolean() && on();
            enabled = keep(add(new BoolSetting(key + ".enabled", "Enabled", !key.equals("tertiary")), title)).visibleWhen(shown);
            shapeStyle = keep(add(new Pick<>(key + ".shapeStyle", "Shape", Shape.COLLISION_SHAPE,
                    "Bounding box", "Collision shape", "Model shape (beta)"), title)).visibleWhen(active);
            outlineType = keep(add(BlockHighlight.faces(key + ".outlineType", Faces.ALL), title))
                    .visibleWhen(() -> active.getAsBoolean() && shapeStyle.value() == Shape.CLASSIC_BOX);
            lineDepthTest = keep(add(BlockHighlight.depth(key + ".lineDepthTest", Depth.ALWAYS_PASS), title)).visibleWhen(active);
            lineWidth = keep(add(new IntSetting(key + ".lineWidth", "Line width", width, minWidth, 150, 1, "").decimals(1)
                    .format(v -> String.format(Locale.ROOT, "%.1f px", v / 10f)), title)).visibleWhen(active);

            color = new Paint(key + ".color", title + " colour", 0xFF000000, key.equals("tertiary") ? 0xFFFFFFFF : 0xFF000000,
                    alpha, 0, rainbow, brightness, active);

            String sizes = title + " size and gaps";
            lineExpandBlocks = keep(add(BlockHighlight.expandBlocks(key + ".lineExpandBlocks"), sizes)).visibleWhen(active);
            lineExpandPercentage = keep(add(percent(key + ".lineExpandPercentage", "Grow (percent)", 100, 0, 200, 1), sizes)).visibleWhen(active);
            cutFromCorner = keep(add(percent(key + ".cutFromCorner", "Gap at the corners", 0, 0, maxCut, 1), sizes)).visibleWhen(active);
            outerThicknessMult = keep(add(percent(key + ".outerThicknessMult", "Thickness at the corners", outer, 0, 200, 5), sizes)).visibleWhen(active);
            cutFromCenter = keep(add(percent(key + ".cutFromCenter", "Gap in the middle", 25, 0, maxCut, 1), sizes)).visibleWhen(active);
            innerThicknessMult = keep(add(percent(key + ".innerThicknessMult", "Thickness in the middle", inner, 0, 200, 5), sizes)).visibleWhen(active);
            // the two gaps share the line: changing one shrinks the other to keep them under 95%
            cutFromCorner.onSet(() -> limitGap(cutFromCorner, cutFromCenter));
            cutFromCenter.onSet(() -> limitGap(cutFromCenter, cutFromCorner));
        }

        public boolean on() { return enabled.get(); }
        public float width() { return lineWidth.asFloat(); }
        public float expandBlocks() { return lineExpandBlocks.asFloat(); }
        public float expandPercent() { return lineExpandPercentage.asFloat(); }
        public float cutCenter() { return cutFromCenter.asFloat(); }
        public float cutCorner() { return cutFromCorner.asFloat(); }
        public float inner() { return innerThicknessMult.asFloat(); }
        public float outer() { return outerThicknessMult.asFloat(); }
        public Depth depth() { return lineDepthTest.value(); }
        public Faces faces() { return outlineType.value(); }
        public Shape shape() { return shapeStyle.value(); }
    }

    private static void limitGap(IntSetting changed, IntSetting other) {
        if (changed.get() + other.get() >= 95) {
            int room = Math.max(0, Math.min(100, 95 - changed.get()));
            if (other.get() > room) other.set(room);
        }
    }

    // ---- a colour: two gradient ends or a rainbow, and an opacity ----

    public final class Paint {
        public final ColorSetting col1, col2;
        public final IntSetting alpha, delay, speed, saturation, brightness;
        public final BoolSetting rainbow;

        Paint(String key, String group, int c1, int c2, int alphaDefault, int alphaMin,
              boolean rainbowOn, float bri, BooleanSupplier active) {
            BooleanSupplier plain = () -> active.getAsBoolean() && !rainbow();
            BooleanSupplier cycling = () -> active.getAsBoolean() && rainbow();
            col1 = keep(add(new ColorSetting(key + ".col1", "Colour", c1).opaque(), group)).visibleWhen(plain);
            col2 = keep(add(new ColorSetting(key + ".col2", "Fades into", c2).opaque(), group)).visibleWhen(plain);
            alpha = keep(add(new IntSetting(key + ".alpha", "Opacity", alphaDefault, alphaMin, 255)
                    .format(v -> v * 100 / 255 + "%"), group)).visibleWhen(active);
            rainbow = keep(add(new BoolSetting(key + ".rainbowSettings.enabled", "Rainbow", rainbowOn), group)).visibleWhen(active);
            speed = keep(add(new IntSetting(key + ".rainbowSettings.speed", "Rainbow speed", 50, 10, 100, 1, "").decimals(1)
                    .format(v -> String.format(Locale.ROOT, "%.1fx", v / 10f)), group)).visibleWhen(cycling);
            delay = keep(add(new IntSetting(key + ".rainbowSettings.delay", "Second end's head start", 250, -1000, 1000, 1, " ms"), group)).visibleWhen(cycling);
            saturation = keep(add(percent(key + ".rainbowSettings.saturation", "Saturation", 100, 0, 100, 1), group)).visibleWhen(cycling);
            brightness = keep(add(percent(key + ".rainbowSettings.brightness", "Brightness", Math.round(bri * 100), 0, 100, 1), group)).visibleWhen(cycling);
        }

        public int rgb1() { return col1.argb() & 0xFFFFFF; }
        public int rgb2() { return col2.argb() & 0xFFFFFF; }
        public int alpha() { return alpha.get(); }
        public boolean rainbow() { return rainbow.get(); }
        public float speed() { return speed.asFloat(); }
        public int delay() { return delay.get(); }
        public float saturation() { return saturation.asFloat(); }
        public float brightness() { return brightness.asFloat(); }
    }

    // ---- a choice backed by an enum ----

    public static final class Pick<E extends Enum<E>> extends ChoiceSetting {
        private final E[] values;

        Pick(String id, String name, E def, String... labels) {
            super(id, name, labels[def.ordinal()], labels);
            this.values = def.getDeclaringClass().getEnumConstants();
        }

        public E value() { return values[index()]; }

        public void setValue(E e) { set(options().get(e.ordinal())); }

        boolean setName(String name) {
            for (E e : values) {
                if (e.name().equals(name)) {
                    setValue(e);
                    return true;
                }
            }
            return false;
        }
    }

    private static Pick<Faces> faces(String id, Faces def) {
        return new Pick<>(id, "Faces", def, "Faces next to air", "All faces", "Faces against blocks", "The face you look at");
    }

    private static Pick<Depth> depth(String id, Depth def) {
        return new Pick<>(id, "Depth test", def, "Normal", "Show through blocks", "Only behind blocks");
    }

    private static IntSetting percent(String id, String name, int def, int min, int max, int step) {
        return new IntSetting(id, name, def, min, max, step, "").decimals(2).format(v -> v + "%");
    }

    private static IntSetting speed(String id, String name, int def, int min, int max, int step) {
        return new IntSetting(id, name, def, min, max, step, "").decimals(1)
                .format(v -> String.format(Locale.ROOT, "%.1fx", v / 10f));
    }

    /** -2 to 1 blocks in sixteenths, stored in ten-thousandths. */
    private static IntSetting expandBlocks(String id) {
        return new IntSetting(id, "Grow (blocks)", 0, -20000, 10000, 625, "").decimals(4).format(v -> {
            String n = String.format(Locale.ROOT, "%.3f", v / 10000f).replace(".000", "");
            return n + (Math.abs(v) == 10000 ? " block" : " blocks");
        });
    }

    private <S extends Setting<?>> S keep(S setting) {
        shared.add(setting);
        return setting;
    }

    private <S extends Setting<?>> S extra(S setting, String group, BooleanSupplier visible) {
        keep(add(setting, group));
        setting.visibleWhen(visible);
        return setting;
    }

    // ---- CBH's config format ----

    /** The settings in Custom Block Highlight's JSON layout. */
    public String exportJson() {
        JsonObject root = new JsonObject();
        for (Setting<?> s : shared) {
            JsonObject at = root;
            String[] path = s.id().split("\\.");
            for (int i = 0; i < path.length - 1; i++) {
                if (!at.has(path[i])) at.add(path[i], new JsonObject());
                at = at.getAsJsonObject(path[i]);
            }
            at.add(path[path.length - 1], write(s));
        }
        return GSON.toJson(root);
    }

    private static JsonElement write(Setting<?> s) {
        if (s instanceof BoolSetting b) return new JsonPrimitive(b.get());
        if (s instanceof ColorSetting c) return new JsonPrimitive(c.argb());
        if (s instanceof Pick<?> p) return new JsonPrimitive(p.value().name());
        IntSetting i = (IntSetting) s;
        return i.id().endsWith("alpha") || i.id().endsWith("delay") ? new JsonPrimitive(i.get()) : new JsonPrimitive(i.asFloat());
    }

    /** Loads a CBH-format config; anything that isn't one is ignored. Returns whether it loaded. */
    public boolean importJson(String text) {
        Map<String, JsonElement> flat = new LinkedHashMap<>();
        try {
            JsonElement root = JsonParser.parseString(text == null ? "" : text);
            if (!root.isJsonObject()) return false;
            flatten("", root.getAsJsonObject(), flat);
        } catch (RuntimeException e) {
            return false;
        }
        if (!flat.containsKey("primary.enabled")) return false;
        apply(flat);
        return true;
    }

    private static void flatten(String prefix, JsonObject o, Map<String, JsonElement> out) {
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            if (e.getValue().isJsonObject()) flatten(prefix + e.getKey() + ".", e.getValue().getAsJsonObject(), out);
            else out.put(prefix + e.getKey(), e.getValue());
        }
    }

    private void apply(Map<String, ?> values) {
        for (Setting<?> s : shared) {
            Object v = values.get(s.id());
            if (v instanceof JsonElement j) {
                if (!j.isJsonPrimitive()) continue;
                JsonPrimitive p = j.getAsJsonPrimitive();
                v = p.isBoolean() ? (Object) p.getAsBoolean() : p.isNumber() ? (Object) p.getAsDouble() : p.getAsString();
            }
            if (v == null) continue;
            try {
                if (s instanceof BoolSetting b && v instanceof Boolean x) b.set(x);
                else if (s instanceof ColorSetting c && v instanceof Number n) c.set((int) n.longValue());
                else if (s instanceof Pick<?> p && v instanceof String n) p.setName(n);
                else if (s instanceof IntSetting i && v instanceof Number n) {
                    if (i.id().endsWith("alpha") || i.id().endsWith("delay")) i.set(n.intValue());
                    else i.setFloat(n.doubleValue());
                }
            } catch (RuntimeException ignored) {
                // a value of the wrong kind keeps what was there
            }
        }
    }

    // ---- presets: CBH's five, each the "everything on" set with changes ----

    /** "Everything on", as changes from the defaults. */
    private static final Map<String, Object> FANCY = map(
            "primary.color.alpha", 255, "primary.color.rainbowSettings.enabled", true,
            "primary.color.rainbowSettings.saturation", 0.5, "primary.lineWidth", 5.0,
            "primary.lineExpandBlocks", -0.0625, "primary.innerThicknessMult", 0.25,
            "secondary.enabled", false, "secondary.color.rainbowSettings.brightness", 1.0,
            "secondary.lineExpandBlocks", -0.0625,
            "updateWhenUnfocused", true, "rotations", true, "rotationSpeed", 35.0);

    private static final Map<String, Object> CLASSIC = map(
            "primary.color.rainbowSettings.saturation", 1.0, "primary.lineWidth", 2.5, "primary.lineExpandBlocks", 0.0,
            "primary.outlineType", "AIR_EXPOSED", "primary.shapeStyle", "CLASSIC_BOX",
            "primary.cutFromCenter", 0.0, "primary.innerThicknessMult", 1.0,
            "secondary.color.rainbowSettings.enabled", false, "secondary.color.rainbowSettings.brightness", 0.75,
            "secondary.lineExpandBlocks", 0.0, "secondary.cutFromCenter", 0.5,
            "fillCol.col1", 0xFFFFFFFF, "fillCol.col2", 0xFF000000, "fillCol.alpha", 129,
            "fillType", "AIR_EXPOSED", "fillDepthTest", "ALWAYS_PASS",
            "updateWhenUnfocused", false, "allowEntities", false, "rotations", false, "rotationSpeed", 20.0);

    private static final Map<String, Object> SWEAT = map(
            "primary.color.col1", 0xFFFF0000, "primary.color.col2", 0xFFFF0000, "primary.color.alpha", 128,
            "primary.color.rainbowSettings.enabled", false, "primary.color.rainbowSettings.saturation", 1.0,
            "primary.lineWidth", 2.5, "primary.lineExpandBlocks", 0.0, "primary.cutFromCenter", 0.5,
            "primary.innerThicknessMult", 1.0,
            "secondary.enabled", true, "secondary.color.rainbowSettings.enabled", false,
            "secondary.color.rainbowSettings.brightness", 0.75, "secondary.lineExpandBlocks", 0.0,
            "secondary.cutFromCenter", 0.5,
            "fillCol.col1", 0xFF000000, "fillCol.col2", 0xFFFF0000, "fillType", "LOOKAT", "fillDepthTest", "NORMAL",
            "crystalHelper", true, "updateWhenUnfocused", false, "rotations", false, "rotationSpeed", 20.0);

    private static final Map<String, Object> TRANS = map(
            "primary.color.col1", 0xFFFFFFFF, "primary.color.col2", 0xFFFFFFFF,
            "primary.color.rainbowSettings.enabled", false, "primary.color.rainbowSettings.saturation", 1.0,
            "primary.lineWidth", 2.5, "primary.lineExpandBlocks", 0.0, "primary.outlineType", "AIR_EXPOSED",
            "primary.cutFromCenter", 0.0, "primary.innerThicknessMult", 1.0,
            "secondary.enabled", true, "secondary.color.col1", 0xFFF0A6B4, "secondary.color.col2", 0xFFF0A6B4,
            "secondary.color.rainbowSettings.enabled", false, "secondary.lineWidth", 7.5,
            "secondary.lineExpandBlocks", 0.0, "secondary.outlineType", "AIR_EXPOSED",
            "secondary.shapeStyle", "CLASSIC_BOX", "secondary.cutFromCenter", 0.0,
            "secondary.innerThicknessMult", 1.0, "secondary.outerThicknessMult", 1.0,
            "tertiary.enabled", true, "tertiary.color.col1", 0xFF5BCEFA, "tertiary.color.col2", 0xFF5BCEFA,
            "tertiary.lineWidth", 12.5, "tertiary.outlineType", "AIR_EXPOSED", "tertiary.shapeStyle", "CLASSIC_BOX",
            "tertiary.cutFromCenter", 0.0,
            "fillEnabled", false, "fillCol.col1", 0xFFFFFFFF, "fillCol.col2", 0xFF000000, "fillCol.alpha", 128,
            "updateWhenUnfocused", false, "rotations", false, "rotationSpeed", 20.0);

    private static final Map<String, Object> VANILLA = map(
            "primary.color.alpha", 103, "primary.color.rainbowSettings.enabled", false,
            "primary.color.rainbowSettings.saturation", 1.0, "primary.lineWidth", 2.5,
            "primary.lineDepthTest", "NORMAL", "primary.lineExpandBlocks", 0.0, "primary.outlineType", "AIR_EXPOSED",
            "primary.cutFromCenter", 0.0, "primary.innerThicknessMult", 1.0,
            "secondary.color.col1", 0xFFFFFFFF, "secondary.color.rainbowSettings.enabled", false,
            "secondary.lineExpandBlocks", 0.0, "secondary.outlineType", "AIR_EXPOSED",
            "secondary.shapeStyle", "CLASSIC_BOX", "secondary.cutFromCenter", 0.0,
            "secondary.innerThicknessMult", 1.0, "secondary.outerThicknessMult", 1.0,
            "tertiary.color.col1", 0xFFFFFFFF, "tertiary.color.col2", 0xFF000000, "tertiary.lineWidth", 5.0,
            "tertiary.outlineType", "AIR_EXPOSED", "tertiary.shapeStyle", "CLASSIC_BOX", "tertiary.cutFromCenter", 0.0,
            "fillEnabled", false, "fillCol.col1", 0xFFFFFFFF, "fillCol.col2", 0xFF000000, "fillCol.alpha", 128,
            "doEasing", false, "easeSpeed", 20.0, "fadeIn", false, "fadeOut", false, "scale", false,
            "animateLineThickness", false, "updateWhenUnfocused", false, "allowEntities", false,
            "allowLiquids", false, "onlyWhenHoldingAppropriate", false, "onlySourceBlocks", false,
            "rotations", false, "rotationSpeed", 20.0);

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private void applyPreset(Map<String, Object> changes) {
        for (Setting<?> s : shared) s.reset();
        apply(FANCY);
        apply(changes);
    }
}
