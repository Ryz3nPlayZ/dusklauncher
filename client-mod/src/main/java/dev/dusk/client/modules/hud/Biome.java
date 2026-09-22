package dev.dusk.client.modules.hud;

import dev.dusk.client.compat.Compat;
import dev.dusk.client.hud.Fmt;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;

public class Biome extends TextHud {
    public Biome() {
        super("biome", "Biome", "Biome", "The biome you are standing in.");
        setPosition(5, 93);
    }

    @Override
    protected String value(HudContext ctx) {
        var p = ctx.player();
        var level = ctx.level();
        if (p == null || level == null) return null;
        return level.getBiome(p.blockPosition()).unwrapKey()
                .map(k -> Fmt.title(Compat.keyPath(k)))
                .orElse("Unknown");
    }

    @Override
    protected String sample() {
        return "Plains";
    }
}
