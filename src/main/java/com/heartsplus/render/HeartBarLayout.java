package com.heartsplus.render;

/**
 * Pure geometry of a heart bar, computed from raw health values. Deliberately
 * holds no Minecraft classes so it is directly unit-testable. Row 0 is the
 * bottom row (closest to the nameplate); extra rows stack upward like the
 * vanilla HUD.
 */
public final class HeartBarLayout {
	/** One heart cell in GUI pixels, matching the vanilla HUD sprite. */
	public static final float HEART_SIZE = 9.0F;
	/** Horizontal distance between heart origins, one pixel tighter than the cell. */
	public static final float HEART_SPACING = 8.0F;
	private static final int HEARTS_PER_ROW = 10;
	private static final int ROW_SPACING_BASE = 10;
	private static final int MIN_ROW_SPACING = 3;
	/** Damage-blink cadence: the highlight shows on every other beat of this length. */
	private static final int BLINK_INTERVAL_TICKS = 3;

	private final int heartsRed;
	private final int heartsNormal;
	private final int heartsTotal;
	private final boolean lastRedHalf;
	private final boolean lastYellowHalf;
	private final boolean lastBlinkHalf;
	private final int heartsBlink;
	private final int rowOffset;
	private final float startX;

	private HeartBarLayout(int healthRed, int maxHealth, int healthYellow, int blinkHalves) {
		this.heartsRed = ceil(healthRed / 2.0F);
		this.lastRedHalf = (healthRed & 1) == 1;
		this.heartsNormal = ceil(maxHealth / 2.0F);
		int heartsYellow = ceil(healthYellow / 2.0F);
		this.lastYellowHalf = (healthYellow & 1) == 1;
		this.heartsTotal = this.heartsNormal + heartsYellow;

		this.heartsBlink = ceil(blinkHalves / 2.0F);
		this.lastBlinkHalf = (blinkHalves & 1) == 1;

		int rowsTotal = (this.heartsTotal + HEARTS_PER_ROW - 1) / HEARTS_PER_ROW;
		// Vanilla-like row compression: rows slide closer together as the bar
		// grows taller, down to a minimum overlap step.
		this.rowOffset = Math.max(ROW_SPACING_BASE - (rowsTotal - 2), MIN_ROW_SPACING);
		float rowWidth = Math.min(this.heartsTotal, HEARTS_PER_ROW) * HEART_SPACING + 1.0F;
		this.startX = -rowWidth / 2.0F;
	}

	/** Builds the bar from health values in half-hearts (vanilla ceil semantics). */
	public static HeartBarLayout of(float health, float maxHealth, float absorption, float blinkOldHealth) {
		return new HeartBarLayout(ceil(health), ceil(maxHealth), ceil(absorption), ceil(blinkOldHealth));
	}

	/** Rounds up like vanilla {@code Mth.ceil} without dragging Minecraft onto the test classpath. */
	private static int ceil(float value) {
		return (int) Math.ceil(value);
	}

	public int heartsRed() {
		return this.heartsRed;
	}

	public int heartsNormal() {
		return this.heartsNormal;
	}

	public int heartsTotal() {
		return this.heartsTotal;
	}

	/** Left edge of a heart's cell in GUI pixels, centred on the head. */
	public float x(int heart) {
		return this.startX + heart % HEARTS_PER_ROW * HEART_SPACING;
	}

	/** Top edge of a heart's quad in GUI pixels; rows grow upwards. */
	public float yTop(int heart) {
		return -(heart / HEARTS_PER_ROW * this.rowOffset) - HEART_SIZE;
	}

	public boolean isRedHalf(int heart) {
		return heart == this.heartsRed - 1 && this.lastRedHalf;
	}

	public boolean hasRedHalf() {
		return this.heartsRed > 0 && this.lastRedHalf;
	}

	public boolean isYellowHalf(int heart) {
		return heart == this.heartsTotal - 1 && this.lastYellowHalf && this.heartsTotal > this.heartsNormal;
	}

	public boolean hasYellowHalf() {
		return this.heartsTotal > this.heartsNormal && this.lastYellowHalf;
	}

	public boolean lastBlinkHalf() {
		return this.lastBlinkHalf;
	}

	/**
	 * Hearts between the current and pre-drop health blink for a short window
	 * after damage, alternating on a fixed cadence like the vanilla HUD.
	 * Returns the exclusive upper heart index, or {@link #heartsRed()} when not
	 * in the blink window / on the "off" beat of the cadence.
	 */
	public int blinkUpperBound(int nowTick, int blinkEndTick, float blinkOldHealth, float currentHealth) {
		if (nowTick >= blinkEndTick
				|| blinkOldHealth <= currentHealth
				|| nowTick / BLINK_INTERVAL_TICKS % 2 != 0) {
			return this.heartsRed;
		}
		return Math.min(this.heartsBlink, this.heartsNormal);
	}
}
