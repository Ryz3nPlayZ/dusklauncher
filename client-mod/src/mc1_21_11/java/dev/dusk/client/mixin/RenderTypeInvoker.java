package dev.dusk.client.mixin;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** RenderType's factory is package-private; Block Highlight's through-walls feature draws need their own types. */
@Mixin(RenderType.class)
public interface RenderTypeInvoker {
    @Invoker("create")
    static RenderType duskclient$create(String name, RenderSetup setup) {
        throw new AssertionError();
    }
}
