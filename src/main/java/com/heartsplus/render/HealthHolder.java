package com.heartsplus.render;

import java.util.UUID;

/**
 * Duck-typed interface injected onto {@code AvatarRenderState} by mixin.
 * AvatarRenderState itself carries no health data, so EntityRendererMixin
 * fills these values while the render state is being extracted from the entity.
 * The vanilla animation history lives in {@link BlinkTracker}, keyed by the
 * player's UUID so it survives render-state recycling.
 */
public interface HealthHolder {
	float heartsplus$getHealth();

	float heartsplus$getMaxHealth();

	float heartsplus$getAbsorption();

	boolean heartsplus$isLocalPlayer();

	boolean heartsplus$isPoisoned();

	boolean heartsplus$isWithered();

	boolean heartsplus$isFrozen();

	/** True while Regeneration runs; the bouncing heart follows it like the vanilla HUD. */
	boolean heartsplus$isRegenerating();

	/** True while the player wears any armour piece; armour betrays invisible players. */
	boolean heartsplus$hasVisibleArmour();

	/** Game tick the animation state was last advanced to. */
	int heartsplus$getAnimationTick();

	/** The lagging vanilla displayHealth copy, in half-hearts. */
	int heartsplus$getDisplayHealth();

	/** True on the on-frames of the vanilla blink flash for the current tick. */
	boolean heartsplus$isBlinking();

	void heartsplus$update(UUID playerId, float health, float maxHealth, float absorption, boolean localPlayer,
			boolean poisoned, boolean withered, boolean frozen, boolean regenerating, boolean hasVisibleArmour,
			int tick, boolean invulnerable);
}
