package com.heartsplus;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public class HeartsPlusModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		// Cloth Config screen when present, bundled flat screen otherwise.
		return HeartsPlusScreens::create;
	}
}
