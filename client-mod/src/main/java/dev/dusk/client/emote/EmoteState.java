package dev.dusk.client.emote;

import org.jetbrains.annotations.Nullable;

/** The emote frame carried on a player's render state (added by a mixin). */
public interface EmoteState {
    void duskclient$setEmote(@Nullable EmotePose pose);

    @Nullable
    EmotePose duskclient$emote();
}
