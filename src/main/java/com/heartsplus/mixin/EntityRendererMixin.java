package com.heartsplus.mixin;

import com.heartsplus.render.HealthHolder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityAttachmentType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Copies player health into the render state while it is being updated,
 * bridging the gap left by the 1.21.9+ extract/render split. The
 * player's UUID goes along so BlinkTracker can keep the vanilla HUD
 * blink windows alive across recycled render states.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
	@Inject(method = "updateRenderState", at = @At("RETURN"))
	private void heartsplus$captureHealth(Entity entity, EntityRenderState state, float tickProgress, CallbackInfo ci) {
		if (state instanceof HealthHolder holder && entity instanceof PlayerEntity player) {
			boolean isLocalPlayer = player == MinecraftClient.getInstance().player;
			holder.heartsplus$update(player.getUuid(), player.getHealth(), player.getMaxHealth(),
					player.getAbsorptionAmount(), isLocalPlayer,
					player.hasStatusEffect(StatusEffects.POISON), player.hasStatusEffect(StatusEffects.WITHER),
					player.isFrozen(), hasVisibleArmour(player),
					player.age, player.timeUntilRegen > 0,
					nameTagAttachmentY(player, tickProgress));
		}
	}

	/**
	 * The Y of the vanilla name tag attachment point, computed with the same
	 * call EntityRenderer.updateRenderState makes for its own
	 * {@code nameLabelPos} — that state field is only filled while the name
	 * is actually shown, which for players is essentially never, so the bar
	 * reads the attachment off the entity instead. The point follows the
	 * pose (standing 1.8, crouching 1.5, swimming 0.6) because
	 * {@code getAttachments} serves the current pose dimensions.
	 */
	private static float nameTagAttachmentY(PlayerEntity player, float tickProgress) {
		Vec3d attachment = player.getAttachments().getPointNullable(EntityAttachmentType.NAME_TAG, 0, player.getLerpedYaw(tickProgress));
		return attachment == null ? player.getHeight() : (float) attachment.y;
	}

	private static boolean hasVisibleArmour(PlayerEntity player) {
		return !player.getEquippedStack(EquipmentSlot.HEAD).isEmpty()
				|| !player.getEquippedStack(EquipmentSlot.CHEST).isEmpty()
				|| !player.getEquippedStack(EquipmentSlot.LEGS).isEmpty()
				|| !player.getEquippedStack(EquipmentSlot.FEET).isEmpty();
	}
}
