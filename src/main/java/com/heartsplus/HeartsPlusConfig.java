package com.heartsplus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.neoforged.fml.loading.FMLPaths;

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
	public static final int MIN_HEART_OFFSET = -40;
	public static final int MAX_HEART_OFFSET = 40;
	/**
	 * Default bar lift above the fixed anchor, in GUI pixels. Since 0.4.7 the
	 * anchor itself sits at the height the owner playtested as ideal (the old
	 * 10px default lift was folded into it), so 0 is that height and the
	 * setting tunes up and down symmetrically.
	 */
	public static final int DEFAULT_HEART_OFFSET = 0;
	/**
	 * heartOffset stored by pre-0.4.4 configs. It was the default compensation
	 * for the old, higher anchor; the 0.4.4 migration lands it at
	 * {@link #LEGACY_DEFAULT_LANDING} before the 0.4.7 shift applies. A -10 set
	 * deliberately after that migration is folded in too — accepted, since the
	 * value only ever existed as that compensation.
	 */
	private static final int LEGACY_HEART_OFFSET = -10;
	/** Where a {@link #LEGACY_HEART_OFFSET} lands: the old default's on-screen position, independent of {@link #DEFAULT_HEART_OFFSET}. */
	private static final int LEGACY_DEFAULT_LANDING = 0;
	/**
	 * 0.4.7 folded the old 10px default lift into the anchor
	 * (2.10 + 10 × 0.025 = 2.35), so every pre-0.4.7 stored offset compensates
	 * by this much to render at the same height.
	 */
	private static final int ANCHOR_ABSORBED_PIXELS = 10;
	/** The 0.4.6 default, kept for old files that omit heartOffset entirely. */
	private static final int PREVIOUS_DEFAULT_HEART_OFFSET = 10;
	/**
	 * On-disk schema version. Files written before 0.4.7 carry no key and are
	 * detected by its absence (Gson would otherwise keep the current-version
	 * initializer below), so the one-time migration can never run twice on a
	 * migrated file: every save writes the version along with the new values.
	 */
	private static final int CONFIG_VERSION = 1;

	private static HeartsPlusConfig instance = new HeartsPlusConfig();

	// Gson writes these directly; all mutations go through the clamped static
	// accessors below, so nothing can bypass validation.
	private int configVersion = CONFIG_VERSION;
	/** Set once parse() has migrated an older file; load() then persists the result immediately. */
	private transient boolean migrated;
	private boolean modEnabled = true;
	private boolean showOwnHearts = false;
	private boolean showInvisiblePlayers = false;
	private boolean showBehindBlocks = true;
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
	 * When true (default) hearts are drawn like a name tag: the depth-tested
	 * pass plus a dimmed half-transparent see-through copy that stays visible
	 * through blocks. When false only the depth-tested pass is drawn, so walls
	 * hide the hearts. Sneaking players keep only that pass either way, like a
	 * vanilla name tag on sneak.
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
				if (read.migrated) {
					// Persist the migrated values (and the schema version that
					// makes the one-time shift stick) before anything else can
					// read the file back.
					save();
				}
			}
		} catch (IOException | com.google.gson.JsonParseException e) {
			HeartsPlusLog.LOGGER.error("Failed to read config file {}", path, e);
		}
	}

	/** Parses JSON into a clamped config, or null for empty input; package-private for tests. */
	static HeartsPlusConfig parse(String json) {
		HeartsPlusConfig read = GSON.fromJson(json, HeartsPlusConfig.class);
		if (read == null) {
			return null;
		}
		// Files written before 0.4.7 carry no configVersion key, but Gson has
		// already filled the field with the current-version initializer, so the
		// key's presence must be checked on the raw JSON tree.
		com.google.gson.JsonObject tree = JsonParser.parseString(json).getAsJsonObject();
		if (!tree.has("configVersion")) {
			read.configVersion = 0;
			if (!tree.has("heartOffset")) {
				// An old file without heartOffset stored the 0.4.6 default;
				// Gson has filled the new default (0), which must not shift.
				read.heartOffset = PREVIOUS_DEFAULT_HEART_OFFSET;
			}
		}
		read.clamp();
		read.migrate();
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
	 * One-time migration of pre-0.4.7 configs. The 0.4.7 anchor absorbed the
	 * old 10px default lift (2.10 + 10 × 0.025 = 2.35), so every stored offset
	 * shifts down by 10 to render at exactly its old height; a pre-0.4.4 -10
	 * first lands on the 0.4.6 position per the earlier migration rule. Runs
	 * only when the parsed file carried no current {@code configVersion}, and
	 * every save afterwards writes that version, so the shift can never be
	 * applied a second time to the same file.
	 */
	private void migrate() {
		if (configVersion >= CONFIG_VERSION) {
			return;
		}
		migrated = true;
		if (heartOffset == LEGACY_HEART_OFFSET) {
			// Land on the position the legacy default actually rendered at in
			// 0.4.6 (anchor 2.10 + 0), not on the new default height.
			heartOffset = LEGACY_DEFAULT_LANDING;
		}
		heartOffset -= ANCHOR_ABSORBED_PIXELS;
		// Hand-edited files can carry out-of-range values that clamp() already
		// folded before the shift; re-clamp so nothing off-range persists.
		heartOffset = (int) sanitize(heartOffset, DEFAULT_HEART_OFFSET, MIN_HEART_OFFSET, MAX_HEART_OFFSET);
		configVersion = CONFIG_VERSION;
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
