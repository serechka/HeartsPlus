package com.heartsplus.render;

/**
 * Frame-rate independent exponential chase of one target height, used to
 * glide the heart bar along the vanilla name tag attachment point through
 * pose changes (sneak, swim, fall flying). Deliberately holds no Minecraft
 * classes so it is directly unit-testable; one instance lives per player in
 * {@link PlayerBlinkTracker}, nothing is allocated per frame.
 *
 * <p>Each update moves the height by {@code (target - current) * k} with
 * {@code k = 1 - exp(-dt * RATE)} — the exact-in-dt form of the per-frame
 * lerp, so 30 and 240 fps glide identically — clamped to
 * {@link #MAX_SPEED_BLOCKS_PER_SECOND} so a pose flip after a teleport or
 * respawn cannot smear the bar across the screen.</p>
 */
public final class HeartHeightSmoother {
	/**
	 * Chase rate in 1/seconds. At 8 the remaining gap halves roughly every
	 * 87 ms, so a pose change settles in the owner-tuned 150–250 ms window.
	 */
	public static final double RATE_PER_SECOND = 8.0;
	/** Cap on the chase speed, so respawn/teleport snaps stay bounded. */
	public static final float MAX_SPEED_BLOCKS_PER_SECOND = 4.0F;
	/** Sentinel {@code lastMillis} of an instance that never chased yet. */
	private static final long NEVER = Long.MIN_VALUE;

	private float currentY;
	private long lastMillis = NEVER;

	/** Starts unattached; the first {@link #update} snaps to its target. */
	public HeartHeightSmoother() {
	}

	/**
	 * Advances the chase to {@code millis} towards {@code targetY} and
	 * returns the smoothed height. The first call (or the first after
	 * {@link #snapTo}) snaps instead of gliding, so a freshly seen player
	 * never plays back an approach from zero.
	 */
	public float update(float targetY, long millis) {
		if (this.lastMillis == NEVER) {
			this.currentY = targetY;
			this.lastMillis = millis;
			return this.currentY;
		}
		float dtSeconds = Math.max(0L, millis - this.lastMillis) / 1000.0F;
		this.lastMillis = millis;
		float step = (targetY - this.currentY) * (float) (1.0 - Math.exp(-dtSeconds * RATE_PER_SECOND));
		float maxStep = MAX_SPEED_BLOCKS_PER_SECOND * dtSeconds;
		this.currentY += Math.clamp(step, -maxStep, maxStep);
		return this.currentY;
	}

	/**
	 * Jumps straight to {@code targetY} with no animation — the companion of
	 * the animation-state reset after a world change under the same UUID.
	 * The clock keeps running, so the next update glides on from the snapped
	 * height instead of snapping a second time.
	 */
	public void snapTo(float targetY) {
		this.currentY = targetY;
	}

	/** The height the bar should draw at right now. */
	public float currentY() {
		return this.currentY;
	}
}
