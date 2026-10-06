package com.heartsplus.mixin;

import com.heartsplus.render.HealthHolder;
import com.heartsplus.render.HeartsAboveHeadRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
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
 * vanilla passes to renderLabelIfPresent.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	@Shadow
	@Final
	protected EntityRenderDispatcher dispatcher;

	@Inject(
			method = "render(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
			at = @At("RETURN")
	)
	private void heartsplus$renderHearts(LivingEntityRenderState state, MatrixStack matrices,
			VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
		if (state instanceof PlayerEntityRenderState playerState) {
			HealthHolder health = (HealthHolder) (Object) playerState;
			HeartsAboveHeadRenderer.render(playerState, health, matrices, vertexConsumers, light,
					this.dispatcher.getRotation());
		}
	}
}
