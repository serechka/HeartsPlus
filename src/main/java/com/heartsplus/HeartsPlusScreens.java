package com.heartsplus;

import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.ModList;

/**
 * Picks the settings screen: the Cloth Config one when the library is
 * present (the required dependency, six tabs, search, per-entry reset),
 * the bundled flat screen as the fallback for a cloth-less classpath.
 * The class reference for the Cloth screen sits behind the mod-loaded
 * check, so the fallback path never loads Cloth classes.
 */
public final class HeartsPlusScreens {
	private HeartsPlusScreens() {
	}

	public static Screen create(Screen parent) {
		// The NeoForge build of Cloth Config carries the mod id cloth_config.
		if (ModList.get().isLoaded("cloth_config")) {
			return HeartsPlusClothConfigScreen.create(parent);
		}
		return new HeartsPlusConfigScreen(parent);
	}
}
