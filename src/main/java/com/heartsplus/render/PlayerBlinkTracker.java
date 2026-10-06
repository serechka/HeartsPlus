package com.heartsplus.render;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Per-player memory of recent health drops, driving the vanilla-style damage
 * blink. The classic 1.21.0/1.21.1 render pipeline hands the live entity to
 * the renderer, so health values are read at render time and only the state a
 * frame cannot derive (the pre-drop health and the blink deadline) is kept
 * here. Entries die with the player object; the map is touched from the
 * render thread only, hence no locking.
 */
final class PlayerBlinkTracker {
	/** Damage-blink duration in ticks, matching the vanilla HUD. */
	private static final int BLINK_TICKS = 15;

	private static final Map<UUID, BlinkState> STATES = new WeakHashMap<>();

	private PlayerBlinkTracker() {
	}

	static BlinkState stateFor(UUID playerId) {
		return STATES.computeIfAbsent(playerId, id -> new BlinkState());
	}

	/** Blink bookkeeping for one player; mutated from the render thread only. */
	static final class BlinkState {
		private float lastHealth = Float.NaN;
		private float oldHealth;
		private int endTick = Integer.MIN_VALUE;

		/**
		 * Registers the health observed this frame; a drop above 0.01 arms the
		 * blink window with the pre-drop health, like the vanilla HUD does.
		 */
		void update(float health, int tick) {
			if (!Float.isNaN(this.lastHealth) && health < this.lastHealth - 0.01F) {
				this.oldHealth = this.lastHealth;
				this.endTick = tick + BLINK_TICKS;
			}
			this.lastHealth = health;
		}

		/** Health the player had before the latest drop; hearts up to this value blink. */
		float oldHealth() {
			return this.oldHealth;
		}

		/** Game tick until which the blink animation plays. */
		int endTick() {
			return this.endTick;
		}
	}
}
