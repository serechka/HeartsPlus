package com.heartsplus.render;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The vanilla name tag pass rule (plus the invisible one) and the submit order that keeps the passes stable. */
class HeartPassTest {

	@Test
	void sneakingPlayerGetsOnlyTheNormalPass() {
		// EntityRenderer passes !isDiscrete as the nametag's see-through flag;
		// the flag's else branch draws the single depth-tested pass. The
		// showBehindBlocks toggle never adds the see-through copy to a
		// sneaking player (owner decision, 0.4.5).
		assertEquals(List.of(HeartPass.NORMAL), HeartPass.passesFor(true, false, true));
		assertEquals(List.of(HeartPass.NORMAL), HeartPass.passesFor(true, false, false));
	}

	@Test
	void showBehindBlocksAddsTheSeeThroughCopy() {
		assertEquals(List.of(HeartPass.NORMAL, HeartPass.SEE_THROUGH), HeartPass.passesFor(false, false, true));
	}

	@Test
	void withoutShowBehindBlocksOnlyTheNormalPassIsDrawn() {
		assertEquals(List.of(HeartPass.NORMAL), HeartPass.passesFor(false, false, false));
	}

	/**
	 * The full (sneaking, invisible, showBehindBlocks) table: an invisible
	 * player is always drawn with the single depth-tested pass, like a
	 * sneaking one — the see-through copy would betray their position
	 * through walls (owner decision, 0.4.8). Only a visible, non-sneaking
	 * player with the toggle on gets both passes.
	 */
	@Test
	void theFullPassTable() {
		boolean[] both = {false, true};
		for (boolean sneaking : both) {
			for (boolean invisible : both) {
				for (boolean showBehindBlocks : both) {
					List<HeartPass> expected = sneaking || invisible || !showBehindBlocks
							? List.of(HeartPass.NORMAL)
							: List.of(HeartPass.NORMAL, HeartPass.SEE_THROUGH);
					assertEquals(expected, HeartPass.passesFor(sneaking, invisible, showBehindBlocks),
							"sneaking=" + sneaking + " invisible=" + invisible + " showBehindBlocks=" + showBehindBlocks);
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
}
