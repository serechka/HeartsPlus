package com.heartsplus.render;

/**
 * Duck-typed interface injected onto {@code AvatarRenderState} by mixin.
 * AvatarRenderState itself carries no health data, so EntityRendererMixin
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

	/** True while the player wears any armour or holds any item; makes hearts on invisible players "fair". */
	boolean heartsplus$hasVisibleGear();

	/** Health the player had before the latest drop; hearts up to this value blink. */
	float heartsplus$getBlinkOldHealth();

	/** Game tick until which the blink animation plays. */
	int heartsplus$getBlinkEndTick();

	void heartsplus$update(float health, float maxHealth, float absorption, boolean localPlayer,
			boolean poisoned, boolean withered, boolean hasVisibleGear, int tick);
}
