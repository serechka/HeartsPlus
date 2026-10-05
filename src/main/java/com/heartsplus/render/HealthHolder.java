package com.heartsplus.render;

/**
 * Duck-typed interface injected onto {@code PlayerEntityRenderState} by mixin.
 * The render state carries no health data, so LivingEntityRendererMixin fills
 * these values while the state is being extracted from the entity.
 */
public interface HealthHolder {
	float heartsplus$getHealth();

	float heartsplus$getMaxHealth();

	float heartsplus$getAbsorption();

	boolean heartsplus$isLocalPlayer();

	boolean heartsplus$isPoisoned();

	boolean heartsplus$isWithered();

	boolean heartsplus$isFrozen();

	void heartsplus$update(float health, float maxHealth, float absorption, boolean localPlayer,
			boolean poisoned, boolean withered, boolean frozen);
}
