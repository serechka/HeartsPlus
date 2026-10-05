package com.heartsplus.mixin;

import com.heartsplus.render.HealthHolder;
import com.heartsplus.render.HeartsAboveHeadRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the heart bar once the living entity itself has been submitted.
 * Only avatar render states (players and player-like entities) get hearts.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	@Inject(
			method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
			at = @At("RETURN")
	)
	private void heartsplus$submitHearts(LivingEntityRenderState state, PoseStack poseStack,
			SubmitNodeCollector submitNodeCollector, CameraRenderState camera, CallbackInfo ci) {
		if (state instanceof AvatarRenderState avatarState) {
			HealthHolder health = (HealthHolder) (Object) avatarState;
			HeartsAboveHeadRenderer.render(avatarState, health, poseStack, submitNodeCollector, camera);
		}
	}
}
