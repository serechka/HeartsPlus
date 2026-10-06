package com.heartsplus.mixin;

import com.heartsplus.render.HealthHolder;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Attaches health data to the vanilla AvatarRenderState (the player render
 * state, Mojang mappings) so the render phase can draw hearts without
 * reaching back into the entity.
 */
@Mixin(AvatarRenderState.class)
public abstract class AvatarRenderStateMixin implements HealthHolder {
	@Unique
	private static final int heartsplus$BLINK_TICKS = 15;

	@Unique
	private float heartsplus$health;
	@Unique
	private float heartsplus$maxHealth;
	@Unique
	private float heartsplus$absorption;
	@Unique
	private boolean heartsplus$localPlayer;
	@Unique
	private boolean heartsplus$poisoned;
	@Unique
	private boolean heartsplus$withered;
	@Unique
	private boolean heartsplus$frozen;
	@Unique
	private boolean heartsplus$visibleArmour;
	@Unique
	private float heartsplus$lastHealth = Float.NaN;
	@Unique
	private float heartsplus$blinkOldHealth;
	@Unique
	private int heartsplus$blinkEndTick = Integer.MIN_VALUE;

	@Override
	@Unique
	public float heartsplus$getHealth() {
		return this.heartsplus$health;
	}

	@Override
	@Unique
	public float heartsplus$getMaxHealth() {
		return this.heartsplus$maxHealth;
	}

	@Override
	@Unique
	public float heartsplus$getAbsorption() {
		return this.heartsplus$absorption;
	}

	@Override
	@Unique
	public boolean heartsplus$isLocalPlayer() {
		return this.heartsplus$localPlayer;
	}

	@Override
	@Unique
	public boolean heartsplus$isPoisoned() {
		return this.heartsplus$poisoned;
	}

	@Override
	@Unique
	public boolean heartsplus$isWithered() {
		return this.heartsplus$withered;
	}

	@Override
	@Unique
	public boolean heartsplus$isFrozen() {
		return this.heartsplus$frozen;
	}

	@Override
	@Unique
	public boolean heartsplus$hasVisibleArmour() {
		return this.heartsplus$visibleArmour;
	}

	@Override
	@Unique
	public float heartsplus$getBlinkOldHealth() {
		return this.heartsplus$blinkOldHealth;
	}

	@Override
	@Unique
	public int heartsplus$getBlinkEndTick() {
		return this.heartsplus$blinkEndTick;
	}

	@Override
	@Unique
	public void heartsplus$update(float health, float maxHealth, float absorption, boolean localPlayer,
			boolean poisoned, boolean withered, boolean frozen, boolean hasVisibleArmour, int tick) {
		if (!Float.isNaN(this.heartsplus$lastHealth) && health < this.heartsplus$lastHealth - 0.01F) {
			this.heartsplus$blinkOldHealth = this.heartsplus$lastHealth;
			this.heartsplus$blinkEndTick = tick + heartsplus$BLINK_TICKS;
		}
		this.heartsplus$lastHealth = health;
		this.heartsplus$health = health;
		this.heartsplus$maxHealth = maxHealth;
		this.heartsplus$absorption = absorption;
		this.heartsplus$localPlayer = localPlayer;
		this.heartsplus$poisoned = poisoned;
		this.heartsplus$withered = withered;
		this.heartsplus$frozen = frozen;
		this.heartsplus$visibleArmour = hasVisibleArmour;
	}
}
