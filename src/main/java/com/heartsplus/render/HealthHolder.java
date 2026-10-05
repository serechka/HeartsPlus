package com.heartsplus.render;

/**
 * Duck-typed interface injected onto {@code AvatarRenderState} by mixin.
 * AvatarRenderState itself carries no health data, so EntityRendererMixin
 * fills these values while the render state is being extracted from the entity.
 */
public interface HealthHolder {
	float heartsplus$getHealth();

	float heartsplus$getMaxHealth();

	float heartsplus$getAbsorption();

	boolean heartsplus$isLocalPlayer();

	boolean heartsplus$isPoisoned();

	boolean heartsplus$isWithered();

	void heartsplus$update(float health, float maxHealth, float absorption, boolean localPlayer,
			boolean poisoned, boolean withered);
}
