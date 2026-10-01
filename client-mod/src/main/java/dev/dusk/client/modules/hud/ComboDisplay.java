package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.ChoiceSetting;
import dev.dusk.client.module.setting.IntSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Eymistaken's HUD's combo counter (MIT, Eymistaken — see NOTICE): "5 Combo"
 * for the hits you land in a row, counted from the server's damage events,
 * and "-3 Combo" for a combo someone is landing on you. Same settings and
 * defaults as the original's combo rules; see {@link ComboTracker}.
 */
public class ComboDisplay extends TextHud {
    private static final String SERVER = "Server", CLIENT = "Client";
    private static final String MODERN = "1.9+", LEGACY = "1.8";

    private static ComboDisplay instance;

    private final IntSetting timeout = add(new IntSetting("timeout", "Timeout", 3, 1, 10, 1, "s"), "Rules");
    private final BoolSetting breakOnAnyDamage = add(new BoolSetting("resetOnAnyDamage", "Break on any damage", true), "Rules");
    private final BoolSetting continueOnSwitch = add(new BoolSetting("continueOnSwitch", "Continue on target switch", true), "Rules");
    private final BoolSetting onlyPlayers = add(new BoolSetting("onlyPlayers", "Only players", true), "Rules");
    private final ChoiceSetting combatMode = add(new ChoiceSetting("combatMode", "Combat", MODERN, MODERN, LEGACY), "Rules");
    private final ChoiceSetting hitDetection = add(new ChoiceSetting("hitDetection", "Hit detection", SERVER, SERVER, CLIENT), "Rules");
    private final BoolSetting decay = add(new BoolSetting("decay", "Decay", false), "Rules");
    private final BoolSetting hideWhenInactive = add(new BoolSetting("hideWhenInactive", "Hide when inactive", false));
    private final IntSetting minDisplay = add(new IntSetting("minDisplay", "Min display", 2, 1, 5, 1, ""));

    public ComboDisplay() {
        super("combo", "Combo Counter", "Hits landed in a row, and hits taken in a row.");
        setPosition(150, 126);
        instance = this;
    }

    long timeoutMs() { return timeout.get() * 1000L; }
    boolean breakOnAnyDamage() { return breakOnAnyDamage.get(); }
    boolean continueOnSwitch() { return continueOnSwitch.get(); }
    boolean onlyPlayers() { return onlyPlayers.get(); }
    boolean modernCombat() { return combatMode.is(MODERN); }
    boolean serverDetection() { return hitDetection.is(SERVER); }
    boolean decay() { return decay.get(); }

    private static ComboDisplay active() {
        ComboDisplay m = instance;
        return m != null && m.enabled() ? m : null;
    }

    public static void onAttack(Entity target, Player attacker) {
        ComboDisplay m = active();
        if (m != null) ComboTracker.onAttack(target, attacker, m);
    }

    public static void onDamageEvent(ClientboundDamageEventPacket packet) {
        ComboDisplay m = active();
        if (m != null) ComboTracker.onDamageEvent(packet, m);
    }

    public static void onEntityEvent(ClientboundEntityEventPacket packet) {
        ComboDisplay m = active();
        if (m != null) ComboTracker.onEntityEvent(packet, m);
    }

    @Override
    public void tick() {
        ComboTracker.onTick(Minecraft.getInstance(), this);
    }

    @Override
    protected void onDisable() {
        ComboTracker.forgetSession();
    }

    @Override
    protected String text(HudContext ctx) {
        if (ctx.player() == null) return null;
        int min = minDisplay.get();
        int own = ComboTracker.combo();
        int received = ComboTracker.received();
        if (own >= min) return format(own);
        if (received >= min) return format(-received);
        return hideWhenInactive.get() ? null : format(own);
    }

    private static String format(int count) {
        return count + " Combo";
    }

    @Override
    protected String sample() {
        return format(5);
    }
}
