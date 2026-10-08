package com.heartsplus.render;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The vanilla name tag pass rule (plus the invisible one) and the submit order that keeps the passes stable. */
class HeartPassTest {

	@Test
	void sneakingPlayerGetsOnlyTheNormalPass() {
		// EntityRenderer passes !isDiscrete as the nametag's seeThrough flag;
		// the flag's else branch draws the single depth-tested pass. The wall
		// opacity never adds the see-through copy to a sneaking player
		// (owner decision, 0.4.5).
		assertEquals(List.of(HeartPass.NORMAL), HeartPass.passesFor(true, false, 100));
		assertEquals(List.of(HeartPass.NORMAL), HeartPass.passesFor(true, false, 0));
	}

	@Test
	void positiveWallOpacityAddsTheSeeThroughCopy() {
		assertEquals(List.of(HeartPass.NORMAL, HeartPass.SEE_THROUGH), HeartPass.passesFor(false, false, 100));
		assertEquals(List.of(HeartPass.NORMAL, HeartPass.SEE_THROUGH), HeartPass.passesFor(false, false, 1));
	}

	@Test
	void zeroWallOpacityDrawsOnlyTheNormalPass() {
		// Walls hide the hearts completely: the see-through pass is dropped.
		assertEquals(List.of(HeartPass.NORMAL), HeartPass.passesFor(false, false, 0));
	}

	/**
	 * The full (sneaking, invisible, wallOpacity) table: an invisible
	 * player is always drawn with the single depth-tested pass, like a
	 * sneaking one — the see-through copy would betray their position
	 * through walls (owner decision, 0.4.8). Only a visible, non-sneaking
	 * player with a positive opacity gets both passes.
	 */
	@Test
	void theFullPassTable() {
		boolean[] both = {false, true};
		int[] opacities = {0, 1, 50, 100};
		for (boolean sneaking : both) {
			for (boolean invisible : both) {
				for (int wallOpacity : opacities) {
					List<HeartPass> expected = sneaking || invisible || wallOpacity <= 0
							? List.of(HeartPass.NORMAL)
							: List.of(HeartPass.NORMAL, HeartPass.SEE_THROUGH);
					assertEquals(expected, HeartPass.passesFor(sneaking, invisible, wallOpacity),
							"sneaking=" + sneaking + " invisible=" + invisible + " wallOpacity=" + wallOpacity);
				}
			}
		}
	}

	@Test
	void seeThroughLayersRenderInTheirPainterOrderBelowTheBrightPass() {
		for (int layer = 0; layer < HeartPass.SEE_THROUGH_LAYER_COUNT; layer++) {
			assertEquals(layer, HeartPass.SEE_THROUGH.renderOrder(layer));
			// The bright pass must beat every see-through layer regardless of
			// which z layer it belongs to: it is the only pass that survives
			// the depth test in direct sight, so it must draw last.
			assertEquals(HeartPass.SEE_THROUGH_LAYER_COUNT, HeartPass.NORMAL.renderOrder(layer));
		}
	}

	/**
	 * The slider maps percent to vertex alpha: 100 percent fully bright, the
	 * 0.4.8 default 50 percent matches the old 0x80 dimming, 0 drops the pass
	 * (see the pass table), out-of-range values clamp.
	 */
	@Test
	void theOpacitySliderMapsToVertexAlpha() {
		assertEquals(255, HeartPass.seeThroughAlpha(100));
		assertEquals(128, HeartPass.seeThroughAlpha(50));
		assertEquals(0, HeartPass.seeThroughAlpha(0));
		assertEquals(26, HeartPass.seeThroughAlpha(10));
		assertEquals(0, HeartPass.seeThroughAlpha(-5));
		assertEquals(255, HeartPass.seeThroughAlpha(150));
	}
}
