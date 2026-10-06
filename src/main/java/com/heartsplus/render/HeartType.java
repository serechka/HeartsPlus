package com.heartsplus.render;

import net.minecraft.resources.Identifier;

/**
 * Heart families mirroring vanilla Hud.HeartType, including the blinking
 * variants used by the damage-flash animation. Each family knows both its
 * GUI-atlas sprites (follow the active resource pack) and the plain sprite
 * files inside Minecraft's built-in default resource pack, so the player can
 * pin the look to the unmodified default textures no matter which packs are
 * stacked on top.
 */
public enum HeartType {
	CONTAINER("container", "container", "container_blinking", "container_blinking"),
	NORMAL("full", "half", "full_blinking", "half_blinking"),
	POISONED("poisoned_full", "poisoned_half", "poisoned_full_blinking", "poisoned_half_blinking"),
	WITHERED("withered_full", "withered_half", "withered_full_blinking", "withered_half_blinking"),
	FROZEN("frozen_full", "frozen_half", "frozen_full_blinking", "frozen_half_blinking"),
	ABSORBING("absorbing_full", "absorbing_half", "absorbing_full_blinking", "absorbing_half_blinking");

	/** Sprites inside the vanilla GUI atlas ({@code textures/atlas/gui.png}). */
	public final Identifier atlasFull;
	public final Identifier atlasHalf;
	public final Identifier atlasFullBlinking;
	public final Identifier atlasHalfBlinking;
	/** Heart sprite files inside Minecraft's built-in default resource pack. */
	public final Identifier fileFull;
	public final Identifier fileHalf;
	public final Identifier fileFullBlinking;
	public final Identifier fileHalfBlinking;

	HeartType(String full, String half, String fullBlinking, String halfBlinking) {
		this.atlasFull = Identifier.withDefaultNamespace("hud/heart/" + full);
		this.atlasHalf = Identifier.withDefaultNamespace("hud/heart/" + half);
		this.atlasFullBlinking = Identifier.withDefaultNamespace("hud/heart/" + fullBlinking);
		this.atlasHalfBlinking = Identifier.withDefaultNamespace("hud/heart/" + halfBlinking);
		this.fileFull = Identifier.withDefaultNamespace("textures/gui/sprites/hud/heart/" + full + ".png");
		this.fileHalf = Identifier.withDefaultNamespace("textures/gui/sprites/hud/heart/" + half + ".png");
		this.fileFullBlinking = Identifier.withDefaultNamespace("textures/gui/sprites/hud/heart/" + fullBlinking + ".png");
		this.fileHalfBlinking = Identifier.withDefaultNamespace("textures/gui/sprites/hud/heart/" + halfBlinking + ".png");
	}

	/** All default-pack sprite ids of this family, for warm-up registration. */
	public Identifier[] fileTextures() {
		return new Identifier[]{this.fileFull, this.fileHalf, this.fileFullBlinking, this.fileHalfBlinking};
	}

	/**
	 * The family vanilla's HUD would show for a player with the given status
	 * effects; priority matches Hud.HeartType.forPlayer.
	 */
	public static HeartType forStatus(boolean poisoned, boolean withered, boolean frozen) {
		if (poisoned) {
			return POISONED;
		}
		if (withered) {
			return WITHERED;
		}
		return frozen ? FROZEN : NORMAL;
	}
}
