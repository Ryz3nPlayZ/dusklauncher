package dev.dusk.client.render.shield;

/**
 * Hands the Shield Statuses colour from item-model resolution (which knows
 * whose shield it is) to the shield renderer (which does not). The colour is
 * stored on the item's render state and published here only while that
 * state submits its geometry.
 */
public final class ShieldTint {
    /** Untinted: vanilla's -1 (opaque white). */
    public static final int NONE = -1;

    /** The tint for the shield being submitted right now, or {@link #NONE}. */
    public static int current = NONE;

    private ShieldTint() {}

    /** Implemented on ItemStackRenderState by a mixin. */
    public interface Holder {
        void dusk$setShieldTint(int argb);
    }

    public static int apply(int vanilla) {
        return current == NONE ? vanilla : current;
    }
}
