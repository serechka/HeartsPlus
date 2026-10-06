package com.heartsplus.mixin;

import com.heartsplus.render.HealthHolder;
import com.heartsplus.render.HeartsAboveHeadRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the heart bar once the living entity itself has been rendered, the
 * classic immediate path this version stretch still uses. Only player render
 * states get hearts. The billboard rotation and the packed light mirror what
 * vanilla passes to renderNameTag.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	@Shadow
	@Final
	protected EntityRenderDispatcher dispatcher;

	@Inject(
			method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
			at = @At("RETURN")
	)
	private void heartsplus$renderHearts(LivingEntityRenderState state, PoseStack poseStack,
			MultiBufferSource bufferSource, int packedLight, CallbackInfo ci) {
		if (state instanceof PlayerRenderState playerState) {
			HealthHolder health = (HealthHolder) (Object) playerState;
			HeartsAboveHeadRenderer.render(playerState, health, poseStack, bufferSource, packedLight,
					this.dispatcher.cameraOrientation());
		}
	}
}
