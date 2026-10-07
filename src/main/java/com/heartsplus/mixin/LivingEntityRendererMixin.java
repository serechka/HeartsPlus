package com.heartsplus.mixin;

import com.heartsplus.render.HeartsAboveHeadRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the heart bar once the living entity itself has been rendered
 * (classic 1.21.0/1.21.1 pipeline: the renderer receives the entity, there
 * are no render states to hook). Only players get hearts.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	@Shadow
	@Final
	protected EntityRenderDispatcher entityRenderDispatcher;

	@Inject(
			method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
			at = @At("RETURN")
	)
	private void heartsplus$renderHearts(LivingEntity entity, float yaw, float partialTick, PoseStack poseStack,
			MultiBufferSource bufferSource, int packedLight, CallbackInfo ci) {
		if (entity instanceof Player player) {
			// Health is read straight off the entity here: the render call
			// happens every frame anyway, so the values cannot lag behind.
			HeartsAboveHeadRenderer.render(player, this.entityRenderDispatcher, poseStack, bufferSource, packedLight,
					partialTick);
		}
	}
}
