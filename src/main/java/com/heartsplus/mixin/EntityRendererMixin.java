package com.heartsplus.mixin;

import com.heartsplus.render.HealthHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Copies player health into the render state while it is being extracted,
 * bridging the gap left by the 1.21.9+ extract/submit rendering split. The
 * player's UUID goes along so BlinkTracker can keep the vanilla HUD
 * blink windows alive across recycled render states.
 *
 * <p>Master switch off (0.4.9): this is the tracker's only feed, and the
 * feed's only entry point is the {@code isEnabled()} guard below. With the
 * mod disabled the extract returns before {@code heartsplus$update}, so
 * {@link com.heartsplus.render.BlinkTracker} receives nothing, its maps stay
 * untouched, and no smoothing or animation state advances per frame. The
 * submit side (LivingEntityRendererMixin into
 * HeartsAboveHeadRenderer.renderHearts) re-checks the same flag and draws
 * nothing, and the warm-up tick in HeartsPlusClient is gated by it too -
 * there is no other background work: zero load while off.</p>
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V",
			at = @At("RETURN"))
	private void heartsplus$captureHealth(Entity entity, EntityRenderState state, float partialTicks, CallbackInfo ci) {
		if (!com.heartsplus.HeartsPlusConfig.isEnabled()) {
			// Full stop: the tracker is never fed while the mod is off.
			return;
		}
		if (state instanceof HealthHolder holder && entity instanceof Player player) {
			boolean isLocalPlayer = player == Minecraft.getInstance().player;
			holder.heartsplus$update(player.getUUID(), player.getHealth(), player.getMaxHealth(),
					player.getAbsorptionAmount(), isLocalPlayer,
					player.hasEffect(MobEffects.POISON), player.hasEffect(MobEffects.WITHER),
					player.isFullyFrozen(), hasVisibleArmour(player),
					player.tickCount, player.invulnerableTime > 0,
					nameTagAttachmentY(player, partialTicks));
		}
	}

	/**
	 * The Y of the vanilla name tag attachment point, computed with the same
	 * call EntityRenderer.extractRenderState makes for its own
	 * {@code nameTagAttachment} — that state field is only filled while the
	 * name is actually shown, which for players is essentially never, so the
	 * bar reads the attachment off the entity instead. The point follows the
	 * pose (standing 1.8, crouching 1.5, swimming 0.6) because
	 * {@code getAttachments} serves the current pose dimensions.
	 */
	private static float nameTagAttachmentY(Player player, float partialTicks) {
		Vec3 attachment = player.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, player.getYRot(partialTicks));
		return attachment == null ? player.getBbHeight() : (float) attachment.y;
	}

	private static boolean hasVisibleArmour(Player player) {
		return !player.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
				|| !player.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
				|| !player.getItemBySlot(EquipmentSlot.LEGS).isEmpty()
				|| !player.getItemBySlot(EquipmentSlot.FEET).isEmpty();
	}
}
