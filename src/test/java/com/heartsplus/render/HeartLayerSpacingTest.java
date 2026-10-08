package com.heartsplus.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quantized layer gap: constant within an 8-block bucket, discrete jumps
 * between buckets, linear ramp (the 0.4.8 behaviour) at short and medium
 * range, quadratic guarantee at long range.
 */
class HeartLayerSpacingTest {
	private static final float EPSILON = 1e-6F;

	@Test
	void zeroDistanceUsesTheUpCloseFloor() {
		assertEquals(0.002F, HeartLayerSpacing.zStep(0.0F), EPSILON);
	}

	@Test
	void belowOneBucketKeepsTheFloor() {
		assertEquals(0.002F, HeartLayerSpacing.zStep(7.9F), EPSILON);
		assertEquals(0.002F, HeartLayerSpacing.zStep(4.0F), EPSILON);
	}

	@Test
	void oneBucketStepsToEightMilliblocks() {
		assertEquals(0.008F, HeartLayerSpacing.zStep(8.0F), EPSILON);
	}

	@Test
	void longRangeStepCoversDepthPrecisionLoss() {
		// At 128 m the depth buffer needs ~0.02 blocks; the step must stay above it.
		assertEquals(0.128F, HeartLayerSpacing.zStep(128.0F), EPSILON);
		assertTrue(HeartLayerSpacing.zStep(128.0F) > 0.02F);
	}

	@Test
	void theStepIsConstantInsideABucket() {
		float low = HeartLayerSpacing.zStep(8.01F);
		float high = HeartLayerSpacing.zStep(15.99F);
		assertEquals(low, high, EPSILON);
	}

	@Test
	void bucketEdgesRoundDown() {
		// 15.999 m is still bucket 1, 16 m is bucket 2 — the quantization jump.
		assertEquals(0.008F, HeartLayerSpacing.zStep(15.999F), EPSILON);
		assertEquals(0.016F, HeartLayerSpacing.zStep(16.0F), EPSILON);
	}

	/**
	 * The 0.4.9 report values: the linear 0.4.8 ramp still rules at 128 and
	 * 640 m; from 1000 m the quadratic term carries the 1.2x margin over the
	 * raw depth requirement d² × 1.2e-6.
	 */
	@Test
	void theQuadraticTermGuaranteesTheDepthMarginAtLongRange() {
		// 128 m: untouched 0.4.8 value, far above the 0.024 requirement.
		assertEquals(0.128F, HeartLayerSpacing.zStep(128.0F), 1e-4F);
		// 640 m: linear 80 buckets * 0.008 still covers the required 0.590.
		assertEquals(0.64F, HeartLayerSpacing.zStep(640.0F), 1e-4F);
		// 1000 m: quadratic 1000² * 1.2e-6 * 1.2 = 1.44 beats the linear 1.0.
		assertEquals(1.44F, HeartLayerSpacing.zStep(1000.0F), 1e-4F);
		// 2048 m: the old ramp gave 2.048 and z-fought; now 2048² * 1.44e-6.
		assertEquals(2048.0F * 2048.0F * HeartLayerSpacing.DEPTH_RESOLUTION_PER_SQUARED_BLOCK
				* HeartLayerSpacing.DEPTH_GUARANTEE_MARGIN, HeartLayerSpacing.zStep(2048.0F), 1e-4F);
		assertTrue(HeartLayerSpacing.zStep(2048.0F) > 6.0F);
		assertTrue(HeartLayerSpacing.zStep(1000.0F) > HeartLayerSpacing.zStep(640.0F));
		assertTrue(HeartLayerSpacing.zStep(640.0F) > HeartLayerSpacing.zStep(128.0F));
	}

	/** Every metre of the whole render range meets the 1.2x depth requirement. */
	@Test
	void everyMetreOfTheRenderRangeMeetsTheDepthGuarantee() {
		for (int d = 0; d <= 2048; d++) {
			float required = d * d * HeartLayerSpacing.DEPTH_RESOLUTION_PER_SQUARED_BLOCK
					* HeartLayerSpacing.DEPTH_GUARANTEE_MARGIN;
			assertTrue(HeartLayerSpacing.zStep(d) >= required - 1e-4F, "d=" + d);
		}
	}

	/** The step never jumps down anywhere in the range: no z-fight flicker on approach. */
	@Test
	void theStepIsMonotoneAcrossTheWholeRange() {
		float previous = HeartLayerSpacing.zStep(0.0F);
		for (int d = 1; d <= 2048; d++) {
			float step = HeartLayerSpacing.zStep(d);
			assertTrue(step >= previous - 1e-7F, "d=" + d);
			previous = step;
		}
	}

	/** The quadratic range quantizes too: constant inside a bucket, so nothing swims. */
	@Test
	void theQuadraticRangeStaysConstantInsideABucket() {
		// 1025..1032 m rounds up to the 1032 bucket, so the step is constant;
		// 1033 m rounds up to 1040 and only there does the step move.
		float low = HeartLayerSpacing.zStep(1025.0F);
		assertEquals(low, HeartLayerSpacing.zStep(1031.9F), EPSILON);
		assertTrue(HeartLayerSpacing.zStep(1033.0F) > low);
	}
}
