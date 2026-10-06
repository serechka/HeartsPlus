package com.heartsplus.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Quantized layer gap: constant within an 8-block bucket, discrete jumps between buckets. */
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
}
