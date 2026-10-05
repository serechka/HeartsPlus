package com.heartsplus.mixin;

import com.heartsplus.render.HealthHolder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Copies player health into the render state while it is being updated,
 * bridging the gap left by the 1.21.9+ extract/render split.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
	@Inject(method = "updateRenderState", at = @At("RETURN"))
	private void heartsplus$captureHealth(Entity entity, EntityRenderState state, float tickProgress, CallbackInfo ci) {
		if (state instanceof HealthHolder holder && entity instanceof PlayerEntity player) {
			boolean isLocalPlayer = player == MinecraftClient.getInstance().player;
			holder.heartsplus$update(player.getHealth(), player.getMaxHealth(), player.getAbsorptionAmount(), isLocalPlayer,
					player.hasStatusEffect(StatusEffects.POISON), player.hasStatusEffect(StatusEffects.WITHER),
					player.isFrozen());
		}
	}
}
