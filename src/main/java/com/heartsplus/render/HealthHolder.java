package com.heartsplus.render;

/**
 * Duck-typed interface injected onto {@code PlayerEntityRenderState} by mixin.
 * The render state itself carries no health data, so EntityRendererMixin
 * fills these values while the render state is being extracted from the entity.
 * The mixin also tracks recent health drops to drive the vanilla-style
 * damage blink animation.
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

	void heartsplus$update(float health, float maxHealth, float absorption, boolean localPlayer,
			boolean poisoned, boolean withered, boolean frozen, boolean hasVisibleArmour, int tick);
}
