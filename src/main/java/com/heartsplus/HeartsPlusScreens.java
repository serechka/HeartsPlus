package com.heartsplus;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.Screen;

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
		if (FabricLoader.getInstance().isModLoaded("cloth-config")) {
			return HeartsPlusClothConfigScreen.create(parent);
		}
		return new HeartsPlusConfigScreen(parent);
	}
}
