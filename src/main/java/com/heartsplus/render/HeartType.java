package com.heartsplus.render;

import net.minecraft.resources.ResourceLocation;

/**
 * Heart families mirroring vanilla InGameHud.HeartType, including the blinking
 * variants used by the damage-flash animation. Each family knows both its
 * GUI-atlas sprites (follow the active resource pack) and copies of the
 * vanilla textures bundled with the mod, so the player can pin the look to
 * the default textures regardless of any installed pack.
 */
public enum HeartType {
	CONTAINER("container", "container", "container_blinking", "container_blinking"),
	NORMAL("full", "half", "full_blinking", "half_blinking"),
	POISONED("poisoned_full", "poisoned_half", "poisoned_full_blinking", "poisoned_half_blinking"),
	WITHERED("withered_full", "withered_half", "withered_full_blinking", "withered_half_blinking"),
	FROZEN("frozen_full", "frozen_half", "frozen_full_blinking", "frozen_half_blinking"),
	ABSORBING("absorbing_full", "absorbing_half", "absorbing_full_blinking", "absorbing_half_blinking");

	/** Sprites inside the vanilla GUI atlas ({@code textures/atlas/gui.png}). */
	public final ResourceLocation atlasFull;
	public final ResourceLocation atlasHalf;
	public final ResourceLocation atlasFullBlinking;
	public final ResourceLocation atlasHalfBlinking;
	/** Bundled copies of the default textures, used in vanilla mode. */
	public final ResourceLocation fileFull;
	public final ResourceLocation fileHalf;
	public final ResourceLocation fileFullBlinking;
	public final ResourceLocation fileHalfBlinking;

	HeartType(String full, String half, String fullBlinking, String halfBlinking) {
		this.atlasFull = ResourceLocation.withDefaultNamespace("hud/heart/" + full);
		this.atlasHalf = ResourceLocation.withDefaultNamespace("hud/heart/" + half);
		this.atlasFullBlinking = ResourceLocation.withDefaultNamespace("hud/heart/" + fullBlinking);
		this.atlasHalfBlinking = ResourceLocation.withDefaultNamespace("hud/heart/" + halfBlinking);
		this.fileFull = ResourceLocation.fromNamespaceAndPath("heartsplus", "textures/vanilla/" + full + ".png");
		this.fileHalf = ResourceLocation.fromNamespaceAndPath("heartsplus", "textures/vanilla/" + half + ".png");
		this.fileFullBlinking = ResourceLocation.fromNamespaceAndPath("heartsplus", "textures/vanilla/" + fullBlinking + ".png");
		this.fileHalfBlinking = ResourceLocation.fromNamespaceAndPath("heartsplus", "textures/vanilla/" + halfBlinking + ".png");
	}

	/** All bundled-file texture ids of this family, for warm-up registration. */
	public ResourceLocation[] fileTextures() {
		return new ResourceLocation[]{this.fileFull, this.fileHalf, this.fileFullBlinking, this.fileHalfBlinking};
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
