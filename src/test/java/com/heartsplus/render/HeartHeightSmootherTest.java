package com.heartsplus.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The exponential chase that glides the heart bar along the vanilla name
 * tag attachment point: exact-in-dt lerp, speed cap, and snap-on-reset.
 */
class HeartHeightSmootherTest {
	private static final float EPSILON = 1.0E-6F;

	@Test
	void theFirstUpdateSnapsToTheTarget() {
		HeartHeightSmoother smoother = new HeartHeightSmoother();

		assertEquals(2.35F, smoother.update(2.35F, 1000L), EPSILON);
		// A fresh player must not play back an approach from zero.
		assertEquals(1.0F, new HeartHeightSmoother().update(1.0F, 0L), EPSILON);
	}

	@Test
	void theChaseFollowsTheExactExponentialForm() {
		HeartHeightSmoother smoother = new HeartHeightSmoother();
		smoother.update(1.8F, 0L);

		// One 50 ms frame: k = 1 - exp(-0.05 * 8).
		float k = (float) (1.0 - Math.exp(-0.05 * HeartHeightSmoother.RATE_PER_SECOND));
		float expected = 1.8F + (2.35F - 1.8F) * k;
		assertEquals(expected, smoother.update(2.35F, 50L), EPSILON);
	}

	@Test
	void framesOfAnyRateCoverTheSameDistance() {
		// One 100 ms step must land where five 20 ms steps land: the exp form
		// is exact in dt, unlike a naive per-frame lerp. Both paths snap at
		// millis 1000 and chase the 0.3 block standing->sneaking drop until
		// millis 1100 — a gap small enough that the speed cap never engages.
		HeartHeightSmoother coarse = new HeartHeightSmoother();
		coarse.update(2.35F, 1000L);
		coarse.update(2.05F, 1100L);

		HeartHeightSmoother fine = new HeartHeightSmoother();
		fine.update(2.35F, 1000L);
		for (long t = 1020L; t <= 1100L; t += 20L) {
			fine.update(2.05F, t);
		}

		assertEquals(coarse.currentY(), fine.currentY(), 1.0E-4F);
		assertTrue(coarse.currentY() > 2.05F && coarse.currentY() < 2.35F);
	}

	@Test
	void theChaseNeverExceedsTheSpeedCap() {
		HeartHeightSmoother smoother = new HeartHeightSmoother();
		smoother.update(1.8F, 0L);

		// A huge jump over a 10 ms frame is capped at 4 blocks/s * 0.01 s.
		float after = smoother.update(100.0F, 10L);
		assertEquals(1.8F + HeartHeightSmoother.MAX_SPEED_BLOCKS_PER_SECOND * 0.01F, after, EPSILON);
	}

	@Test
	void theCapLeavesNormalPoseGlidesUntouched() {
		HeartHeightSmoother smoother = new HeartHeightSmoother();
		smoother.update(2.35F, 0L);

		// Standing -> sneaking over one exact 25 ms frame: a 0.3 block drop
		// must chase faster than the cap, so the glide is purely exponential.
		float k = (float) (1.0 - Math.exp(-0.025 * HeartHeightSmoother.RATE_PER_SECOND));
		float expected = 2.35F + (2.05F - 2.35F) * k;
		assertTrue(Math.abs(expected - 2.35F) < HeartHeightSmoother.MAX_SPEED_BLOCKS_PER_SECOND * 0.025F);
		assertEquals(expected, smoother.update(2.05F, 25L), EPSILON);
	}

	@Test
	void snapToJumpsImmediatelyAndTheNextUpdateGlidesOn() {
		HeartHeightSmoother smoother = new HeartHeightSmoother();
		smoother.update(1.8F, 0L);
		smoother.update(1.8F, 50L);

		smoother.snapTo(2.05F);
		assertEquals(2.05F, smoother.currentY(), EPSILON);
		// After the snap the chase resumes from the snapped height (one exact
		// 50 ms frame, comfortably inside the speed cap).
		float k = (float) (1.0 - Math.exp(-0.05 * HeartHeightSmoother.RATE_PER_SECOND));
		assertEquals(2.05F + (1.5F - 2.05F) * k, smoother.update(1.5F, 100L), EPSILON);
	}

	@Test
	void aZeroOrNegativeDtHoldsTheHeight() {
		HeartHeightSmoother smoother = new HeartHeightSmoother();
		smoother.update(1.8F, 100L);
		smoother.update(2.35F, 100L);

		assertEquals(1.8F, smoother.currentY(), EPSILON);
	}
}
