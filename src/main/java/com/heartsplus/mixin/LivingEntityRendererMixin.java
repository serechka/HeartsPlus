package com.heartsplus.mixin;

import com.heartsplus.render.HeartsAboveHeadRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
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
	protected EntityRenderDispatcher dispatcher;

	@Inject(
			method = "render(Lnet/minecraft/entity/LivingEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
			at = @At("RETURN")
	)
	private void heartsplus$renderHearts(LivingEntity entity, float yaw, float tickDelta, MatrixStack matrices,
			VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
		if (entity instanceof PlayerEntity player) {
			// Health is read straight off the entity here: the render call
			// happens every frame anyway, so the values cannot lag behind.
			HeartsAboveHeadRenderer.render(player, this.dispatcher, matrices, vertexConsumers, light, tickDelta);
		}
	}
}
