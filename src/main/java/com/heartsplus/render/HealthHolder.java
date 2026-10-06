package com.heartsplus.render;

import java.util.UUID;

/**
 * Duck-typed interface injected onto the player render state
 * ({@code AvatarRenderState} in Mojang mappings) by mixin.
 * The render state itself carries no health data, so EntityRendererMixin
 * fills these values while the render state is being extracted from the entity.
 * The damage-blink history lives in {@link BlinkTracker}, keyed by the
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

	/** True while the player wears any armour piece; armour betrays invisible players. */
	boolean heartsplus$hasVisibleArmour();

	/** Health the player had before the latest drop; hearts up to this value blink. */
	float heartsplus$getBlinkOldHealth();

	/** Game tick until which the blink animation plays. */
	int heartsplus$getBlinkEndTick();

	void heartsplus$update(UUID playerId, float health, float maxHealth, float absorption, boolean localPlayer,
			boolean poisoned, boolean withered, boolean frozen, boolean hasVisibleArmour, int tick);
}
