package com.heartsplus.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vanilla Hud.extractPlayerHealth blink-window state machine, 0.4.7 shape:
 * the bar value is instant, so the window only drives the flash overlays —
 * the square-wave frames, the damage overlay over the lost slots, and the
 * once-per-tick guard. Healing opens the short pop window with no heart
 * overlay (vanilla flashes only the containers on acquired slots). Since
 * 0.4.8 a backwards tick (recreated entity under the same UUID) resets the
 * state instead of letting the stale window thrash.
 */
class HeartAnimationStateTest {

	/** Vanilla frame formula for a blink window ending at {@code blinkEndTick}. */
	private static boolean blinkFrame(int tick, int blinkEndTick) {
		return blinkEndTick > tick && (blinkEndTick - tick) / 3 % 2 == 1;
	}

	private static HeartAnimationState atFullHealth(int tick) {
		HeartAnimationState state = new HeartAnimationState();
		state.tick(20, tick, false, true);
		return state;
	}

	@Test
	void firstUpdateShowsNoBlinkingAndNoOverlay() {
		HeartAnimationState state = atFullHealth(100);

		assertFalse(state.isBlinking());
		assertEquals(0, state.blinkOverlayStart());
		assertEquals(0, state.blinkOverlayEnd());
	}

	@Test
	void damageOpensAWindowOfTwentyTicks() {
		HeartAnimationState state = atFullHealth(100);
		// Drop 20 -> 6 half-hearts at tick 101: vanilla arms the window to tick 121.
		state.tick(6, 101, true, true);

		// The damage tick itself samples the window as it was before the drop.
		assertFalse(state.isBlinking());
		state.tick(6, 102, false, true);
		// (121 - 102) / 3 == 6, even → the first two window ticks are still off-frames.
		assertFalse(state.isBlinking());
		state.tick(6, 104, false, true);
		// (121 - 104) / 3 == 5, odd → the first on-phase.
		assertTrue(state.isBlinking());
	}

	@Test
	void damageOverlayCoversExactlyTheLostSlots() {
		HeartAnimationState state = atFullHealth(100);
		state.tick(6, 101, true, true);

		// The lost half-hearts 6..19 carry the blinking sprites; the kept ones
		// are already covered by the instantly drawn normal hearts.
		assertEquals(6, state.blinkOverlayStart());
		assertEquals(20, state.blinkOverlayEnd());

		// A range topping on an odd half-heart ends with the half variant.
		HeartAnimationState oddTop = atFullHealth(100);
		oddTop.tick(13, 101, true, true);
		oddTop.tick(6, 103, true, true);
		assertEquals(6, oddTop.blinkOverlayStart());
		assertEquals(13, oddTop.blinkOverlayEnd());
	}

