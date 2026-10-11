package dev.dusk.client.compat;

import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;

import java.util.List;

/** Opening a {@link Pack}: one PackResources up to 26.2, a stream of them from 26.3. */
public final class PackCompat {
    private PackCompat() {}

    public static List<PackResources> open(Pack pack) {
        return pack.open().toList();
    }
}
