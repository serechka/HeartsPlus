package com.heartsplus.mixin;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the shadow-rendering flag so hearts can tell world rendering apart
 * from embedded entity previews (the inventory screen model spins through the
 * same renderer with shadows disabled).
 */
@Mixin(EntityRenderDispatcher.class)
public interface EntityRenderDispatcherMixin {
	@Accessor("shouldRenderShadow")
	boolean heartsplus$shouldRenderShadow();
}
