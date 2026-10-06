package com.heartsplus.mixin;

import com.heartsplus.render.HealthHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Copies player health into the render state while it is being extracted,
 * bridging the gap left by the 1.21.9+ extract/submit rendering split. The
 * player's UUID goes along so BlinkTracker can keep the vanilla HUD
 * animation state (display health + blink windows) alive across recycled
 * render states.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V",
			at = @At("RETURN"))
	private void heartsplus$captureHealth(Entity entity, EntityRenderState state, float partialTicks, CallbackInfo ci) {
		if (state instanceof HealthHolder holder && entity instanceof Player player) {
			boolean isLocalPlayer = player == Minecraft.getInstance().player;
			holder.heartsplus$update(player.getUUID(), player.getHealth(), player.getMaxHealth(),
					player.getAbsorptionAmount(), isLocalPlayer,
					player.hasEffect(MobEffects.POISON), player.hasEffect(MobEffects.WITHER),
					player.isFullyFrozen(), player.hasEffect(MobEffects.REGENERATION), hasVisibleArmour(player),
					player.tickCount, player.invulnerableTime > 0);
		}
	}

	private static boolean hasVisibleArmour(Player player) {
		return !player.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
				|| !player.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
				|| !player.getItemBySlot(EquipmentSlot.LEGS).isEmpty()
				|| !player.getItemBySlot(EquipmentSlot.FEET).isEmpty();
	}
}
