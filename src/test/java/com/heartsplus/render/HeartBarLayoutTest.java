package com.heartsplus.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Heart bar slot model: container counts, halves, row wrapping, shake and the Regeneration bounce. */
class HeartBarLayoutTest {
	private static final float EPSILON = 1e-4F;

	@Test
	void fullVanillaHealthDrawsTenFullHeartsWithoutHalves() {
		HeartBarLayout layout = HeartBarLayout.of(20.0F, 20.0F, 0.0F, 20.0F);

		assertEquals(10, layout.slots());
		assertEquals(10, layout.healthContainers());
		assertTrue(layout.hasHealthHeart(9));
		assertFalse(layout.hasHealthHeart(10));
		assertFalse(layout.isHealthHalf(9));
	}

	@Test
	void oddHealthMarksTheLastHeartAsHalf() {
		HeartBarLayout layout = HeartBarLayout.of(13.0F, 20.0F, 0.0F, 13.0F);

		assertTrue(layout.hasHealthHeart(6));
		assertTrue(layout.isHealthHalf(6));
		assertFalse(layout.isHealthHalf(5));
		assertFalse(layout.hasHealthHeart(7));
	}

	@Test
	void absorptionExtendsTheBarBeyondMaxHealth() {
		HeartBarLayout even = HeartBarLayout.of(20.0F, 20.0F, 4.0F, 20.0F);
		assertEquals(12, even.slots());
		assertEquals(10, even.healthContainers());
		assertTrue(even.hasAbsorptionHeart(11));
		assertFalse(even.isAbsorptionHalf(11));

		HeartBarLayout odd = HeartBarLayout.of(20.0F, 20.0F, 3.0F, 20.0F);
		assertTrue(odd.hasAbsorptionHeart(11));
		assertTrue(odd.isAbsorptionHalf(11));
		assertFalse(odd.isAbsorptionHalf(10));
	}

	@Test
	void longBarsWrapIntoRowsOfTenGrowingUpward() {
		HeartBarLayout layout = HeartBarLayout.of(40.0F, 40.0F, 0.0F, 40.0F);

		assertEquals(20, layout.slots());
		// The first heart of row 1 sits one row spacing above row 0.
		assertEquals(layout.yTop(0) - 10.0F, layout.yTop(10), EPSILON);
		// The bar is visually centred: the first cell mirrors the last cell around x = 0.
		assertEquals(-layout.x(0), layout.x(9) + HeartBarLayout.HEART_SIZE, EPSILON);
	}

	@Test
	void zeroHealthProducesAnEmptyBar() {
		assertEquals(0, HeartBarLayout.of(0.0F, 0.0F, 0.0F, 0.0F).slots());
	}

	@Test
	void containersFollowTheLargerOfAttributeAndAnimatedHealthCopies() {
		// While displayHealth still trails the pre-damage value, its containers must not collapse.
		assertEquals(10, HeartBarLayout.of(6.0F, 20.0F, 0.0F, 20.0F).healthContainers());
		// A max-health attribute below the current health cannot shrink the bar either.
		assertEquals(7, HeartBarLayout.of(13.0F, 10.0F, 0.0F, 13.0F).healthContainers());
	}

	@Test
	void theBarShakesOnlyAtTwoHeartsOrLess() {
		assertTrue(HeartBarLayout.of(4.0F, 20.0F, 0.0F, 4.0F).shakes());
		assertTrue(HeartBarLayout.of(2.0F, 20.0F, 2.0F, 2.0F).shakes());
		assertFalse(HeartBarLayout.of(6.0F, 20.0F, 0.0F, 6.0F).shakes());
	}

	@Test
	void regenBounceCyclesThroughTheHealthContainersOnly() {
		HeartBarLayout layout = HeartBarLayout.of(20.0F, 20.0F, 0.0F, 20.0F);

		assertEquals(-1, layout.regenBounceSlot(7, false));
		// ceil(20 + 5) == 25 positions, but only slots below the 10 health containers bounce.
		assertEquals(7, layout.regenBounceSlot(7, true));
		assertEquals(-1, layout.regenBounceSlot(12, true));
	}
}
