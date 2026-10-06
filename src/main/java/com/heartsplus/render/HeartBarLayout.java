package com.heartsplus.render;

/**
 * Pure vanilla slot model of a heart bar, computed from raw health values.
 * Deliberately holds no Minecraft classes so it is directly unit-testable.
 * Row 0 is the bottom row (closest to the nameplate); extra rows stack
 * upward like the vanilla HUD. Slot math mirrors vanilla InGameHud.renderHealthBar:
 * slot {@code s} covers the half-hearts {@code s*2} and {@code s*2 + 1},
 * health containers fill every slot below the max-health count and
 * absorption hearts occupy the slots beyond it.
 */
public final class HeartBarLayout {
	/** One heart cell in GUI pixels, matching the vanilla HUD sprite. */
	public static final float HEART_SIZE = 9.0F;
	/** Horizontal distance between heart origins, one pixel tighter than the cell. */
	public static final float HEART_SPACING = 8.0F;
	private static final int HEARTS_PER_ROW = 10;
	private static final int ROW_SPACING_BASE = 10;
	private static final int MIN_ROW_SPACING = 3;
	/** Vanilla low-health threshold: at two full hearts or less the bar starts shaking. */
	private static final int SHAKE_HALF_HEARTS = 4;

	private final int currentHealth;
	private final int displayHealth;
	private final int absorption;
	private final int healthContainers;
	private final int slots;
	private final int rowOffset;
	private final float startX;
	private final float maxHealth;

	private HeartBarLayout(int currentHealth, int attributeMaxHealth, int absorption, int displayHealth) {
		// Vanilla sizes the container count by the larger of the attribute and
		// both animated health copies: while displayHealth still trails the
		// pre-damage value, the containers it covers must not collapse.
		this.maxHealth = Math.max(attributeMaxHealth, Math.max(displayHealth, currentHealth));
		this.currentHealth = currentHealth;
		this.displayHealth = displayHealth;
		this.absorption = absorption;
		this.healthContainers = ceil(this.maxHealth / 2.0F);
		this.slots = this.healthContainers + ceil(absorption / 2.0F);

		int rowsTotal = (this.slots + HEARTS_PER_ROW - 1) / HEARTS_PER_ROW;
		// Vanilla-like row compression: rows slide closer together as the bar
		// grows taller, down to a minimum overlap step.
		this.rowOffset = Math.max(ROW_SPACING_BASE - (rowsTotal - 2), MIN_ROW_SPACING);
		float rowWidth = Math.min(this.slots, HEARTS_PER_ROW) * HEART_SPACING + 1.0F;
		this.startX = -rowWidth / 2.0F;
	}

	/** Builds the bar from health values in half-hearts (vanilla ceil semantics). */
	public static HeartBarLayout of(float health, float maxHealth, float absorption, float displayHealth) {
		return new HeartBarLayout(ceil(health), ceil(maxHealth), ceil(absorption), ceil(displayHealth));
	}

	/** Rounds up like vanilla {@code MathHelper.ceil} without dragging Minecraft onto the test classpath. */
	private static int ceil(float value) {
		return (int) Math.ceil(value);
	}

	/** Every drawn cell of the bar: max-health containers plus absorption containers. */
	public int slots() {
		return this.slots;
	}

	/** Containers of the health rows; absorption slots start at this index. */
	public int healthContainers() {
		return this.healthContainers;
	}

	/** Left edge of a heart's cell in GUI pixels, centred on the head. */
	public float x(int slot) {
		return this.startX + slot % HEARTS_PER_ROW * HEART_SPACING;
	}

	/** Top edge of a heart's quad in GUI pixels; rows grow upwards. */
	public float yTop(int slot) {
		return -(slot / HEARTS_PER_ROW * this.rowOffset) - HEART_SIZE;
	}

	/** True beyond the max-health containers, where absorption hearts live. */
	public boolean isAbsorptionSlot(int slot) {
		return slot >= this.healthContainers;
	}

	/** First half-heart index covered by the slot's absorption heart. */
	public int absorptionHalves(int slot) {
		return slot * 2 - this.healthContainers * 2;
	}

	/** Vanilla: the absorption heart exists when its first half-index is below the absorption total. */
	public boolean hasAbsorptionHeart(int slot) {
		return this.isAbsorptionSlot(slot) && this.absorptionHalves(slot) < this.absorption;
	}

	/** Vanilla: the absorption heart is half when its first half-index plus one equals the absorption total. */
	public boolean isAbsorptionHalf(int slot) {
		return this.absorptionHalves(slot) + 1 == this.absorption;
	}

	/** Vanilla: the health heart exists when the slot's first half-index is below the current health. */
	public boolean hasHealthHeart(int slot) {
		return slot * 2 < this.currentHealth;
	}

	/** Vanilla: the health heart is half when the slot's first half-index plus one equals the current health. */
	public boolean isHealthHalf(int slot) {
		return slot * 2 + 1 == this.currentHealth;
	}

	/** Vanilla: blink overlays cover every heart up to the lagging displayHealth copy. */
	public boolean hasBlinkHeart(int slot) {
		return slot * 2 < this.displayHealth;
	}

	/** Vanilla: the blink overlay is half when the slot's first half-index plus one equals displayHealth. */
	public boolean isBlinkHalf(int slot) {
		return slot * 2 + 1 == this.displayHealth;
	}

	/** Vanilla shakes every heart while the bar holds two full hearts or less. */
	public boolean shakes() {
		return this.currentHealth + this.absorption <= SHAKE_HALF_HEARTS;
	}

	/**
	 * The health slot lifted by two pixels during Regeneration, cycling through
	 * the bar like the vanilla HUD; -1 when the effect is absent or the bounce
	 * index fell beyond the health containers (invisible that tick, as in vanilla).
	 */
	public int regenBounceSlot(int tick, boolean regenerating) {
		if (!regenerating) {
			return -1;
		}
		int slot = Math.floorMod(tick, ceil(this.maxHealth + 5.0F));
		return slot < this.healthContainers ? slot : -1;
	}
}
