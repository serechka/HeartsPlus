package com.heartsplus.mixin;

import com.heartsplus.render.HealthHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Copies avatar health into the render state while it is being extracted,
 * bridging the gap left by the 26.x extract/submit rendering split. The
 * player's UUID goes along so BlinkTracker can keep the vanilla HUD
 * blink windows alive across recycled render states.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
	@Inject(method = "extractRenderState", at = @At("RETURN"))
	private void heartsplus$captureHealth(Entity entity, EntityRenderState state, float partialTicks, CallbackInfo ci) {
		if (state instanceof HealthHolder holder && entity instanceof Avatar avatar) {
			boolean isLocalPlayer = avatar == Minecraft.getInstance().player;
			holder.heartsplus$update(avatar.getUUID(), avatar.getHealth(), avatar.getMaxHealth(),
					avatar.getAbsorptionAmount(), isLocalPlayer,
					avatar.hasEffect(MobEffects.POISON), avatar.hasEffect(MobEffects.WITHER),
					hasVisibleArmour(avatar), avatar.tickCount, avatar.invulnerableTime > 0,
					nameTagAttachmentY(avatar, partialTicks));
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
	private static float nameTagAttachmentY(Avatar avatar, float partialTicks) {
		Vec3 attachment = avatar.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, avatar.getYRot(partialTicks));
		return attachment == null ? avatar.getBbHeight() : (float) attachment.y;
	}

	private static boolean hasVisibleArmour(Avatar avatar) {
		return !avatar.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
				|| !avatar.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
				|| !avatar.getItemBySlot(EquipmentSlot.LEGS).isEmpty()
				|| !avatar.getItemBySlot(EquipmentSlot.FEET).isEmpty();
	}
}
