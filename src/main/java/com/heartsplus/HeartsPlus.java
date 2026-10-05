package com.heartsplus;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Common mod constants shared by every class of HeartsPlus.
 */
@Environment(EnvType.CLIENT)
public final class HeartsPlus {
	public static final String MOD_ID = "heartsplus";
	public static final String CONFIG_FILE = "heartsplus.json";

	private HeartsPlus() {
	}
}
