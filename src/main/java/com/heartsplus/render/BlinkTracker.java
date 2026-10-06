package com.heartsplus.render;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

/**
 * Per-player vanilla health-animation history, keyed by the entity UUID.
 * Deliberately lives outside the render state: vanilla hands out recycled
 * render states for every frame, and storing the history on the state
 * silently reset it, killing the animation. Render-thread only — both the
 * state update and the render phases run there — so the plain map needs no
 * locking; entries of despawned players are collected weakly.
 */
public final class BlinkTracker {
	private static final Map<UUID, HeartAnimationState> animationStates = new WeakHashMap<>();

	private BlinkTracker() {
	}

	/**
	 * Feeds the current health of a player into the vanilla animation model.
	 * The per-tick guard inside {@link HeartAnimationState} keeps the repeated
	 * per-frame state updates from advancing the animation more than once per
	 * game tick — the cadence the vanilla HUD ticks with.
	 */
	public static void update(UUID playerId, float health, int tick, boolean invulnerable) {
		animationStates.computeIfAbsent(playerId, id -> new HeartAnimationState())
				.tick(MathHelper.ceil(health), tick, Util.getMeasuringTimeMs(), invulnerable);
	}

	/** The lagging vanilla displayHealth copy in half-hearts; 0 before the first snap. */
	public static int getDisplayHealth(UUID playerId) {
		HeartAnimationState state = animationStates.get(playerId);
		return state == null ? 0 : state.displayHealth();
	}

	/** True on the on-frames of the vanilla blink flash for the current tick. */
	public static boolean isBlinking(UUID playerId) {
		HeartAnimationState state = animationStates.get(playerId);
		return state != null && state.isBlinking();
	}
}
