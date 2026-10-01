package dev.dusk.client.render.cape;

import org.jetbrains.annotations.Nullable;

/** Carries a player's cape shape ({@link CapeSim#shape}) from the entity to the cape layer on render states. */
public interface CapeShapeHolder {
    void duskclient$setCapeShape(@Nullable float[] shape);

    @Nullable
    float[] duskclient$capeShape();
}
