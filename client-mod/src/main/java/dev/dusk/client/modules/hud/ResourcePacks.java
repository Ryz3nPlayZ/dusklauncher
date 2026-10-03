package dev.dusk.client.modules.hud;

import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.TextHud;
import dev.dusk.client.module.setting.BoolSetting;
import net.minecraft.server.packs.repository.Pack;

import java.util.ArrayList;
import java.util.List;

/** The resource packs you have turned on, highest priority first. */
public class ResourcePacks extends TextHud {
    private final BoolSetting topOnly = add(new BoolSetting("topOnly", "Only the top pack", false));

    public ResourcePacks() {
        super("resourcepacks", "Resource Packs", "The resource packs you have turned on.");
        setPosition(150, 110);
    }

    @Override
    protected String text(HudContext ctx) {
        // user packs live under resourcepacks/ ("file/…"); built-in and mod packs aren't news
        List<String> names = new ArrayList<>();
        for (Pack p : ctx.mc().getResourcePackRepository().getSelectedPacks()) {
            if (p.getId().startsWith("file/")) names.add(0, p.getTitle().getString());
        }
        if (names.isEmpty()) return null;
        if (topOnly.get() && names.size() > 1) return names.get(0) + " +" + (names.size() - 1);
        return String.join(", ", names);
    }

    @Override
    protected String sample() {
        return "Faithful 32x";
    }
}
