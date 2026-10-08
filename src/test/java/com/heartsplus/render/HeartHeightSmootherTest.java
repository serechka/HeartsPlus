package com.heartsplus.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The exponential chase that glides the heart bar along the vanilla name
 * tag attachment point: exact-in-dt lerp, speed cap, snap-on-reset, the
 * config-driven rate/cap parameters and the smoothing-off mode.
 */
class HeartHeightSmootherTest {
	private static final float EPSILON = 1.0E-6F;
	/** The 0.4.8 fixed rate, kept as an explicit test constant: the live value comes from the config. */
	private static final double RATE = 8.0;
	private static final float CAP = 4.0F;

	@Test
	void theFirstUpdateSnapsToTheTarget() {
		HeartHeightSmoother smoother = new HeartHeightSmoother();

		assertEquals(2.35F, smoother.update(2.35F, 1000L, RATE, CAP), EPSILON);
		// A fresh player must not play back an approach from zero.
		assertEquals(1.0F, new HeartHeightSmoother().update(1.0F, 0L, RATE, CAP), EPSILON);
	}

	@Test
	void theChaseFollowsTheExactExponentialForm() {
		HeartHeightSmoother smoother = new HeartHeightSmoother();
		smoother.update(1.8F, 0L, RATE, CAP);

		// One 50 ms frame: k = 1 - exp(-0.05 * 8).
		float k = (float) (1.0 - Math.exp(-0.05 * RATE));
		float expected = 1.8F + (2.35F - 1.8F) * k;
		assertEquals(expected, smoother.update(2.35F, 50L, RATE, CAP), EPSILON);
	}

	@Test
	void theRateArrivesFromTheConfigMapping() {
		// The default Follow Smoothing (50) maps to rate 12: 1.5x of the old
		// fixed 8. The smoother must honour whatever rate the caller passes.
		// A standing->sneaking drop over 25 ms stays inside the speed cap, so
		// the glide is purely exponential.
		HeartHeightSmoother smoother = new HeartHeightSmoother();
		smoother.update(2.35F, 0L, 12.0, CAP);

		float k = (float) (1.0 - Math.exp(-0.025 * 12.0));
		float expected = 2.35F + (2.05F - 2.35F) * k;
		assertEquals(expected, smoother.update(2.05F, 25L, 12.0, CAP), EPSILON);
	}

	@Test
	void framesOfAnyRateCoverTheSameDistance() {
		// One 100 ms step must land where five 20 ms steps land: the exp form
		// is exact in dt, unlike a naive per-frame lerp. Both paths snap at
		// millis 1000 and chase the 0.3 block standing->sneaking drop until
		// millis 1100 — a gap small enough that the speed cap never engages.
		HeartHeightSmoother coarse = new HeartHeightSmoother();
		coarse.update(2.35F, 1000L, RATE, CAP);
		coarse.update(2.05F, 1100L, RATE, CAP);

		HeartHeightSmoother fine = new HeartHeightSmoother();
		fine.update(2.35F, 1000L, RATE, CAP);
		for (long t = 1020L; t <= 1100L; t += 20L) {
			fine.update(2.05F, t, RATE, CAP);
		}

		assertEquals(coarse.currentY(), fine.currentY(), 1.0E-4F);
		assertTrue(coarse.currentY() > 2.05F && coarse.currentY() < 2.35F);
	}

	@Test
	void theChaseNeverExceedsTheSpeedCap() {
		HeartHeightSmoother smoother = new HeartHeightSmoother();
		smoother.update(1.8F, 0L, RATE, CAP);

		// A huge jump over a 10 ms frame is capped at 4 blocks/s * 0.01 s.
		float after = smoother.update(100.0F, 10L, RATE, CAP);
		assertEquals(1.8F + CAP * 0.01F, after, EPSILON);
	}

	@Test
	void theCapGrowsWithTheConfigMapping() {
		// Default Follow Smoothing (50) maps to a cap of 8: double the old
		// fixed 4 - the raise that keeps the bar honest on fast elytra dives.
		HeartHeightSmoother smoother = new HeartHeightSmoother();
		smoother.update(1.8F, 0L, RATE, 8.0F);

		float after = smoother.update(100.0F, 10L, RATE, 8.0F);
		assertEquals(1.8F + 8.0F * 0.01F, after, EPSILON);
	}

