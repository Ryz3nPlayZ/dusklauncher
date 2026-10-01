package dev.dusk.client.mixin.keybinds;

import dev.dusk.client.gui.KeybindSearch;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.screens.options.controls.KeyBindsList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/** Lets the key binds list hide rows that don't match the search (see KeyBindsScreenMixin). */
@Mixin(KeyBindsList.class)
public abstract class KeyBindsListMixin extends ContainerObjectSelectionList<KeyBindsList.Entry> implements KeybindSearch.Filterable {
    @Unique
    private final List<KeyBindsList.Entry> dusk$all = new ArrayList<>();

    private KeyBindsListMixin(Minecraft minecraft, int width, int height, int y, int itemHeight) {
        super(minecraft, width, height, y, itemHeight);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void dusk$remember(CallbackInfo ci) {
        dusk$all.addAll(children());
    }

    @Override
    public void dusk$filter(String query, KeybindSearch.Mode mode) {
        KeyMapping[] all = minecraft.options.keyMappings;
        List<KeyBindsList.Entry> shown = new ArrayList<>();
        KeyBindsList.Entry heading = null;
        for (KeyBindsList.Entry entry : dusk$all) {
            if (entry instanceof KeyBindsList.CategoryEntry) {
                heading = entry;
            } else if (entry instanceof KeyEntryAccessor key && KeybindSearch.matches(key.dusk$key(), query, mode, all)) {
                // a category heading only shows above a row that survived
                if (heading != null) shown.add(heading);
                heading = null;
                shown.add(entry);
            }
        }
        clearEntries();
        setScrollAmount(0);
        for (KeyBindsList.Entry entry : shown) addEntry(entry);
        // rows hidden during a rebind or "Reset all" missed that refresh
        ((KeyBindsList) (Object) this).refreshEntries();
    }
}
