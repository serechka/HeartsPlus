package com.heartsplus.render;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Per-player damage-blink history, keyed by the entity UUID. Deliberately
 * lives outside the render state: vanilla may hand out a fresh
 * AvatarRenderState for every frame, and storing the history on the state
 * silently reset it, killing the blink animation. Render-thread only — both
 * the extract (update) and submit (read) phases run there — so the plain map
 * needs no locking; entries of despawned players are collected weakly.
 */
public final class BlinkTracker {
	/** How long the highlight plays after a health drop, in game ticks. */
	private static final int BLINK_TICKS = 15;

	private static final Map<UUID, BlinkState> blinkStates = new WeakHashMap<>();

	private BlinkTracker() {
	}

	/**
	 * Feeds the current health of a player; a drop against the previous value
	 * arms the blink window for the hearts between the old and new values.
	 */
	public static void update(UUID playerId, float health, int tick) {
		BlinkState state = blinkStates.computeIfAbsent(playerId, id -> new BlinkState());
		if (!Float.isNaN(state.lastHealth) && health < state.lastHealth - 0.01F) {
			state.blinkOldHealth = state.lastHealth;
			state.blinkEndTick = tick + BLINK_TICKS;
		}
		state.lastHealth = health;
	}

	/** Health the player had before the latest drop; NaN when nothing was recorded yet. */
	public static float getBlinkOldHealth(UUID playerId) {
		BlinkState state = blinkStates.get(playerId);
		return state == null ? Float.NaN : state.blinkOldHealth;
	}

	/** Game tick until which the blink animation plays; MIN_VALUE when idle. */
	public static int getBlinkEndTick(UUID playerId) {
		BlinkState state = blinkStates.get(playerId);
		return state == null ? Integer.MIN_VALUE : state.blinkEndTick;
	}

	private static final class BlinkState {
		private float lastHealth = Float.NaN;
		private float blinkOldHealth;
		private int blinkEndTick = Integer.MIN_VALUE;
	}
}
