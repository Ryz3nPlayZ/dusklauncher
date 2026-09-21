package dev.dusk.client.cosmetics;

import dev.dusk.client.cosmetics.model.AccessoryModel;

/** A worn accessory: its registry entry, baked model and uploaded texture. */
public record Accessory(CapeRegistry.AccessoryEntry entry, AccessoryModel model, CapeTexture texture) {
    public boolean isReady() {
        return texture.isRegistered();
    }
}
