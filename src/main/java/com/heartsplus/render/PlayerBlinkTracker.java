package com.heartsplus.render;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Per-player vanilla health-animation history, keyed by the entity UUID. The
 * classic 1.21.0/1.21.1 render pipeline hands the live entity to the
 * renderer, so health values are read at render time; only the state a frame
 * cannot derive (the lagging displayHealth copy, the blink window and the
 * last-change stamp) is kept here, in the MC-free {@link HeartAnimationState}
 * that mirrors the vanilla HUD's animation model. Entries die with the player
 * object; the map is touched from the render thread only, hence no locking.
 */
final class PlayerBlinkTracker {

	private static final Map<UUID, HeartAnimationState> STATES = new WeakHashMap<>();

	private PlayerBlinkTracker() {
	}

	static HeartAnimationState stateFor(UUID playerId) {
		return STATES.computeIfAbsent(playerId, id -> new HeartAnimationState());
	}
}
