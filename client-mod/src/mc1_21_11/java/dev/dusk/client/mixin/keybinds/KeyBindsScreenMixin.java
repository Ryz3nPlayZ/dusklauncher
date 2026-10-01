package dev.dusk.client.mixin.keybinds;

import dev.dusk.client.gui.KeybindSearch;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsList;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Controlling-style search for Controls → Key Binds: a search box and a
 * filter button (all / by key / conflicts / unbound) between the title and
 * the list, which moves down to make room.
 */
@Mixin(KeyBindsScreen.class)
public abstract class KeyBindsScreenMixin extends OptionsSubScreen {
    @Unique
    private static final int ROW = 24, BOX_WIDTH = 200, BUTTON_WIDTH = 76, GAP = 4;

    @Shadow
    private KeyBindsList keyBindsList;

    @Unique
    private EditBox dusk$search;
    @Unique
    private Button dusk$mode;

    private KeyBindsScreenMixin(Screen parent, Options options, Component title) {
        super(parent, options, title);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void dusk$freshSearch(CallbackInfo ci) {
        KeybindSearch.query = "";
        KeybindSearch.mode = KeybindSearch.Mode.ALL;
    }

    @Inject(method = "addContents", at = @At("TAIL"))
    private void dusk$addSearch(CallbackInfo ci) {
        dusk$search = new EditBox(font, 0, 0, BOX_WIDTH, 20, Component.literal("Search key binds"));
        dusk$search.setHint(Component.literal("Search key binds…"));
        dusk$search.setValue(KeybindSearch.query);
        dusk$search.setResponder(text -> {
            KeybindSearch.query = text;
            dusk$apply();
        });
        dusk$mode = Button.builder(Component.literal(KeybindSearch.mode.label), b -> {
            KeybindSearch.mode = KeybindSearch.mode.next();
            b.setMessage(Component.literal(KeybindSearch.mode.label));
            dusk$apply();
        }).bounds(0, 0, BUTTON_WIDTH, 20).build();
        addRenderableWidget(dusk$search);
        addRenderableWidget(dusk$mode);
        setInitialFocus(dusk$search);
        dusk$apply();
    }

    @Inject(method = "repositionElements", at = @At("TAIL"))
    private void dusk$layout(CallbackInfo ci) {
        if (dusk$search == null) return;
        int top = layout.getHeaderHeight();
        int left = width / 2 - (BOX_WIDTH + GAP + BUTTON_WIDTH) / 2;
        dusk$search.setPosition(left, top);
        dusk$mode.setPosition(left + BOX_WIDTH + GAP, top);
        keyBindsList.updateSizeAndPosition(width, layout.getContentHeight() - ROW, top + ROW);
    }

    @Unique
    private void dusk$apply() {
        ((KeybindSearch.Filterable) keyBindsList).dusk$filter(KeybindSearch.query, KeybindSearch.mode);
    }
}