	@Test
	void blinkFramesFollowTheVanillaSquareWave() {
		HeartAnimationState state = atFullHealth(100);
		// Drop at tick 101 → window ends at tick 121; on-phases where (121 - tick) / 3 is odd.
		state.tick(6, 101, true, true);

		for (int tick = 102; tick <= 120; tick++) {
			state.tick(6, tick, false, true);
			assertEquals(blinkFrame(tick, 121), state.isBlinking(), "tick " + tick);
		}
		// Beyond the window: never blinking again.
		state.tick(6, 121, false, true);
		assertFalse(state.isBlinking());
		state.tick(6, 122, false, true);
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
	void healingOpensAShorterWindowWithoutAHeartOverlay() {
		HeartAnimationState state = atFullHealth(100);
		state.tick(6, 101, false, true);
		// Recover to 10 half-hearts while invulnerable: vanilla arms the 10-tick recovery pop.
		state.tick(10, 102, true, true);

		state.tick(10, 103, false, true);
		assertEquals(blinkFrame(103, 112), state.isBlinking());
		assertTrue(state.isBlinking()); // (112 - 103) / 3 == 3, odd → on
		state.tick(10, 112, false, true);
		assertFalse(state.isBlinking()); // window over
		// Acquired slots get no blinking heart: vanilla draws the blinking
		// container variant plus the normal heart there (Hud.extractHearts).
		assertEquals(0, state.blinkOverlayStart());
		assertEquals(0, state.blinkOverlayEnd());
	}

	@Test
	void aLiveDamageWindowRearmsFromTheLatestValue() {
		HeartAnimationState state = atFullHealth(100);
		state.tick(6, 101, true, true); // window [6, 20), ends at 121
		// Damaged again two ticks later: the range counts from the latest value.
		state.tick(2, 103, true, true);

		assertEquals(2, state.blinkOverlayStart());
		assertEquals(6, state.blinkOverlayEnd());
		// The re-armed window ends at 123: on-phase where (123 - tick) / 3 is
		// odd. Sampled forward only — a backwards tick now means a recreated
		// entity and resets the state, so the old rewind-to-120 probe is gone.
		state.tick(2, 106, false, true);
		assertTrue(state.isBlinking()); // (123 - 106) / 3 == 5, odd → on
		state.tick(2, 122, false, true);
		assertFalse(state.isBlinking()); // (123 - 122) / 3 == 0, even → off
	}

	@Test
	void healthChangesWithoutInvulnerabilityNeverBlink() {
		HeartAnimationState state = atFullHealth(100);
		state.tick(6, 101, false, true);
		state.tick(6, 105, false, true);

		assertFalse(state.isBlinking());
		assertEquals(0, state.blinkOverlayStart());
		assertEquals(0, state.blinkOverlayEnd());
	}

	@Test
	void repeatedTicksWithinTheSameGameTickAreIgnored() {
		HeartAnimationState state = atFullHealth(100);
		// Extraction runs every frame; two updates within tick 101 must behave like one.
		state.tick(6, 101, true, true);
		state.tick(2, 101, true, true);

		// The second update was swallowed: the window still matches the first
		// drop (range [6, 20), on-phase at 104), not the re-reported 2.
		state.tick(6, 104, false, true);
		assertTrue(state.isBlinking());
		assertEquals(6, state.blinkOverlayStart());
		assertEquals(20, state.blinkOverlayEnd());
	}

	@Test
	void animationOffTracksHealthWithoutArmingWindows() {
		HeartAnimationState state = atFullHealth(100);
		state.tick(6, 101, true, false);
		state.tick(6, 105, false, false);

		assertFalse(state.isBlinking());
		assertEquals(0, state.blinkOverlayStart());
		assertEquals(0, state.blinkOverlayEnd());

		// lastHealth kept tracking, so re-enabling only arms the new change.
		state.tick(2, 106, true, true);
		assertEquals(2, state.blinkOverlayStart());
		assertEquals(6, state.blinkOverlayEnd());
	}

	@Test
	void aBackwardTickResetsTheStateAndArmsNothing() {
		HeartAnimationState state = atFullHealth(1000);
		state.tick(6, 1001, true, true); // window [6, 20), ends at tick 1021

		// The entity behind the UUID is recreated (world/server switch, chunk
		// reload): the tick counter restarts from ~0 while the old window is
		// still armed, and spawn protection can report invulnerable with a
		// health jump 6 -> 20. The reset must swallow that call entirely.
		assertTrue(state.tick(20, 5, true, true));
		assertFalse(state.isBlinking());
		assertEquals(0, state.blinkOverlayStart());
		assertEquals(0, state.blinkOverlayEnd());

		// The stale window must never flash again: its square wave over the
		// restarted counter is exactly the reported white flicker.
		state.tick(20, 104, false, true);
		assertFalse(state.isBlinking());
		assertEquals(0, state.blinkOverlayStart());
		assertEquals(0, state.blinkOverlayEnd());
	}

	@Test
	void afterTheResetNewChangesArmNormally() {
		HeartAnimationState state = atFullHealth(1000);
		state.tick(20, 1001, false, true);
		assertTrue(state.tick(20, 3, false, true)); // backward tick re-baselines lastHealth to 20

		// Damage right after the reset arms a fresh window from the new counter.
		state.tick(6, 4, true, true);
		assertEquals(6, state.blinkOverlayStart());
		assertEquals(20, state.blinkOverlayEnd());
		state.tick(6, 7, false, true);
		assertTrue(state.isBlinking()); // (24 - 7) / 3 == 5, odd → on
	}

	@Test
	void theSameTickRightAfterTheResetIsStillSwallowed() {
		HeartAnimationState state = atFullHealth(1000);
		assertTrue(state.tick(20, 3, false, true));
		// Extraction repeats within the restarted tick: nothing may arm.
		assertFalse(state.tick(2, 3, true, true));
		assertFalse(state.isBlinking());
		assertEquals(0, state.blinkOverlayStart());
		assertEquals(0, state.blinkOverlayEnd());
	}
}
