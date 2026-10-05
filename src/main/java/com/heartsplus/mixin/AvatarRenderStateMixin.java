package com.heartsplus.mixin;

import com.heartsplus.render.HealthHolder;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Attaches health data to the vanilla AvatarRenderState so the submit
 * phase can draw hearts without reaching back into the entity.
 */
@Mixin(AvatarRenderState.class)
public abstract class AvatarRenderStateMixin implements HealthHolder {
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
	public void heartsplus$update(float health, float maxHealth, float absorption, boolean localPlayer,
			boolean poisoned, boolean withered) {
		this.heartsplus$health = health;
		this.heartsplus$maxHealth = maxHealth;
		this.heartsplus$absorption = absorption;
		this.heartsplus$localPlayer = localPlayer;
		this.heartsplus$poisoned = poisoned;
		this.heartsplus$withered = withered;
	}
}
