package com.heartsplus.render;

import java.util.List;

/**
 * The two vanilla name tag passes a heart bar is submitted with, the
 * (sneaking, showBehindBlocks) rule that picks between them, and the submit
 * order that keeps their blending deterministic. Deliberately holds no
 * Minecraft classes so it is directly unit-testable.
 *
 * <p>The pass set mirrors EntityRenderer.renderLabelIfPresent: it passes
 * {@code !sneaking} as the label's notSneaking flag, and
 * LabelCommandRenderer.Commands.add draws the flag's else branch — a
 * sneaking player's name — as the single depth-tested NORMAL pass. The
 * see-through copy is only ever added for players who are not sneaking: the
 * sneak rule is stronger than the showBehindBlocks toggle (owner decision,
 * 0.4.5).</p>
 */
public enum HeartPass {
	/** Depth-tested bright pass (vanilla text render layer, nametag +2 emission). */
	NORMAL,
	/** Depth-blind dimmed copy (vanilla text-see-through render layer, 0x80FFFFFF). */
	SEE_THROUGH;

	/** The bar's see-through z layers: containers, absorption, blink overlay, health. */
	public static final int SEE_THROUGH_LAYER_COUNT = 4;

	/**
	 * Submit order inside the frame's OrderedRenderCommandQueueImpl: every
	 * phase of order N executes before every phase of order N+1, so each
	 * see-through layer gets its own order and the bright pass sits above all
	 * of them. The see-through layers must never be re-sorted against each
	 * other — they are depth-blind, so only the draw order keeps the HUD's
	 * painter order — and the bright pass must always beat them, which vanilla
	 * otherwise guarantees by drawing its seethroughLabels list before
	 * normalLabels (LabelCommandRenderer.render).
	 */
	public int renderOrder(int seeThroughLayerIndex) {
		return this == NORMAL ? SEE_THROUGH_LAYER_COUNT : seeThroughLayerIndex;
	}

	/** Pass set for a (sneaking, showBehindBlocks) pair — the vanilla name tag rule. */
	public static List<HeartPass> passesFor(boolean sneaking, boolean showBehindBlocks) {
		if (sneaking) {
			return List.of(NORMAL);
		}
		return showBehindBlocks ? List.of(NORMAL, SEE_THROUGH) : List.of(NORMAL);
	}
}
