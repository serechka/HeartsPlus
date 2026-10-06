package com.heartsplus.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Heart bar geometry: heart counts, halves, row wrapping and the blink window. */
class HeartBarLayoutTest {
	private static final float EPSILON = 1e-4F;

	@Test
	void fullVanillaHealthDrawsTenFullHeartsWithoutHalves() {
		HeartBarLayout layout = HeartBarLayout.of(20.0F, 20.0F, 0.0F, 0.0F);

		assertEquals(10, layout.heartsRed());
		assertEquals(10, layout.heartsNormal());
		assertEquals(10, layout.heartsTotal());
		assertFalse(layout.hasRedHalf());
		assertFalse(layout.hasYellowHalf());
	}

	@Test
	void oddHealthMarksTheLastHeartAsHalf() {
		HeartBarLayout layout = HeartBarLayout.of(13.0F, 20.0F, 0.0F, 0.0F);

		assertEquals(7, layout.heartsRed());
		assertTrue(layout.hasRedHalf());
		assertTrue(layout.isRedHalf(6));
		assertFalse(layout.isRedHalf(5));
	}

	@Test
	void absorptionExtendsTheBarBeyondMaxHealth() {
		HeartBarLayout even = HeartBarLayout.of(20.0F, 20.0F, 4.0F, 0.0F);
		assertEquals(12, even.heartsTotal());
		assertEquals(10, even.heartsNormal());
		assertFalse(even.hasYellowHalf());

		HeartBarLayout odd = HeartBarLayout.of(20.0F, 20.0F, 3.0F, 0.0F);
		assertTrue(odd.hasYellowHalf());
		assertTrue(odd.isYellowHalf(11));
	}

	@Test
	void longBarsWrapIntoRowsOfTenGrowingUpward() {
		HeartBarLayout layout = HeartBarLayout.of(40.0F, 40.0F, 0.0F, 0.0F);

		assertEquals(20, layout.heartsTotal());
		// The first heart of row 1 sits one row spacing above row 0.
		assertEquals(layout.yTop(0) - 10.0F, layout.yTop(10), EPSILON);
		// The bar is visually centred: the first cell mirrors the last cell around x = 0.
		assertEquals(-layout.x(0), layout.x(9) + HeartBarLayout.HEART_SIZE, EPSILON);
	}

	@Test
	void zeroHealthProducesAnEmptyBar() {
		assertEquals(0, HeartBarLayout.of(0.0F, 0.0F, 0.0F, 0.0F).heartsTotal());
	}

	@Test
	void blinkWindowHighlightsUpToTheOldHealthOnTheOnBeat() {
		HeartBarLayout layout = HeartBarLayout.of(6.0F, 20.0F, 0.0F, 20.0F);

		// Tick 6 is inside the window (end 10) and on the "on" beat (6/3 % 2 == 0).
		assertEquals(10, layout.blinkUpperBound(6, 10, 20.0F, 6.0F));
		// Off beat: the highlight collapses to the red heart count.
		assertEquals(3, layout.blinkUpperBound(3, 10, 20.0F, 6.0F));
		// Window over: no highlight.
		assertEquals(3, layout.blinkUpperBound(10, 10, 20.0F, 6.0F));
		// Health recovered above the old value: no highlight.
		assertEquals(3, layout.blinkUpperBound(6, 10, 6.0F, 8.0F));
	}
}
