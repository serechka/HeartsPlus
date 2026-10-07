package com.heartsplus.render;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The vanilla name tag pass rule and the submit order that keeps the passes stable. */
class HeartPassTest {

	@Test
	void sneakingPlayerGetsOnlyTheNormalPass() {
		// EntityRenderer passes !isDiscrete as the nametag's see-through flag;
		// the flag's else branch draws the single depth-tested pass. The
		// showBehindBlocks toggle never adds the see-through copy to a
		// sneaking player (owner decision, 0.4.5).
		assertEquals(List.of(HeartPass.NORMAL), HeartPass.passesFor(true, true));
		assertEquals(List.of(HeartPass.NORMAL), HeartPass.passesFor(true, false));
	}

	@Test
	void showBehindBlocksAddsTheSeeThroughCopy() {
		assertEquals(List.of(HeartPass.NORMAL, HeartPass.SEE_THROUGH), HeartPass.passesFor(false, true));
	}

	@Test
	void withoutShowBehindBlocksOnlyTheNormalPassIsDrawn() {
		assertEquals(List.of(HeartPass.NORMAL), HeartPass.passesFor(false, false));
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
