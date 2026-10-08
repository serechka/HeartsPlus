package com.heartsplus.render;

import com.heartsplus.HeartsPlusConfig;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

/**
 * Per-player vanilla health-animation history and smoothed heart height,
 * keyed by the entity UUID. Deliberately lives outside the render state:
 * vanilla may hand out a fresh PlayerEntityRenderState for every frame, and
 * storing the history on the state silently reset it, killing the
 * animation. Render-thread only — both the extract (update) and render
 * (read) phases run there — so the plain maps need no locking; entries of
 * despawned players are collected weakly.
 *
 * <p>Both maps are dropped in {@link #clear()} when the play connection
 * ends: a rejoin hands the same UUID a fresh entity whose tick counter
 * restarted, and stale state would replay old blink windows (the 0.4.7
 * flicker).</p>
 */
public final class BlinkTracker {
	private static final Map<UUID, HeartAnimationState> animationStates = new WeakHashMap<>();
	private static final Map<UUID, HeartHeightSmoother> heightSmoothers = new WeakHashMap<>();

	private BlinkTracker() {
	}

	/**
	 * Feeds the current health of a player into the vanilla animation model
	 * and the current name tag attachment height into its height smoother,
	 * returning the height the heart bar should draw at this frame. The
	 * per-tick guard inside {@link HeartAnimationState} keeps the repeated
	 * per-frame update calls from advancing the animation more than once per
	 * game tick — the cadence the vanilla HUD ticks with. With the animation
	 * toggle off the state still tracks the health (so re-enabling cannot
	 * flash a change that happened meanwhile) but arms no windows.
	 *
	 * <p>The smoother's rate and speed cap come from the Follow Smoothing
	 * setting on every call; a smoothness of zero passes rate 0, which makes
	 * the update snap to the target (smoothing off).</p>
	 */
	public static float update(UUID playerId, float health, int tick, boolean invulnerable, float attachmentY) {
		HeartAnimationState state = animationStates.computeIfAbsent(playerId, id -> new HeartAnimationState());
		boolean entityRecreated = state.tick(MathHelper.ceil(health), tick, invulnerable, HeartsPlusConfig.isBlinkAnimationEnabled());
		float targetY = HeartsAboveHeadRenderer.heartAnchorForAttachment(attachmentY);
		HeartHeightSmoother smoother = heightSmoothers.computeIfAbsent(playerId, id -> new HeartHeightSmoother());
		if (entityRecreated) {
			// Same UUID, new entity (world/server switch, chunk reload): the
			// spec says the height snaps with the reset instead of sliding.
			smoother.snapTo(targetY);
		}
		return smoother.update(targetY, Util.getMeasuringTimeMs(), HeartsPlusConfig.followRatePerSecond(),
				(float) HeartsPlusConfig.followSpeedCapBlocksPerSecond());
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

	/** Drops every per-player state; registered on the play-connection disconnect. */
	public static void clear() {
		animationStates.clear();
		heightSmoothers.clear();
	}
}
