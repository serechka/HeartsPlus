package com.heartsplus.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

/** Heart family selection priority must mirror the vanilla HUD. */
class HeartTypeTest {

	@Test
	void poisonWinsOverWither() {
		assertSame(HeartType.POISONED, HeartType.forStatus(true, true, false));
	}

	@Test
	void witherWinsOverFreeze() {
		assertSame(HeartType.WITHERED, HeartType.forStatus(false, true, true));
	}

	@Test
	void frozenHeartsWhenOnlyFrozen() {
		assertSame(HeartType.FROZEN, HeartType.forStatus(false, false, true));
	}

	@Test
	void normalHeartsWithoutEffects() {
		assertSame(HeartType.NORMAL, HeartType.forStatus(false, false, false));
	}
}
