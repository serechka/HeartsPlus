package com.heartsplus.render;

import java.util.List;

/**
 * The two vanilla name tag passes a heart bar is submitted with, the
 * (sneaking, invisible, showBehindBlocks) rule that picks between them, and
 * the submit order that keeps their blending deterministic. Deliberately
 * holds no Minecraft classes so it is directly unit-testable.
 *
 * <p>The pass set mirrors EntityRenderer.renderLabelIfPresent: the label
 * gates its see-through copy on {@code !isSneaking}, so a sneaking player's
 * name — and the heart bar — is the single depth-tested NORMAL pass. The
 * see-through copy is only ever added for players who are neither sneaking
 * nor invisible: both rules are stronger than the showBehindBlocks toggle
 * (owner decisions, 0.4.5 and 0.4.8 — the see-through copy would betray an
 * invisible player's position through walls).</p>
 */
public enum HeartPass {
	/** Depth-tested bright pass (vanilla text render layer, plain light coords). */
	NORMAL,
	/** Depth-blind dimmed copy (vanilla text-see-through render layer, 0x20FFFFFF). */
	SEE_THROUGH;

	/** The bar's see-through z layers: containers, absorption, blink overlay, health. */
	public static final int SEE_THROUGH_LAYER_COUNT = 4;

	/**
	 * Submit order: every see-through layer sits below the bright pass. The
	 * see-through layers must never be re-sorted against each other — they
	 * are depth-blind, so only the draw order keeps the HUD's painter order —
	 * and the bright pass must always beat them. This era has no submit
	 * orders to route the passes to, so the order is realized by submission
	 * (HeartsAboveHeadRenderer): the entity VertexConsumerProvider.Immediate
	 * draws each text batch the moment a different render layer is requested,
	 * which makes the draw order the submission order of two sweeps. (The
	 * 26.x line pins the same order with SubmitNodeStorage orders instead.)
	 */
	public int renderOrder(int seeThroughLayerIndex) {
		return this == NORMAL ? SEE_THROUGH_LAYER_COUNT : seeThroughLayerIndex;
	}

	/**
	 * Pass set for a (sneaking, invisible, showBehindBlocks) triple — the
	 * vanilla name tag rule plus the invisible one: an invisible player is
	 * drawn with the single depth-tested pass like a sneaking one, so their
	 * hearts hide behind walls no matter what the toggle says.
	 */
	public static List<HeartPass> passesFor(boolean sneaking, boolean invisible, boolean showBehindBlocks) {
		if (sneaking || invisible) {
			return List.of(NORMAL);
		}
		return showBehindBlocks ? List.of(NORMAL, SEE_THROUGH) : List.of(NORMAL);
	}
}
