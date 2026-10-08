package com.heartsplus.render;

/**
 * Depth gap between the sprite layers of the heart bar. Deliberately holds
 * no Minecraft classes so it is directly unit-testable.
 *
 * <p>The gap must grow with the camera distance: depth precision degrades
 * quadratically (at d blocks the depth buffer resolves gaps of roughly
 * d² × {@link #DEPTH_RESOLUTION_PER_SQUARED_BLOCK} blocks). A continuous step
 * proportional to the distance made the layers drift against each other
 * ("swim") while the player moved, so the distance is quantized into
 * 8-block buckets first: the step only ever takes discrete jumps. Since 0.4.9
 * the render distance reaches 2048 blocks, where the depth buffer needs about
 * 5.03 blocks (2048² × 1.2e-6) - the old linear ramp would only deliver
 * 2.048 and the layers would z-fight, so the step is a two-range construct:</p>
 *
 * <ul>
 * <li>the linear ramp is the 0.4.8 formula unchanged (each distance bucket
 * adds {@link #STEP_PER_BUCKET} blocks) and keeps today's behaviour up to
 * roughly 700 m, including the {@link #MIN_STEP} up-close floor;</li>
 * <li>the quadratic term is {@code (8·⌈d/8⌉)² × margin·1.2e-6}, quantized
 * with the distance rounded <i>up</i> to a bucket, so it can never undercut
 * the depth requirement for any distance inside the bucket; it overtakes the
 * linear ramp around 700 m.</li>
 * </ul>
 *
 * <p>The step is the maximum of both terms (plus the floor). Both terms are
 * non-decreasing step functions that only jump at multiples of
 * {@link #BUCKET_DISTANCE}, so the result is monotone, never swims inside a
 * bucket and never jumps down. At 128 m it returns the untouched 0.4.8 value
 * 0.128; at 640 m the linear 0.64 still covers the required 0.59; from 1000 m
 * (1.44) the quadratic term dominates and carries the guarantee (6.04 at
 * 2048 m).</p>
 */
public final class HeartLayerSpacing {
	/** Distance bucket width in blocks; the step changes once per bucket. */
	public static final float BUCKET_DISTANCE = 8.0F;
	/** Layer gap in blocks contributed by one distance bucket (linear range). */
	private static final float STEP_PER_BUCKET = 0.001F;
	/** Up-close floor keeping the layers visually coplanar. */
	private static final float MIN_STEP = 0.002F;
	/**
	 * Depth-buffer resolution loss per squared block of distance: at d blocks
	 * the buffer separates layers about d² × this much apart.
	 */
	public static final float DEPTH_RESOLUTION_PER_SQUARED_BLOCK = 1.2E-6F;
	/** Safety factor applied on top of the raw depth requirement. */
	public static final float DEPTH_GUARANTEE_MARGIN = 1.2F;

	private HeartLayerSpacing() {
	}

	/** Quantized layer gap for a camera distance in blocks. */
	public static float zStep(float distanceToCamera) {
		float linear = floor(distanceToCamera / BUCKET_DISTANCE) * BUCKET_DISTANCE * STEP_PER_BUCKET;
		float ceilBucketBlocks = (float) Math.ceil(distanceToCamera / BUCKET_DISTANCE) * BUCKET_DISTANCE;
		float quadratic = ceilBucketBlocks * ceilBucketBlocks * DEPTH_RESOLUTION_PER_SQUARED_BLOCK
				* DEPTH_GUARANTEE_MARGIN;
		return Math.max(MIN_STEP, Math.max(linear, quadratic));
	}

	/** Rounds down like vanilla {@code Mth.floor} without dragging Minecraft onto the test classpath. */
	private static int floor(float value) {
		int truncated = (int) value;
		return value < truncated ? truncated - 1 : truncated;
	}
}
