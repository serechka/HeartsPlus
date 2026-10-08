package com.heartsplus.render;

/**
 * Frame-rate independent exponential chase of one target height, used to
 * glide the heart bar along the vanilla name tag attachment point through
 * pose changes (sneak, swim, fall flying). Deliberately holds no Minecraft
 * classes so it is directly unit-testable; one instance lives per player in
 * {@link PlayerBlinkTracker}, nothing is allocated per frame.
 *
 * <p>Each update moves the height by {@code (target - current) * k} with
 * {@code k = 1 - exp(-dt * rate)} - the exact-in-dt form of the per-frame
 * lerp, so 30 and 240 fps glide identically - clamped to the passed speed cap
 * so a pose flip after a teleport or respawn cannot smear the bar across the
 * screen. Rate and cap arrive as parameters on every update (0.4.9): they are
 * derived from the Follow Smoothing setting, and the smoother itself stays
 * parameter-free.</p>
 *
 * <p>The chase is always towards the target of the current call. Several
 * updates within one frame (target changing N times between two timestamps)
 * advance the clock only on the first of them - dt 0 moves nothing - so the
 * bar always chases the most recent target and no per-frame lag accumulates.
 * A rate of zero or less disables smoothing entirely: the update snaps to the
 * target and returns it immediately.</p>
 */
public final class HeartHeightSmoother {
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
	 *
	 * @param ratePerSecond            exponential chase rate in 1/seconds; zero
	 *                                 or negative turns smoothing off
	 * @param maxSpeedBlocksPerSecond  cap on the chase speed, so
	 *                                 respawn/teleport snaps stay bounded
	 */
	public float update(float targetY, long millis, double ratePerSecond, float maxSpeedBlocksPerSecond) {
		if (ratePerSecond <= 0.0) {
			// Smoothing off: the bar sits exactly on its anchor every frame.
			this.currentY = targetY;
			this.lastMillis = millis;
			return this.currentY;
		}
		if (this.lastMillis == NEVER) {
			this.currentY = targetY;
			this.lastMillis = millis;
			return this.currentY;
		}
		float dtSeconds = Math.max(0L, millis - this.lastMillis) / 1000.0F;
		this.lastMillis = millis;
		float step = (targetY - this.currentY) * (float) (1.0 - Math.exp(-dtSeconds * ratePerSecond));
		float maxStep = maxSpeedBlocksPerSecond * dtSeconds;
		this.currentY += Math.clamp(step, -maxStep, maxStep);
		return this.currentY;
	}

	/**
	 * Jumps straight to {@code targetY} with no animation - the companion of
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
