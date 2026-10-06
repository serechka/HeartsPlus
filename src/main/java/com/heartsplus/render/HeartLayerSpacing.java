package com.heartsplus.render;

/**
 * Depth gap between the sprite layers of the heart bar. Deliberately holds
 * no Minecraft classes so it is directly unit-testable.
 *
 * <p>The gap must grow with the camera distance: depth precision degrades
 * quadratically (at d blocks the depth buffer resolves gaps of roughly
 * d² × 1.2e-6 blocks). A continuous step proportional to the distance made
 * the layers drift against each other ("swim") while the player moved, so
 * the distance is quantized into 8-block buckets first: the step only ever
 * takes discrete jumps, and at 128 m it still reaches 0.128 blocks per layer
 * — far above the ~0.02 the depth buffer needs there — while the whole
 * 4-layer stack stays under 0.4 blocks of parallax. The 0.002 floor keeps
 * the bar visually coplanar up close.</p>
 */
public final class HeartLayerSpacing {
	/** Distance bucket width in blocks; the step changes once per bucket. */
	public static final float BUCKET_DISTANCE = 8.0F;
	/** Layer gap in blocks contributed by one distance bucket. */
	private static final float STEP_PER_BUCKET = 0.001F;
	/** Up-close floor keeping the layers visually coplanar. */
	private static final float MIN_STEP = 0.002F;

	private HeartLayerSpacing() {
	}

	/** Quantized layer gap for a camera distance in blocks. */
	public static float zStep(float distanceToCamera) {
		int bucket = floor(distanceToCamera / BUCKET_DISTANCE);
		return Math.max(MIN_STEP, bucket * BUCKET_DISTANCE * STEP_PER_BUCKET);
	}

	/** Rounds down like vanilla {@code MathHelper.floor} without dragging Minecraft onto the test classpath. */
	private static int floor(float value) {
		int truncated = (int) value;
		return value < truncated ? truncated - 1 : truncated;
	}
}
