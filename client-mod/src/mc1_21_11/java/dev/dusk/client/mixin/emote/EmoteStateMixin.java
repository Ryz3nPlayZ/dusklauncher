package dev.dusk.client.mixin.emote;

import dev.dusk.client.emote.EmotePose;
import dev.dusk.client.emote.EmoteState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Emotes: the frame to pose with, on every humanoid render state (only players get one). */
@Mixin(HumanoidRenderState.class)
public class EmoteStateMixin implements EmoteState {
    @Unique
    @Nullable
    private EmotePose duskclient$emote;

    @Override
    public void duskclient$setEmote(@Nullable EmotePose pose) {
        this.duskclient$emote = pose;
    }

    @Override
    @Nullable
    public EmotePose duskclient$emote() {
        return this.duskclient$emote;
    }
}
