package com.heartsplus.render;

import net.minecraft.resources.Identifier;

/**
 * Heart families mirroring vanilla Hud.HeartType. Each family knows both its
 * GUI-atlas sprite (follows the active resource pack) and a copy of the
 * vanilla texture bundled with the mod, so the player can pin the look to
 * the default textures regardless of any installed pack.
 */
public enum HeartType {
	CONTAINER("container", "container"),
	NORMAL("full", "half"),
	POISONED("poisoned_full", "poisoned_half"),
	WITHERED("withered_full", "withered_half"),
	FROZEN("frozen_full", "frozen_half"),
	ABSORBING("absorbing_full", "absorbing_half");

	/** Sprite inside the vanilla GUI atlas ({@code textures/atlas/gui.png}). */
	public final Identifier atlasFull;
	public final Identifier atlasHalf;
	/** Bundled copies of the default textures, used in vanilla mode. */
	public final Identifier fileFull;
	public final Identifier fileHalf;

	HeartType(String full, String half) {
		this.atlasFull = Identifier.withDefaultNamespace("hud/heart/" + full);
		this.atlasHalf = Identifier.withDefaultNamespace("hud/heart/" + half);
		this.fileFull = Identifier.fromNamespaceAndPath("heartsplus", "textures/vanilla/" + full + ".png");
		this.fileHalf = Identifier.fromNamespaceAndPath("heartsplus", "textures/vanilla/" + half + ".png");
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
