package com.heartsplus.render;

import com.heartsplus.HeartsPlusConfig;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.util.Mth;

/**
 * Per-player vanilla health-animation history, keyed by the entity UUID.
 * Deliberately lives outside the render state: vanilla may hand out a fresh
 * AvatarRenderState for every frame, and storing the history on the state
 * silently reset it, killing the animation. Render-thread only — both the
 * extract (update) and submit (read) phases run there — so the plain map
 * needs no locking; entries of despawned players are collected weakly.
 */
public final class BlinkTracker {
	private static final Map<UUID, HeartAnimationState> animationStates = new WeakHashMap<>();

	private BlinkTracker() {
	}

	/**
	 * Feeds the current health of a player into the vanilla animation model.
	 * The per-tick guard inside {@link HeartAnimationState} keeps the repeated
	 * per-frame extract calls from advancing the animation more than once per
	 * game tick — the cadence the vanilla HUD ticks with. With the animation
	 * toggle off the state still tracks the health (so re-enabling cannot
	 * flash a change that happened meanwhile) but arms no windows.
	 */
	public static void update(UUID playerId, float health, int tick, boolean invulnerable) {
		animationStates.computeIfAbsent(playerId, id -> new HeartAnimationState())
				.tick(Mth.ceil(health), tick, invulnerable, HeartsPlusConfig.isBlinkAnimationEnabled());
	}

	/** First half-heart index carrying a blinking overlay sprite (inclusive); 0 when the overlay is empty. */
	public static int getBlinkOverlayStart(UUID playerId) {
		HeartAnimationState state = animationStates.get(playerId);
		return state == null ? 0 : state.blinkOverlayStart();
	}

	/** Half-heart index after the last blinking overlay sprite (exclusive). */
	public static int getBlinkOverlayEnd(UUID playerId) {
		HeartAnimationState state = animationStates.get(playerId);
		return state == null ? 0 : state.blinkOverlayEnd();
	}

	/** True on the on-frames of the vanilla blink flash for the current tick. */
	public static boolean isBlinking(UUID playerId) {
		HeartAnimationState state = animationStates.get(playerId);
		return state != null && state.isBlinking();
	}
}
