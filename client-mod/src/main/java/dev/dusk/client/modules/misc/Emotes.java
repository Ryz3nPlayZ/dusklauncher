package dev.dusk.client.modules.misc;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dusk.client.compat.Compat;
import dev.dusk.client.emote.EmoteAnimation;
import dev.dusk.client.emote.EmoteLibrary;
import dev.dusk.client.emote.EmotePose;
import dev.dusk.client.emote.EmoteWheelScreen;
import dev.dusk.client.module.Module;
import dev.dusk.client.module.setting.ActionSetting;
import dev.dusk.client.module.setting.BoolSetting;
import dev.dusk.client.module.setting.KeySetting;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.jetbrains.annotations.Nullable;

/**
 * Emotes: a key opens a wheel of the animations in the instance's
 * {@code emotes} folder (Blockbench animation files); picking one plays it
 * on your player. Moving, jumping, attacking or using an item stops it.
 *
 * <p>Only you see it: other players' clients don't know you're emoting,
 * because that needs a server to pass it on.
 */
public class Emotes extends Module {
    private static Emotes instance;

    private final KeySetting key = add(new KeySetting("emoteWheel", "Emote wheel key", InputConstants.KEY_B), "Keybinds");
    private final BoolSetting thirdPerson = add(new BoolSetting("thirdPerson", "Look at yourself while emoting", true), "Camera");
    private final BoolSetting stopOnMove = add(new BoolSetting("stopOnMove", "Stop when you move", true), "Playback");
    private final ActionSetting reload = add(new ActionSetting("reload", "Emote files", "Reload", EmoteLibrary::reload), "Playback");

    @Nullable private EmoteAnimation playing;
    private long startNanos;
    private boolean wasDown;
    @Nullable private CameraType previousView;

    public Emotes() {
        super("emotes", "Emotes", Category.MISC,
                "Press B to pick an emote. Add Blockbench animation files to the emotes folder in this instance. Only you can see them for now.");
        instance = this;
        setEnabled(true);
    }

    /** This frame's pose for {@code entity}, or null when it isn't you playing an emote. */
    @Nullable
    public static EmotePose poseFor(Object entity) {
        Emotes m = instance;
        if (m == null || m.playing == null || !m.enabled()) return null;
        if (entity != Minecraft.getInstance().player) return null;
        return m.playing.sample((System.nanoTime() - m.startNanos) / 1e9);
    }

    /** The emote playing now, or null. */
    @Nullable
    public static EmoteAnimation playing() {
        Emotes m = instance;
        return m == null ? null : m.playing;
    }

    public static void play(EmoteAnimation emote) {
        Emotes m = instance;
        if (m == null || !m.enabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        m.playing = emote;
        m.startNanos = System.nanoTime();
        if (m.thirdPerson.get() && m.previousView == null && mc.options.getCameraType() == CameraType.FIRST_PERSON) {
            m.previousView = CameraType.FIRST_PERSON;
            mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        }
    }

    public static void stop() {
        Emotes m = instance;
        if (m != null) m.stopPlaying();
    }

    private void stopPlaying() {
        playing = null;
        Minecraft mc = Minecraft.getInstance();
        // only undo the view we set; F5 during the emote wins
        if (previousView != null && mc.options.getCameraType() == CameraType.THIRD_PERSON_FRONT) {
            mc.options.setCameraType(previousView);
        }
        previousView = null;
    }

    @Override
    protected void onDisable() {
        stopPlaying();
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        boolean down = key.mapping().isDown();
        if (down && !wasDown && mc.player != null && Compat.currentScreen(mc) == null) {
            Compat.setScreen(mc, new EmoteWheelScreen());
        }
        wasDown = down;
        if (playing == null) return;
        if (mc.player == null) { stopPlaying(); return; }
        if (playing.finished((System.nanoTime() - startNanos) / 1e9)) { stopPlaying(); return; }
        if (Compat.currentScreen(mc) != null) return;
        Options o = mc.options;
        boolean acting = o.keyAttack.isDown() || o.keyUse.isDown();
        boolean moving = o.keyUp.isDown() || o.keyDown.isDown() || o.keyLeft.isDown() || o.keyRight.isDown()
                || o.keyJump.isDown() || o.keyShift.isDown();
        if (acting || (stopOnMove.get() && moving)) stopPlaying();
    }
}
