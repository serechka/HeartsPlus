package com.heartsplus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client-side configuration. Every field is exposed through a pair of
 * static accessors and persisted immediately after a change, so the
 * config file always mirrors what the player currently sees on screen.
 * Numeric bounds live here as public constants so the settings screen
 * sliders and the clamping logic cannot drift apart.
 */
public final class HeartsPlusConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static final double MIN_SCALE = 0.25;
	public static final double MAX_SCALE = 4.0;
	public static final double MIN_RENDER_DISTANCE = 8.0;
	public static final double MAX_RENDER_DISTANCE = 128.0;
	public static final int MIN_HEART_OFFSET = -20;
	public static final int MAX_HEART_OFFSET = 40;

	private static HeartsPlusConfig instance = new HeartsPlusConfig();

	public boolean modEnabled = true;
	public boolean showOwnHearts = false;
	public boolean showInvisiblePlayers = false;
	public boolean showSneakingPlayers = false;
	public boolean vanillaTextures = false;
	public int heartOffset = 0;
	public double scale = 1.0;
	public double renderDistanceBlocks = 128.0;

	public static boolean isEnabled() {
		return instance.modEnabled;
	}

	public static void setEnabled(boolean value) {
		instance.modEnabled = value;
		save();
	}

	public static boolean isShowOwnHearts() {
		return instance.showOwnHearts;
	}

	public static void setShowOwnHearts(boolean value) {
		instance.showOwnHearts = value;
		save();
	}

	/**
	 * When false (default) invisible players get no hearts at all. When true,
	 * an invisible player still shows hearts only while wearing armour.
	 */
	public static boolean isShowInvisiblePlayers() {
		return instance.showInvisiblePlayers;
	}

	public static void setShowInvisiblePlayers(boolean value) {
		instance.showInvisiblePlayers = value;
		save();
	}

	/** When false (default) sneaking players get no hearts, like their name tag; when true, hearts stay visible. */
	public static boolean isShowSneakingPlayers() {
		return instance.showSneakingPlayers;
	}

	public static void setShowSneakingPlayers(boolean value) {
		instance.showSneakingPlayers = value;
		save();
	}

	/**
	 * @return false to take heart sprites from the GUI atlas (honours the
	 * active resource pack), true to use the vanilla textures bundled with
	 * the mod regardless of any installed pack.
	 */
	public static boolean isVanillaTextures() {
		return instance.vanillaTextures;
	}

	public static void setVanillaTextures(boolean value) {
		instance.vanillaTextures = value;
		save();
	}

	public static int getHeartOffset() {
		return instance.heartOffset;
	}

	public static void setHeartOffset(int value) {
		instance.heartOffset = (int) sanitize(value, 0, MIN_HEART_OFFSET, MAX_HEART_OFFSET);
		save();
	}

	public static double getScale() {
		return instance.scale;
	}

	public static void setScale(double value) {
		instance.scale = sanitize(value, 1.0, MIN_SCALE, MAX_SCALE);
		save();
	}

	public static double getRenderDistance() {
		return instance.renderDistanceBlocks;
	}

	public static void setRenderDistance(double value) {
		instance.renderDistanceBlocks = sanitize(value, 128.0, MIN_RENDER_DISTANCE, MAX_RENDER_DISTANCE);
		save();
	}

	public static void reset() {
		instance = new HeartsPlusConfig();
		save();
	}

	static void setScaleSilently(double value) {
		instance.scale = sanitize(value, 1.0, MIN_SCALE, MAX_SCALE);
	}

	static void setRenderDistanceSilently(double value) {
		instance.renderDistanceBlocks = sanitize(value, 128.0, MIN_RENDER_DISTANCE, MAX_RENDER_DISTANCE);
	}

	static void setHeartOffsetSilently(int value) {
		instance.heartOffset = (int) sanitize(value, 0, MIN_HEART_OFFSET, MAX_HEART_OFFSET);
	}

	public static void load() {
		Path path = configPath();
		if (!Files.exists(path)) {
			save();
			return;
		}
		try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			HeartsPlusConfig read = GSON.fromJson(reader, HeartsPlusConfig.class);
			if (read != null) {
				read.clamp();
				instance = read;
			}
		} catch (IOException | com.google.gson.JsonParseException e) {
			HeartsPlusLog.LOGGER.error("Failed to read config file {}", path, e);
		}
	}

	public static void save() {
		Path path = configPath();
		try {
			Files.createDirectories(path.getParent());
			try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(instance, writer);
			}
		} catch (IOException e) {
			HeartsPlusLog.LOGGER.error("Failed to write config file {}", path, e);
		}
	}

	private void clamp() {
		scale = sanitize(scale, 1.0, MIN_SCALE, MAX_SCALE);
		renderDistanceBlocks = sanitize(renderDistanceBlocks, 128.0, MIN_RENDER_DISTANCE, MAX_RENDER_DISTANCE);
		heartOffset = (int) sanitize(heartOffset, 0, MIN_HEART_OFFSET, MAX_HEART_OFFSET);
	}

	/**
	 * Clamps a config value into its legal range, falling back to the
	 * default when the stored value is NaN (Gson accepts a bare NaN literal).
	 */
	private static double sanitize(double value, double fallback, double min, double max) {
		double safe = Double.isNaN(value) ? fallback : value;
		return Math.clamp(safe, min, max);
	}

	private static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve(HeartsPlus.CONFIG_FILE);
	}
}
