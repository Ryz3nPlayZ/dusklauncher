package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;

public class EntityCount extends TextHud {
    public EntityCount() {
        super("entities", "Entity Count", "Entities", "Entities loaded on the client.");
        setPosition(150, 60);
    }

    @Override
    protected String value(HudContext ctx) {
        var level = ctx.level();
        return level == null ? null : Integer.toString(level.getEntityCount());
    }

    @Override
    protected String sample() {
        return "0";
    }
}
