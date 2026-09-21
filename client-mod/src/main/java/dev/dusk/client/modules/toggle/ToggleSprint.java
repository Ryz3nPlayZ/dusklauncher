package dev.fasterlauncher.client.modules.toggle;

import dev.fasterlauncher.client.module.Module;

/**
 * ToggleSprint/ToggleSneak. Implemented at the input-preference layer only:
 * it must not alter packet semantics — sprint/sneak flags remain vanilla-consistent
 * so server-side anticheats see standard movement.
 */
public class ToggleSprint extends Module {
    private boolean toggleSneak;

    public ToggleSprint() {
        super("togglesprint", "ToggleSprint", Category.MOVEMENT);
    }

    public boolean toggleSneak() { return toggleSneak; }
    public void setToggleSneak(boolean v) { this.toggleSneak = v; }
}
