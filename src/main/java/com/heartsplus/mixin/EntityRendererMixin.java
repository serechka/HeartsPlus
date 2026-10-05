package com.heartsplus.mixin;

import com.heartsplus.render.HealthHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Copies avatar health into the render state while it is being extracted,
 * bridging the gap left by the 26.x extract/submit rendering split.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
	@Inject(method = "extractRenderState", at = @At("RETURN"))
	private void heartsplus$captureHealth(Entity entity, EntityRenderState state, float partialTicks, CallbackInfo ci) {
		if (state instanceof HealthHolder holder && entity instanceof Avatar avatar) {
			boolean isLocalPlayer = avatar == Minecraft.getInstance().player;
			holder.heartsplus$update(avatar.getHealth(), avatar.getMaxHealth(), avatar.getAbsorptionAmount(), isLocalPlayer,
					avatar.hasEffect(MobEffects.POISON), avatar.hasEffect(MobEffects.WITHER),
					hasVisibleGear(avatar), avatar.tickCount);
		}
	}

	private static boolean hasVisibleGear(Avatar avatar) {
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			if (!avatar.getItemBySlot(slot).isEmpty()) {
				return true;
			}
		}
		return false;
	}
}
