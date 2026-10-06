package com.heartsplus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;

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
	/** Default bar lift above the fixed anchor, in GUI pixels ("ideal height" reported by playtesting). */
	public static final int DEFAULT_HEART_OFFSET = 0;
	/**
	 * heartOffset stored by pre-0.4.4 configs. It was the default compensation
	 * for the old, higher anchor; the anchor now carries that compensation, so
	 * a stored -10 means "the old default" and migrates to 0 (same on-screen
	 * position). A -10 set deliberately after the migration is folded in too —
	 * accepted, since the value only ever existed as that compensation.
	 */
	private static final int LEGACY_HEART_OFFSET = -10;

	private static HeartsPlusConfig instance = new HeartsPlusConfig();

	// Gson writes these directly; all mutations go through the clamped static
	// accessors below, so nothing can bypass validation.
	private boolean modEnabled = true;
	private boolean showOwnHearts = false;
	private boolean showInvisiblePlayers = false;
	private boolean showBehindBlocks = false;
	private boolean vanillaTextures = false;
	private boolean blinkAnimation = true;
	private int heartOffset = DEFAULT_HEART_OFFSET;
	private double scale = 1.0;
	private double renderDistanceBlocks = 128.0;

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

	/**
	 * When false (default) hearts are hidden behind walls like normal
	 * geometry; when true, every pass is also drawn with a depth-test-free
	 * see-through render type so hearts stay visible through blocks.
	 */
	public static boolean isShowBehindBlocks() {
		return instance.showBehindBlocks;
	}

	public static void setShowBehindBlocks(boolean value) {
		instance.showBehindBlocks = value;
		save();
	}

	/**
	 * Drives the vanilla-style damage blink above players' heads (the flash
	 * and the pre-drop highlight). When false the bar always shows the current
	 * health with no flashing.
	 */
	public static boolean isBlinkAnimationEnabled() {
		return instance.blinkAnimation;
	}

	public static void setBlinkAnimation(boolean value) {
		instance.blinkAnimation = value;
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
		instance.heartOffset = (int) sanitize(value, DEFAULT_HEART_OFFSET, MIN_HEART_OFFSET, MAX_HEART_OFFSET);
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
		instance.heartOffset = (int) sanitize(value, DEFAULT_HEART_OFFSET, MIN_HEART_OFFSET, MAX_HEART_OFFSET);
	}

	public static void load() {
		Path path = configPath();
		if (!Files.exists(path)) {
			save();
			return;
		}
		try {
			HeartsPlusConfig read = parse(Files.readString(path, StandardCharsets.UTF_8));
			if (read != null) {
				instance = read;
			}
		} catch (IOException | com.google.gson.JsonParseException e) {
			HeartsPlusLog.LOGGER.error("Failed to read config file {}", path, e);
		}
	}

	/** Parses JSON into a clamped config, or null for empty input; package-private for tests. */
	static HeartsPlusConfig parse(String json) {
		HeartsPlusConfig read = GSON.fromJson(json, HeartsPlusConfig.class);
		if (read != null) {
			read.clamp();
			read.migrate();
		}
		return read;
	}

	/** Serializes to the on-disk JSON form; package-private for tests. */
	static String serialize(HeartsPlusConfig config) {
		return GSON.toJson(config);
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
		heartOffset = (int) sanitize(heartOffset, DEFAULT_HEART_OFFSET, MIN_HEART_OFFSET, MAX_HEART_OFFSET);
	}

	/**
	 * One-time migration for configs saved by older versions: a stored -10 is
	 * the old default that compensated the higher anchor (2.3), and the anchor
	 * is lowered now, so it becomes 0 — the identical on-screen position.
	 */
	private void migrate() {
		if (heartOffset == LEGACY_HEART_OFFSET) {
			heartOffset = DEFAULT_HEART_OFFSET;
		}
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
		return FMLPaths.CONFIGDIR.get().resolve(HeartsPlus.CONFIG_FILE);
	}
}
