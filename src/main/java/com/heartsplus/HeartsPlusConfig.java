package com.heartsplus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
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
	/** Raised from 128 in 0.4.9 together with the quadratic layer-spacing term. */
	public static final double MAX_RENDER_DISTANCE = 2048.0;
	public static final int MIN_HEART_OFFSET = -40;
	public static final int MAX_HEART_OFFSET = 40;
	/**
	 * Follow Smoothing slider bounds, in arbitrary points. 0 disables
	 * smoothing entirely (hearts sit on the anchor immediately); higher
	 * values glide slower and smoother.
	 */
	public static final int MIN_FOLLOW_SMOOTHNESS = 0;
	public static final int MAX_FOLLOW_SMOOTHNESS = 100;
	/** Follow Smoothing default: chases 1.5x faster than the fixed 0.4.8 rate (12 vs 8) with a doubled speed cap (8 vs 4 blocks/s). */
	public static final int DEFAULT_FOLLOW_SMOOTHNESS = 50;
	/** See-Through Opacity slider bounds, in percent of the fully bright pass. */
	public static final int MIN_WALL_OPACITY = 0;
	public static final int MAX_WALL_OPACITY = 100;
	/** See-Through Opacity default: the 0.4.8 dimming (0x80 = 50 percent). */
	public static final int DEFAULT_WALL_OPACITY = 50;
	/**
	 * Follow Smoothing to chase rate, in 1/seconds: linear from
	 * {@link #FOLLOW_RATE_BASE} at 1 point down to
	 * {@code FOLLOW_RATE_BASE - FOLLOW_RATE_PER_POINT * 100} at 100 points.
	 * The default 50 lands exactly on 12, the owner-chosen 1.5x of the fixed
	 * 0.4.8 rate of 8.
	 */
	public static final double FOLLOW_RATE_BASE = 22.0;
	public static final double FOLLOW_RATE_PER_POINT = 0.2;
	/**
	 * Follow Smoothing to speed cap, in blocks per second: linear, so the
	 * default 50 lands exactly on 8, double the fixed 0.4.8 cap of 4 - the
	 * raise that keeps the bar honest during fast elytra dives.
	 */
	public static final double FOLLOW_CAP_BASE = 4.0;
	public static final double FOLLOW_CAP_PER_POINT = 0.08;
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
	 * deliberately after that migration is folded in too - accepted, since the
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
	 * 0.4.9 raised it to 2: heartOffset becomes offsetStanding and
	 * showBehindBlocks becomes the wallOpacity slider.
	 */
	private static final int CONFIG_VERSION = 2;

	private static HeartsPlusConfig instance = new HeartsPlusConfig();

	// Gson writes these directly; all mutations go through the clamped static
	// accessors below, so nothing can bypass validation.
	private int configVersion = CONFIG_VERSION;
	/** Set once parse() has migrated an older file; load() then persists the result immediately. */
	private transient boolean migrated;
	private boolean modEnabled = true;
	private boolean showOwnHearts = false;
	private boolean showInvisiblePlayers = false;
	/** See-through copy opacity behind walls, 0-100 percent; 0 draws no see-through pass at all. */
	private int wallOpacity = DEFAULT_WALL_OPACITY;
	private boolean vanillaTextures = false;
	private boolean blinkAnimation = true;
	/** Chase smoothing points, 0-100; 0 disables smoothing (see the follow* formulas above). */
	private int followSmoothness = DEFAULT_FOLLOW_SMOOTHNESS;
	// One bar lift per pose, in GUI pixels, applied on top of the smoothed
	// anchor. Pre-0.4.9 files had the single heartOffset, which migrates into
	// the standing value; the other poses start at no extra lift.
	private int offsetStanding = DEFAULT_HEART_OFFSET;
	private int offsetSneaking = DEFAULT_HEART_OFFSET;
	private int offsetSwimming = DEFAULT_HEART_OFFSET;
	private int offsetFlying = DEFAULT_HEART_OFFSET;
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
	 * Opacity of the see-through copy that stays visible through blocks, in
	 * percent of the fully bright pass. Zero drops the see-through pass
	 * entirely, so walls hide the hearts; 100 draws it fully bright.
	 * Sneaking players keep only the depth-tested pass either way, like a
	 * vanilla name tag on sneak.
	 */
	public static int getWallOpacity() {
		return instance.wallOpacity;
	}

	public static void setWallOpacity(int value) {
		instance.wallOpacity = (int) sanitize(value, DEFAULT_WALL_OPACITY, MIN_WALL_OPACITY, MAX_WALL_OPACITY);
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

	/** Follow Smoothing points, 0-100; zero disables smoothing. */
	public static int getFollowSmoothness() {
		return instance.followSmoothness;
	}

	public static void setFollowSmoothness(int value) {
		instance.followSmoothness = (int) sanitize(value, DEFAULT_FOLLOW_SMOOTHNESS, MIN_FOLLOW_SMOOTHNESS, MAX_FOLLOW_SMOOTHNESS);
		save();
	}

	/**
	 * @return the exponential chase rate in 1/seconds for the stored
	 * Follow Smoothing, or 0 when smoothing is off; see
	 * {@link #FOLLOW_RATE_BASE}.
	 */
	public static double followRatePerSecond() {
		int smoothness = instance.followSmoothness;
		if (smoothness <= MIN_FOLLOW_SMOOTHNESS) {
			return 0.0;
		}
		return FOLLOW_RATE_BASE - FOLLOW_RATE_PER_POINT * smoothness;
	}

	/**
	 * @return the chase speed cap in blocks per second for the stored
	 * Follow Smoothing; see {@link #FOLLOW_CAP_BASE}.
	 */
	public static double followSpeedCapBlocksPerSecond() {
		return FOLLOW_CAP_BASE + FOLLOW_CAP_PER_POINT * instance.followSmoothness;
	}

	public static int getOffsetStanding() {
		return instance.offsetStanding;
	}

	public static void setOffsetStanding(int value) {
		instance.offsetStanding = heartOffsetValue(value);
		save();
	}

	public static int getOffsetSneaking() {
		return instance.offsetSneaking;
	}

	public static void setOffsetSneaking(int value) {
		instance.offsetSneaking = heartOffsetValue(value);
		save();
	}

	public static int getOffsetSwimming() {
		return instance.offsetSwimming;
	}

	public static void setOffsetSwimming(int value) {
		instance.offsetSwimming = heartOffsetValue(value);
		save();
	}

	public static int getOffsetFlying() {
		return instance.offsetFlying;
	}

	public static void setOffsetFlying(int value) {
		instance.offsetFlying = heartOffsetValue(value);
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

	static void setWallOpacitySilently(int value) {
		instance.wallOpacity = (int) sanitize(value, DEFAULT_WALL_OPACITY, MIN_WALL_OPACITY, MAX_WALL_OPACITY);
	}

	static void setFollowSmoothnessSilently(int value) {
		instance.followSmoothness = (int) sanitize(value, DEFAULT_FOLLOW_SMOOTHNESS, MIN_FOLLOW_SMOOTHNESS, MAX_FOLLOW_SMOOTHNESS);
	}

	static void setOffsetStandingSilently(int value) {
		instance.offsetStanding = heartOffsetValue(value);
	}

	static void setOffsetSneakingSilently(int value) {
		instance.offsetSneaking = heartOffsetValue(value);
	}

	static void setOffsetSwimmingSilently(int value) {
		instance.offsetSwimming = heartOffsetValue(value);
	}

	static void setOffsetFlyingSilently(int value) {
		instance.offsetFlying = heartOffsetValue(value);
	}

	private static int heartOffsetValue(int value) {
		return (int) sanitize(value, DEFAULT_HEART_OFFSET, MIN_HEART_OFFSET, MAX_HEART_OFFSET);
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

	/**
	 * Parses JSON into a clamped config, or null for empty input;
	 * package-private for tests. Legacy pre-0.4.9 fields no longer exist on
	 * the class (Gson silently ignores them), so they are pulled off the raw
	 * JSON tree and folded into their 0.4.9 replacements before the
	 * one-time version migration runs.
	 */
	static HeartsPlusConfig parse(String json) {
		HeartsPlusConfig read = GSON.fromJson(json, HeartsPlusConfig.class);
		if (read == null) {
			return null;
		}
		// Files written before 0.4.7 carry no configVersion key, but Gson has
		// already filled the field with the current-version initializer, so the
		// key's presence must be checked on the raw JSON tree.
		JsonObject tree = JsonParser.parseString(json).getAsJsonObject();
		int version = tree.has("configVersion") ? Math.min(read.configVersion, CONFIG_VERSION) : 0;
		read.configVersion = version;
		if (version < 2) {
			if (tree.has("heartOffset")) {
				read.offsetStanding = tree.get("heartOffset").getAsInt();
			} else if (version < 1) {
				// An old file without heartOffset stored the 0.4.6 default;
				// Gson has filled the new default (0), which must not shift.
				read.offsetStanding = PREVIOUS_DEFAULT_HEART_OFFSET;
			}
			if (tree.has("showBehindBlocks")) {
				// The 0.4.8 toggle migrates to the slider: false drops the
				// see-through pass entirely, true keeps the old half alpha.
				read.wallOpacity = tree.get("showBehindBlocks").getAsBoolean() ? 50 : 0;
			}
			// No showBehindBlocks key: the old default true - the 50
			// initializer already matches. followSmoothness and the three
			// non-standing pose offsets start at their defaults as well.
		}
		read.clamp();
		read.migrate(version);
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
		wallOpacity = (int) sanitize(wallOpacity, DEFAULT_WALL_OPACITY, MIN_WALL_OPACITY, MAX_WALL_OPACITY);
		followSmoothness = (int) sanitize(followSmoothness, DEFAULT_FOLLOW_SMOOTHNESS, MIN_FOLLOW_SMOOTHNESS, MAX_FOLLOW_SMOOTHNESS);
		offsetStanding = heartOffsetValue(offsetStanding);
		offsetSneaking = heartOffsetValue(offsetSneaking);
		offsetSwimming = heartOffsetValue(offsetSwimming);
		offsetFlying = heartOffsetValue(offsetFlying);
	}

	/**
	 * One-time migration to the current schema. Two versions existed before:
	 * <ul>
	 * <li>pre-0.4.7 (no version key): the anchor absorbed the old 10px default
	 * lift (2.10 + 10 × 0.025 = 2.35), so the stored offset shifts down by 10
	 * to render at exactly its old height; a pre-0.4.4 -10 first lands on the
	 * 0.4.6 position per the earlier migration rule.</li>
	 * <li>0.4.7/0.4.8 (version 1): the single heartOffset is the standing
	 * offset verbatim (parse() has already moved it); showBehindBlocks became
	 * the wallOpacity slider in parse(); followSmoothness and the other three
	 * pose offsets start at their defaults.</li>
	 * </ul>
	 * Every save afterwards writes the current version, so no step can ever be
	 * applied a second time to the same file.
	 */
	private void migrate(int fromVersion) {
		if (fromVersion >= CONFIG_VERSION) {
			return;
		}
		migrated = true;
		if (fromVersion < 1) {
			if (this.offsetStanding == LEGACY_HEART_OFFSET) {
				// Land on the position the legacy default actually rendered at
				// in 0.4.6 (anchor 2.10 + 0), not on the new default height.
				this.offsetStanding = LEGACY_DEFAULT_LANDING;
			}
			this.offsetStanding -= ANCHOR_ABSORBED_PIXELS;
		}
		// Hand-edited files can carry out-of-range values that clamp() already
		// folded before the shift; re-clamp so nothing off-range persists.
		this.offsetStanding = heartOffsetValue(this.offsetStanding);
		this.configVersion = CONFIG_VERSION;
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
