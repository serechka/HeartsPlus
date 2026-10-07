package com.heartsplus.mixin;

import com.heartsplus.render.BlinkTracker;
import com.heartsplus.render.HealthHolder;
import java.util.UUID;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Attaches health data to the vanilla AvatarRenderState so the submit
 * phase can draw hearts without reaching back into the entity. The vanilla
 * animation history itself is kept in {@link BlinkTracker} by UUID: render
 * states are recycled between frames, which used to wipe state-stored
 * history and silently killed the animation.
 */
@Mixin(AvatarRenderState.class)
public abstract class AvatarRenderStateMixin implements HealthHolder {
	@Unique
	private UUID heartsplus$playerUuid;
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
	private int heartsplus$blinkOverlayStart;
	@Unique
	private int heartsplus$blinkOverlayEnd;
	@Unique
	private boolean heartsplus$blinking;
	@Unique
	private float heartsplus$heartAnchorY;

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
	public int heartsplus$getBlinkOverlayStart() {
		return this.heartsplus$blinkOverlayStart;
	}

	@Override
	@Unique
	public int heartsplus$getBlinkOverlayEnd() {
		return this.heartsplus$blinkOverlayEnd;
	}

	@Override
	@Unique
	public boolean heartsplus$isBlinking() {
		return this.heartsplus$blinking;
	}

	@Override
	@Unique
	public float heartsplus$getHeartAnchorY() {
		return this.heartsplus$heartAnchorY;
	}

	@Override
	@Unique
	public void heartsplus$update(UUID playerId, float health, float maxHealth, float absorption, boolean localPlayer,
			boolean poisoned, boolean withered, boolean frozen, boolean hasVisibleArmour, int tick,
			boolean invulnerable, float attachmentY) {
		this.heartsplus$playerUuid = playerId;
		this.heartsplus$health = health;
		this.heartsplus$maxHealth = maxHealth;
		this.heartsplus$absorption = absorption;
		this.heartsplus$localPlayer = localPlayer;
		this.heartsplus$poisoned = poisoned;
		this.heartsplus$withered = withered;
		this.heartsplus$frozen = frozen;
		this.heartsplus$visibleArmour = hasVisibleArmour;
		this.heartsplus$heartAnchorY = BlinkTracker.update(playerId, health, tick, invulnerable, attachmentY);
		this.heartsplus$blinkOverlayStart = BlinkTracker.getBlinkOverlayStart(playerId);
		this.heartsplus$blinkOverlayEnd = BlinkTracker.getBlinkOverlayEnd(playerId);
		this.heartsplus$blinking = BlinkTracker.isBlinking(playerId);
	}
}
