package dev.dusk.client.compat;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Supplier;

/**
 * The local player's skin for the title screen and the wardrobe: a live
 * lookup that can be pointed at a freshly uploaded skin (an unsigned
 * textures property, which the insecure lookup accepts), and a 3D preview
 * widget. 1.21.1-1.21.8 flavour (client PlayerSkin of ResourceLocations).
 */
public final class SkinCompat {
    private SkinCompat() {}

    /** What the preview should show instead of the account skin: a registered texture id. */
    public record Preview(String texture, boolean slim) {}

    @Nullable private static Supplier<PlayerSkin> lookup;
    @Nullable private static Preview lastPreview;
    @Nullable private static PlayerSkin lastPreviewSkin;

    public static PlayerSkin current(Minecraft mc) {
        if (lookup == null) lookup = mc.getSkinManager().lookupInsecure(mc.getGameProfile());
        return lookup.get();
    }

    /**
     * Point the local skin at {@code url} (a textures.minecraft.net URL fresh
     * from an upload) without waiting for Mojang's signed profile.
     */
    public static void useUploaded(Minecraft mc, String url, boolean slim) {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\""
                + (slim ? ",\"metadata\":{\"model\":\"slim\"}" : "") + "}}}";
        String packed = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        GameProfile self = mc.getGameProfile();
        GameProfile profile = new GameProfile(self.getId(), self.getName());
        profile.getProperties().put("textures", new Property("textures", packed));
        lookup = mc.getSkinManager().lookupInsecure(profile);
    }

    /** A drag-to-spin 3D model of {@code preview} (or of the account skin while it returns null). */
    public static AbstractWidget widget(Minecraft mc, int w, int h, Supplier<@Nullable Preview> preview) {
        return new PlayerSkinWidget(w, h, mc.getEntityModels(), () -> {
            Preview p = preview.get();
            if (p == null) return current(mc);
            if (!p.equals(lastPreview)) {
                lastPreviewSkin = new PlayerSkin(ResourceLocation.parse(p.texture()), null, null, null,
                        p.slim() ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE, false);
                lastPreview = p;
            }
            return lastPreviewSkin;
        });
    }
}
