package com.heartsplus.render;

/**
 * Vanilla HUD blink-window state machine for one player, deliberately kept
 * free of Minecraft classes so it is directly unit-testable. Mirrors the
 * window logic of vanilla Gui.renderHealthLevel: a blink end tick whose
 * on/off frames come from the tick counter via the square wave
 * {@code (blinkEnd - tick) / 3 % 2 == 1}. Damage opens a 20-tick window;
 * healing opens a 10-tick one. Since 0.4.7 the bar value itself is instant
 * (the vanilla lagging {@code displayHealth} copy and its 1000 ms catch-up
 * are dropped by owner spec), so the window drives only the flash overlays:
 * the half-heart interval changed by the armed change is remembered, and on
 * damage the vanilla blinking sprites are drawn on the lost slots. On
 * acquired slots vanilla draws no blinking heart at all (Gui.renderHearts
 * draws the blinking container variant plus the normal heart there), so a
 * heal window only flashes the containers.
 */
public final class HeartAnimationState {
	/** Vanilla blink window after damage, in game ticks. */
	private static final int DAMAGE_BLINK_TICKS = 20;
	/** Vanilla blink window after healing (the recovery pop), in game ticks. */
	private static final int HEAL_BLINK_TICKS = 10;
	/** Vanilla blink cadence: on for this many ticks, off for as long. */
	private static final long BLINK_PHASE_TICKS = 3L;

	private int lastHealth;
	private int blinkEndTick;
	/** Half-heart interval [start, end) carrying the blinking overlay; empty for heal windows. */
	private int blinkOverlayStart;
	private int blinkOverlayEnd;
	/** The animation must advance once per game tick, but extraction runs every frame. */
	private int lastSeenTick = Integer.MIN_VALUE;
	private boolean blinking;

	/**
	 * Advances the state to {@code tick}. Statement order mirrors vanilla:
	 * the blink phase is sampled from the window as it was before this tick's
	 * change is folded in, and only an {@code invulnerable} change counts —
	 * the same gate the vanilla HUD applies against noisy health drift. With
	 * the animation off no window is armed, but {@code lastHealth} still
	 * tracks, so re-enabling cannot flash a change that happened meanwhile.
	 *
	 * @return true when the tick counter moved backwards — the entity behind
	 * this UUID was recreated (world or server switch, chunk reload) and the
	 * state was reset; the caller must drop any companion state (height
	 * smoothing) the old entity fed too.
	 */
	public boolean tick(int currentHealth, int tick, boolean invulnerable, boolean animationEnabled) {
		if (tick == this.lastSeenTick) {
			return false;
		}
		if (tick < this.lastSeenTick) {
			// The tick counter restarted under the same UUID. Every window here
			// is built from the old counter, so its square wave would thrash on
			// for ages (the stale 0.4.7 flicker); drop it all and re-baseline.
			this.lastSeenTick = tick;
			this.blinkEndTick = 0;
			this.blinkOverlayStart = 0;
			this.blinkOverlayEnd = 0;
			this.blinking = false;
			this.lastHealth = currentHealth;
			return true;
		}
		this.lastSeenTick = tick;
		this.blinking = animationEnabled && this.blinkEndTick > tick
				&& (this.blinkEndTick - tick) / BLINK_PHASE_TICKS % 2L == 1L;
		if (animationEnabled && invulnerable && currentHealth != this.lastHealth) {
			// A change while a window is live re-arms it from the latest value.
			if (currentHealth < this.lastHealth) {
				this.blinkOverlayStart = currentHealth;
				this.blinkOverlayEnd = this.lastHealth;
				this.blinkEndTick = tick + DAMAGE_BLINK_TICKS;
			} else {
				// A heal window flashes only the containers: vanilla draws no
				// blinking heart on acquired slots (Gui.renderHearts draws the
				// blinking container variant plus the normal heart there).
				this.blinkOverlayStart = 0;
				this.blinkOverlayEnd = 0;
				this.blinkEndTick = tick + HEAL_BLINK_TICKS;
			}
		}
		this.lastHealth = currentHealth;
		return false;
	}

	/** True on the on-frames of the blink flash for the current tick. */
	public boolean isBlinking() {
		return this.blinking;
	}

	/** First half-heart index carrying a blinking overlay sprite (inclusive). */
	public int blinkOverlayStart() {
		return this.blinkOverlayStart;
	}

	/** Half-heart index after the last blinking overlay sprite (exclusive). */
	public int blinkOverlayEnd() {
		return this.blinkOverlayEnd;
	}
}
