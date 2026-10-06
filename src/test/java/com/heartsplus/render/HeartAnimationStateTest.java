package com.heartsplus.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vanilla Hud.extractPlayerHealth as a state machine: the blink window and
 * its square-wave frames, the lagging displayHealth copy and its one-second
 * catch-up, and the once-per-tick guard.
 */
class HeartAnimationStateTest {
	private static final long T0 = 50_000L;

	/** Vanilla frame formula for a blink window ending at {@code blinkEndTick}. */
	private static boolean blinkFrame(int tick, int blinkEndTick) {
		return blinkEndTick > tick && (blinkEndTick - tick) / 3 % 2 == 1;
	}

	private static HeartAnimationState atFullHealth(int tick, long millis) {
		HeartAnimationState state = new HeartAnimationState();
		state.tick(20, tick, millis, false);
		return state;
	}

	@Test
	void firstUpdateSnapsDisplayHealthWithoutBlinking() {
		HeartAnimationState state = atFullHealth(100, T0);

		assertEquals(20, state.displayHealth());
		assertFalse(state.isBlinking());
	}

	@Test
	void damageOpensAWindowOfTwentyTicks() {
		HeartAnimationState state = atFullHealth(100, T0);
		// Drop 20 -> 6 half-hearts at tick 101: vanilla arms the window to tick 121.
		state.tick(6, 101, T0 + 50, true);

		// The damage tick itself samples the window as it was before the drop.
		assertFalse(state.isBlinking());
		state.tick(6, 102, T0 + 50, false);
		// (121 - 102) / 3 == 6, even → the first two window ticks are still off-frames.
		assertFalse(state.isBlinking());
		state.tick(6, 104, T0 + 50, false);
		// (121 - 104) / 3 == 5, odd → the first on-phase.
		assertTrue(state.isBlinking());
	}

	@Test
	void blinkFramesFollowTheVanillaSquareWave() {
		HeartAnimationState state = atFullHealth(100, T0);
		// Drop at tick 101 → window ends at tick 121; on-phases where (121 - tick) / 3 is odd.
		state.tick(6, 101, T0 + 50, true);

		for (int tick = 102; tick <= 120; tick++) {
			state.tick(6, tick, T0 + 50, false);
			assertEquals(blinkFrame(tick, 121), state.isBlinking(), "tick " + tick);
		}
		// Beyond the window: never blinking again.
		state.tick(6, 121, T0 + 50, false);
		assertFalse(state.isBlinking());
		state.tick(6, 122, T0 + 50, false);
		assertFalse(state.isBlinking());
	}

	@Test
	void theSquareWaveHasThreeOnTicksThenThreeOffTicks() {
		// Sanity for the formula itself: window end 121 gives on-phases 104..106, 110..112, 116..118.
		int blinkEndTick = 121;
		int[] onFrames = {104, 105, 106, 110, 111, 112, 116, 117, 118};
		for (int tick = 102; tick <= 120; tick++) {
			boolean expected = false;
			for (int onFrame : onFrames) {
				expected |= tick == onFrame;
			}
			assertEquals(expected, blinkFrame(tick, blinkEndTick), "tick " + tick);
		}
	}

	@Test
	void healingOpensAShorterWindowOfTenTicks() {
		HeartAnimationState state = atFullHealth(100, T0);
		state.tick(6, 101, T0 + 50, false);
		// Recover to 10 half-hearts while invulnerable: vanilla arms the 10-tick recovery pop.
		state.tick(10, 102, T0 + 100, true);

		state.tick(10, 103, T0 + 100, false);
		assertEquals(blinkFrame(103, 112), state.isBlinking());
		assertTrue(state.isBlinking()); // (112 - 103) / 3 == 3, odd → on
		state.tick(10, 112, T0 + 100, false);
		assertFalse(state.isBlinking()); // window over
	}

	@Test
	void displayHealthTrailsForOneSecondThenSnaps() {
		HeartAnimationState state = atFullHealth(0, T0);
		// Drop 20 -> 6 at tick 101: displayHealth must keep the old value while the clock runs.
		state.tick(6, 101, T0 + 50, true);
		assertEquals(20, state.displayHealth());

		// Exactly 1000 ms after the change: vanilla still keeps the old value (strictly greater).
		state.tick(6, 111, T0 + 50 + 1000, false);
		assertEquals(20, state.displayHealth());

		// Past one second: the copy snaps to the current health.
		state.tick(6, 112, T0 + 50 + 1001, false);
		assertEquals(6, state.displayHealth());
	}

	@Test
	void healthChangesWithoutInvulnerabilityNeverBlink() {
		HeartAnimationState state = atFullHealth(100, T0);
		state.tick(6, 101, T0 + 50, false);
		state.tick(6, 105, T0 + 250, false);

		assertFalse(state.isBlinking());
		// But the display copy still catches up on the vanilla clock.
		state.tick(6, 130, T0 + 1051, false);
		assertEquals(6, state.displayHealth());
	}

	@Test
	void repeatedTicksWithinTheSameGameTickAreIgnored() {
		HeartAnimationState state = atFullHealth(100, T0);
		// Extraction runs every frame; two updates within tick 101 must behave like one.
		state.tick(6, 101, T0 + 50, true);
		state.tick(2, 101, T0 + 60, true);

		// The second update was swallowed: the window still matches the first drop
		// (blinkEnd 121, on-phase at 104), and displayHealth still trails at the
		// pre-damage value instead of snapping to the re-reported 2.
		state.tick(6, 104, T0 + 50, false);
		assertTrue(state.isBlinking());
		assertEquals(20, state.displayHealth());
	}
}
