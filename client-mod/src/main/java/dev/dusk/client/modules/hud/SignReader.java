package dev.dusk.client.modules.hud;

import dev.dusk.client.compat.Compat;
import dev.dusk.client.gui.Canvas;
import dev.dusk.client.hud.HudContext;
import dev.dusk.client.hud.HudElement;
import dev.dusk.client.hud.Raycast;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HangingSignBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Flex-HUD's sign reader: draws the sign you are looking at, face texture
 * and all, so you can read it from a distance.
 */
public class SignReader extends HudElement {
    private RenderData data = new RenderData();

    public SignReader() {
        super("signreader", "Sign Reader", "Shows the text of the sign you are looking at.");
        setPosition(4, 60);
    }

    @Override
    public void tick() {
        data = collect();
    }

    @Override
    public boolean visible(HudContext ctx) {
        return ctx.player() != null && data.texture != null;
    }

    @Override
    public int width(HudContext ctx) {
        return data.hanging ? Math.round(14 * 4.5f) : 24 * 4;
    }

    @Override
    public int height(HudContext ctx) {
        return data.hanging ? Math.round(10 * 4.5f) : 12 * 4;
    }

    @Override
    public void render(Canvas c, HudContext ctx) {
        RenderData d = data.texture != null ? data : placeholder();
        if (d.texture == null) return;

        float textureScale = d.hanging ? 4.5f : 4f;
        int w = width(ctx);
        int h = height(ctx);
        int textureWidth = Math.round(64 * textureScale);
        int textureHeight = Math.round(32 * textureScale);

        float offsetX = 2 * textureScale;
        if (!d.facingFront) offsetX += w + 2 * textureScale;
        float offsetY = d.hanging ? 14 * textureScale : 2 * textureScale;

        // only the face of the sign sheet, blown up to HUD size
        c.blit(d.texture, 0, 0, offsetX, offsetY, w, h, textureWidth, textureHeight);

        for (int i = 0; i < 4 && i < d.content.length; i++) {
            Component line = d.content[i];
            int x = (w - c.textWidth(line)) / 2;
            int y = d.hanging ? 5 + 9 * i : 4 + 10 * i;

            if (d.glowing) {
                MutableComponent glowLine = stripColors(line);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        if (dx == 0 && dy == 0) continue;
                        c.text(glowLine, x + dx, y + dy, d.glowColor, false);
                    }
                }
            }

            c.text(line, x, y, d.textColor, false);
        }
    }

    /** Keeps italics/bold but drops colours, so the outline draws in one colour. */
    private static MutableComponent stripColors(Component original) {
        MutableComponent copy = original.copy();
        copy.setStyle(Style.EMPTY.withItalic(original.getStyle().isItalic())
                .withBold(original.getStyle().isBold()));
        for (Component sibling : copy.getSiblings()) {
            if (sibling instanceof MutableComponent mutable) {
                mutable.setStyle(Style.EMPTY.withItalic(sibling.getStyle().isItalic())
                        .withBold(sibling.getStyle().isBold()));
            }
        }
        return copy;
    }

    private RenderData placeholder() {
        RenderData d = new RenderData();
        d.texture = Compat.signTexture("oak", false);
        d.content = new Component[]{
                Component.literal(""),
                Component.literal("Sign Reader"),
                Component.literal(""),
                Component.literal("")
        };
        d.facingFront = true;
        return d;
    }

    private RenderData collect() {
        RenderData d = new RenderData();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null || mc.getCameraEntity() == null) return d;

        HitResult hit = Raycast.hitResult();
        if (!(hit instanceof BlockHitResult blockHit)) return d;

        BlockPos pos = blockHit.getBlockPos();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        Block block = level.getBlockState(pos).getBlock();

        SignBlockEntity sign = null;
        String woodName = null;

        switch (block) {
            case StandingSignBlock signBlock when blockEntity instanceof SignBlockEntity e -> {
                sign = e;
                woodName = signBlock.type().name();
                d.hanging = false;
            }
            case WallSignBlock signBlock when blockEntity instanceof SignBlockEntity e -> {
                sign = e;
                woodName = signBlock.type().name();
                d.hanging = false;
            }
            case CeilingHangingSignBlock signBlock when blockEntity instanceof HangingSignBlockEntity e -> {
                sign = e;
                woodName = signBlock.type().name();
                d.hanging = true;
            }
            case WallHangingSignBlock signBlock when blockEntity instanceof HangingSignBlockEntity e -> {
                sign = e;
                woodName = signBlock.type().name();
                d.hanging = true;
            }
            default -> {
            }
        }

        if (sign == null) return d;

        d.facingFront = sign.isFacingFrontText(player);
        SignText text = sign.getText(d.facingFront);
        d.content = text.getMessages(false);
        d.textColor = text.getColor().getTextColor();
        d.glowColor = Compat.signDarkColor(text);
        d.glowing = text.hasGlowingText();
        d.texture = Compat.signTexture(woodName, d.hanging);
        return d;
    }

    private static final class RenderData {
        @Nullable String texture;
        boolean facingFront;
        Component[] content = new Component[0];
        int textColor = DyeColor.BLACK.getTextColor();
        int glowColor = 0xFF000000;
        boolean glowing;
        boolean hanging;
    }
}
