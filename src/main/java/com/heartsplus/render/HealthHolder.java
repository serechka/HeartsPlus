package com.heartsplus.render;

import java.util.UUID;

/**
 * Duck-typed interface injected onto {@code AvatarRenderState} by mixin.
 * The render state itself carries no health data, so EntityRendererMixin
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

	/** True while the player wears any armour piece; armour betrays invisible players. */
	boolean heartsplus$hasVisibleArmour();

	/** First half-heart index carrying a blinking overlay sprite (inclusive); empty range when none. */
	int heartsplus$getBlinkOverlayStart();

	/** Half-heart index after the last blinking overlay sprite (exclusive). */
	int heartsplus$getBlinkOverlayEnd();

	/** True on the on-frames of the vanilla blink flash for the current tick. */
	boolean heartsplus$isBlinking();

	/** Height above the entity origin where the bar draws: the smoothed name tag attachment anchor. */
	float heartsplus$getHeartAnchorY();

	void heartsplus$update(UUID playerId, float health, float maxHealth, float absorption, boolean localPlayer,
			boolean poisoned, boolean withered, boolean frozen, boolean hasVisibleArmour, int tick,
			boolean invulnerable, float attachmentY);
}
