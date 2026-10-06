package com.heartsplus.render;

/**
 * Vanilla HUD health-animation state machine for one player, deliberately
 * kept free of Minecraft classes so it is directly unit-testable. Mirrors
 * vanilla InGameHud.renderStatusBars: {@code lastHealthValue}/{@code
 * renderHealthValue} in half-hearts, a millisecond stamp of the last health
 * change and the blink window ({@code heartJumpEndTick}) whose on/off frames
 * come from the tick counter. Damage opens a 20-tick window; healing opens a
 * 10-tick one — that shorter flash is the vanilla recovery pop. The lagging
 * {@code displayHealth} copy snaps to the current health only after a full
 * second without changes, which is what keeps the lost hearts blinking in
 * place and sizes the containers while they do.
 */
public final class HeartAnimationState {
	/** Vanilla blink window after damage, in game ticks. */
	private static final int DAMAGE_BLINK_TICKS = 20;
	/** Vanilla blink window after healing (the recovery pop), in game ticks. */
	private static final int HEAL_BLINK_TICKS = 10;
	/** Vanilla lag before displayHealth snaps to the current health. */
	private static final long DISPLAY_CATCHUP_MILLIS = 1000L;
	/** Vanilla blink cadence: on for this many ticks, off for as long. */
	private static final long BLINK_PHASE_TICKS = 3L;

	private int lastHealth;
	private int displayHealth;
	private long lastHealthTime;
	private int blinkEndTick;
	/** The animation must advance once per game tick, but extraction runs every frame. */
	private int lastSeenTick = Integer.MIN_VALUE;
	private boolean blinking;

	/**
	 * Advances the state to {@code tick}. Statement order mirrors vanilla:
	 * the blink phase is sampled from the window as it was before this tick's
	 * change is folded in, and only an {@code invulnerable} change counts —
	 * the same gate the vanilla HUD applies against noisy health drift.
	 */
	public void tick(int currentHealth, int tick, long nowMillis, boolean invulnerable) {
		if (tick == this.lastSeenTick) {
			return;
		}
		this.lastSeenTick = tick;
		this.blinking = this.blinkEndTick > tick && (this.blinkEndTick - tick) / BLINK_PHASE_TICKS % 2L == 1L;
		if (currentHealth < this.lastHealth && invulnerable) {
			this.lastHealthTime = nowMillis;
			this.blinkEndTick = tick + DAMAGE_BLINK_TICKS;
		} else if (currentHealth > this.lastHealth && invulnerable) {
			this.lastHealthTime = nowMillis;
			this.blinkEndTick = tick + HEAL_BLINK_TICKS;
		}
		if (nowMillis - this.lastHealthTime > DISPLAY_CATCHUP_MILLIS) {
			this.displayHealth = currentHealth;
			this.lastHealthTime = nowMillis;
		}
		this.lastHealth = currentHealth;
	}

	/** True on the on-frames of the blink flash for the current tick. */
	public boolean isBlinking() {
		return this.blinking;
	}

	/** The lagging health copy that the blink overlay and container count follow. */
	public int displayHealth() {
		return this.displayHealth;
	}
}