	@Test
	void theCapLeavesNormalPoseGlidesUntouched() {
		HeartHeightSmoother smoother = new HeartHeightSmoother();
		smoother.update(2.35F, 0L, RATE, CAP);

		// Standing -> sneaking over one exact 25 ms frame: a 0.3 block drop
		// must chase faster than the cap, so the glide is purely exponential.
		float k = (float) (1.0 - Math.exp(-0.025 * RATE));
		float expected = 2.35F + (2.05F - 2.35F) * k;
		assertTrue(Math.abs(expected - 2.35F) < CAP * 0.025F);
		assertEquals(expected, smoother.update(2.05F, 25L, RATE, CAP), EPSILON);
	}

	@Test
	void snapToJumpsImmediatelyAndTheNextUpdateGlidesOn() {
		HeartHeightSmoother smoother = new HeartHeightSmoother();
		smoother.update(1.8F, 0L, RATE, CAP);
		smoother.update(1.8F, 50L, RATE, CAP);

		smoother.snapTo(2.05F);
		assertEquals(2.05F, smoother.currentY(), EPSILON);
		// After the snap the chase resumes from the snapped height (one exact
		// 50 ms frame, comfortably inside the speed cap).
		float k = (float) (1.0 - Math.exp(-0.05 * RATE));
		assertEquals(2.05F + (1.5F - 2.05F) * k, smoother.update(1.5F, 100L, RATE, CAP), EPSILON);
	}

	@Test
	void aZeroOrNegativeDtHoldsTheHeight() {
		HeartHeightSmoother smoother = new HeartHeightSmoother();
		smoother.update(1.8F, 100L, RATE, CAP);
		smoother.update(2.35F, 100L, RATE, CAP);

		assertEquals(1.8F, smoother.currentY(), EPSILON);
	}

	/**
	 * Smoothing off (rate 0, Follow Smoothing slider at zero): the update
	 * returns the target immediately, every frame.
	 */
	@Test
	void aZeroRateReturnsTheTargetImmediately() {
		HeartHeightSmoother smoother = new HeartHeightSmoother();

		assertEquals(2.35F, smoother.update(2.35F, 0L, 0.0, CAP), EPSILON);
		assertEquals(1.5F, smoother.update(1.5F, 50L, 0.0, CAP), EPSILON);
		assertEquals(1.5F, smoother.currentY(), EPSILON);
	}

	/**
	 * The owner's probe: the target flipping N times within one frame (shift
	 * tapped 20 times a second while the slider is very smooth) must leave no
	 * accumulating lag - the clock advances once per timestamp, so the frame
	 * lands exactly where a single step towards the frame's first target
	 * lands, and the next frame chases on from there with no debt.
	 */
	@Test
	void retargetingManyTimesPerFrameLeavesNoOwedLag() {
		HeartHeightSmoother churned = new HeartHeightSmoother();
		churned.update(2.05F, 0L, RATE, CAP);
		// Twenty retargets inside one frame (all at millis 50): only the
		// first advances the clock, the rest hold the height.
		for (int i = 0; i < 20; i++) {
			churned.update(i % 2 == 0 ? 2.35F : 2.05F, 50L, RATE, CAP);
		}
		float k = (float) (1.0 - Math.exp(-0.05 * RATE));
		float expected = 2.05F + (2.35F - 2.05F) * k;
		assertEquals(expected, churned.currentY(), 1.0E-4F);

		// Identical to a single update towards that first target.
		HeartHeightSmoother plain = new HeartHeightSmoother();
		plain.update(2.05F, 0L, RATE, CAP);
		plain.update(2.35F, 50L, RATE, CAP);
		assertEquals(plain.currentY(), churned.currentY(), 1.0E-4F);

		// The next frame chases on from there, again without debt, even
		// though the churned frame ended on the opposite target.
		plain.update(2.05F, 100L, RATE, CAP);
		churned.update(2.05F, 100L, RATE, CAP);
		assertEquals(plain.currentY(), churned.currentY(), 1.0E-4F);
		assertTrue(churned.currentY() > 2.05F && churned.currentY() < 2.35F);
	}
}
