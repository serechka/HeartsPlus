package com.heartsplus.render;

import com.heartsplus.HeartsPlusConfig;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.Util;
import net.minecraft.util.Mth;

/**
 * Per-player vanilla health-animation history and smoothed heart height,
 * keyed by the entity UUID. The classic 1.21.0/1.21.1 render pipeline hands
 * the live entity to the renderer, so health values are read at render time;
 * only the state a frame cannot derive (the blink window and the changed
 * half-heart interval) plus the height smoother are kept here, in MC-free
 * classes ({@link HeartAnimationState}, {@link HeartHeightSmoother}) that
 * mirror the vanilla HUD's animation model. Entries die with the player
 * object; the maps are touched from the render thread only, hence no locking.
 *
 * <p>Both maps are dropped in {@link #clear()} when the play connection
 * ends: a rejoin hands the same UUID a fresh entity whose tick counter
 * restarted, and stale state would replay old blink windows (the 0.4.7
 * flicker).</p>
 */
final class PlayerBlinkTracker {

	private static final Map<UUID, HeartAnimationState> ANIMATION_STATES = new WeakHashMap<>();
	private static final Map<UUID, HeartHeightSmoother> HEIGHT_SMOOTHERS = new WeakHashMap<>();

	private PlayerBlinkTracker() {
	}

	/**
	 * Feeds the current health of a player into the vanilla animation model
	 * and the current name tag attachment height into its height smoother,
	 * returning the height the heart bar should draw at this frame. The
	 * per-tick guard inside {@link HeartAnimationState} keeps the repeated
	 * per-frame render calls from advancing the animation more than once per
	 * game tick — the cadence the vanilla HUD ticks with. With the animation
	 * toggle off the state still tracks the health (so re-enabling cannot
	 * flash a change that happened meanwhile) but arms no windows.
	 *
	 * <p>The smoother's rate and speed cap come from the Follow Smoothing
	 * setting on every call; a smoothness of zero passes rate 0, which makes
	 * the update snap to the target (smoothing off).</p>
	 */
	static float update(UUID playerId, float health, int tick, boolean invulnerable, float attachmentY) {
		HeartAnimationState state = stateFor(playerId);
		boolean entityRecreated = state.tick(Mth.ceil(health), tick, invulnerable,
				HeartsPlusConfig.isBlinkAnimationEnabled());
		float targetY = HeartsAboveHeadRenderer.heartAnchorForAttachment(attachmentY);
		HeartHeightSmoother smoother = HEIGHT_SMOOTHERS.computeIfAbsent(playerId, id -> new HeartHeightSmoother());
		if (entityRecreated) {
			// Same UUID, new entity (world/server switch, chunk reload): the
			// spec says the height snaps with the reset instead of sliding.
			smoother.snapTo(targetY);
		}
		return smoother.update(targetY, Util.getMillis(), HeartsPlusConfig.followRatePerSecond(),
				(float) HeartsPlusConfig.followSpeedCapBlocksPerSecond());
	}

	static HeartAnimationState stateFor(UUID playerId) {
		return ANIMATION_STATES.computeIfAbsent(playerId, id -> new HeartAnimationState());
	}

	/** Drops every per-player state; registered on the play-connection disconnect. */
	static void clear() {
		ANIMATION_STATES.clear();
		HEIGHT_SMOOTHERS.clear();
	}
}
