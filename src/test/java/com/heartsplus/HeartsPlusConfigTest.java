package com.heartsplus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Clamping, round-trip, malformed-file fallback and the one-time schema migrations (0.4.7 heights, 0.4.9 slider split) of the JSON config. */
class HeartsPlusConfigTest {
	/** A 0.4.7/0.4.8 schema file: version 1 still runs the 0.4.9 slider migration. */
	private static final String VERSION_1 = "\"configVersion\": 1";
	/** The current schema: no migration may touch it. */
	private static final String CURRENT_VERSION = "\"configVersion\": 2";

	private static String parseAndSerialize(String json) {
		return HeartsPlusConfig.serialize(HeartsPlusConfig.parse(json));
	}

	@Test
	void outOfRangeValuesAreClamped() {
		String json = parseAndSerialize(
				"{" + CURRENT_VERSION + ", \"scale\": 99.0, \"renderDistanceBlocks\": 1.0, \"wallOpacity\": 999, \"offsetStanding\": 100}");

		assertTrue(json.contains("\"scale\": 4.0"), json);
		assertTrue(json.contains("\"renderDistanceBlocks\": 8.0"), json);
		assertTrue(json.contains("\"wallOpacity\": 100"), json);
		assertTrue(json.contains("\"offsetStanding\": 40"), json);
	}

	@Test
	void nanValuesFallBackToDefaults() {
		// Gson cannot read NaN into an int field at all, so the NaN fallback
		// only ever applies to the double fields.
		String json = parseAndSerialize(
				"{" + CURRENT_VERSION + ", \"scale\": NaN, \"renderDistanceBlocks\": NaN}");

		assertTrue(json.contains("\"scale\": 1.0"), json);
		assertTrue(json.contains("\"renderDistanceBlocks\": 128.0"), json);
	}

	@Test
	void defaultsMatchTheDocumentedValues() {
		String json = parseAndSerialize("{" + CURRENT_VERSION + "}");

		assertTrue(json.contains("\"modEnabled\": true"), json);
		assertTrue(json.contains("\"wallOpacity\": 50"), json);
		assertTrue(json.contains("\"followSmoothness\": 50"), json);
		assertTrue(json.contains("\"offsetStanding\": 0"), json);
		assertTrue(json.contains("\"offsetSneaking\": 0"), json);
		assertTrue(json.contains("\"offsetSwimming\": 0"), json);
		assertTrue(json.contains("\"offsetFlying\": 0"), json);
		assertTrue(json.contains("\"blinkAnimation\": true"), json);
		// The 0.4.8 toggle and the single offset are gone from the schema.
		assertFalse(json.contains("showBehindBlocks"), json);
		assertFalse(json.contains("heartOffset"), json);
		assertFalse(json.contains("showSneakingPlayers"), json);
	}

	/**
	 * 0.4.7 folded the old 10px default lift into the anchor, so every stored
	 * offset shifts down by 10 to keep rendering at its old height (the
	 * pre-0.4.4 -10 first lands on the 0.4.6 position, then shifts too). In
	 * the 0.4.9 schema the shifted value lands in offsetStanding.
	 */
	@Test
	void theHeightShiftPreservesEveryStoredRender() {
		// Legacy -10 -> 0.4.6 landing 0 -> shifted -10.
		assertTrue(parseAndSerialize("{\"heartOffset\": -10}").contains("\"offsetStanding\": -10"));
		assertTrue(parseAndSerialize("{\"heartOffset\": 0}").contains("\"offsetStanding\": -10"));
		assertTrue(parseAndSerialize("{\"heartOffset\": 10}").contains("\"offsetStanding\": 0"));
		assertTrue(parseAndSerialize("{\"heartOffset\": 40}").contains("\"offsetStanding\": 30"));
		assertTrue(parseAndSerialize("{\"heartOffset\": -20}").contains("\"offsetStanding\": -30"));
	}

	@Test
	void anOldFileWithoutHeartOffsetLandsOnTheNewDefault() {
		// It stored the 0.4.6 default (10), which the shift turns into 0 —
		// exactly the new default, so the file reads as freshly created.
		String json = parseAndSerialize("{}");

		assertTrue(json.contains("\"offsetStanding\": 0"), json);
		assertTrue(json.contains(CURRENT_VERSION), json);
	}

	@Test
	void aHandEditedOldFileStaysInRangeAfterClampAndShift() {
		// Clamp folds an out-of-range stored value before the shift, so the
		// shift itself must re-clamp: nothing off-range may ever persist.
		String json = parseAndSerialize("{\"heartOffset\": -100}");

		assertTrue(json.contains("\"offsetStanding\": -40"), json);
		assertTrue(json.contains(CURRENT_VERSION), json);
	}

	@Test
	void theShiftRunsExactlyOncePerFile() {
		// Migrate a 0.4.6 file, write it back, read it again: the written
		// schema version must suppress any second shift.
		String migrated = parseAndSerialize("{\"heartOffset\": 10}");
		assertTrue(migrated.contains(CURRENT_VERSION), migrated);
		assertTrue(migrated.contains("\"offsetStanding\": 0"), migrated);

		String reread = parseAndSerialize(migrated);
		assertTrue(reread.contains("\"offsetStanding\": 0"), reread);
	}

	/**
	 * The 0.4.9 slider split (owner spec C7): the old showBehindBlocks toggle
	 * becomes the wall opacity (false drops the see-through pass entirely,
	 * true keeps the old half dimming) and heartOffset becomes the standing
	 * height; the other three poses and the smoothing start at their defaults.
	 */
	@Test
	void theSliderSplitMigratesToggleAndOffset() {
		String json = parseAndSerialize("{" + VERSION_1 + ", \"showBehindBlocks\": false, \"heartOffset\": 5}");

		assertTrue(json.contains(CURRENT_VERSION), json);
		assertTrue(json.contains("\"wallOpacity\": 0"), json);
		assertTrue(json.contains("\"offsetStanding\": 5"), json);
		assertTrue(json.contains("\"offsetSneaking\": 0"), json);
		assertTrue(json.contains("\"offsetSwimming\": 0"), json);
		assertTrue(json.contains("\"offsetFlying\": 0"), json);
		assertTrue(json.contains("\"followSmoothness\": 50"), json);
		assertFalse(json.contains("showBehindBlocks"), json);
	}

	/** The toggle's true branch lands on the old 0x80 half dimming: 50 percent. */
	@Test
	void aToggledOnOldFileKeepsItsHalfOpacity() {
		String json = parseAndSerialize("{" + VERSION_1 + ", \"showBehindBlocks\": true}");

		assertTrue(json.contains("\"wallOpacity\": 50"), json);
		assertFalse(json.contains("showBehindBlocks"), json);
	}

	/** A version 1 file carries the already-shifted offset: no second 0.4.7 shift. */
	@Test
	void aVersionOneFileSkipsTheHeightShift() {
		String json = parseAndSerialize("{" + VERSION_1 + ", \"heartOffset\": 7}");

		assertTrue(json.contains("\"offsetStanding\": 7"), json);
	}

	/**
	 * An old file migrates through both versions in one read (0.4.7 shift
	 * first, then the slider split) and never re-runs either afterwards.
	 */
	@Test
	void theFullMigrationChainIsIdempotent() {
		String migrated = parseAndSerialize("{\"heartOffset\": 20, \"showBehindBlocks\": false}");
		assertTrue(migrated.contains(CURRENT_VERSION), migrated);
		assertTrue(migrated.contains("\"offsetStanding\": 10"), migrated);
		assertTrue(migrated.contains("\"wallOpacity\": 0"), migrated);

		String reread = parseAndSerialize(migrated);
		assertEquals(migrated, reread);
	}

	@Test
	void roundTripPreservesLegalValues() {
		String json = parseAndSerialize(
				"{" + CURRENT_VERSION + ", \"modEnabled\": false, \"wallOpacity\": 30, \"followSmoothness\": 20, "
						+ "\"scale\": 2.5, \"renderDistanceBlocks\": 64.0, \"offsetStanding\": -5, \"offsetFlying\": 15}");

		assertTrue(json.contains("\"modEnabled\": false"), json);
		assertTrue(json.contains("\"wallOpacity\": 30"), json);
		assertTrue(json.contains("\"followSmoothness\": 20"), json);
		assertTrue(json.contains("\"scale\": 2.5"), json);
		assertTrue(json.contains("\"renderDistanceBlocks\": 64.0"), json);
		assertTrue(json.contains("\"offsetStanding\": -5"), json);
		assertTrue(json.contains("\"offsetFlying\": 15"), json);
	}

	@Test
	void emptyDocumentParsesToNull() {
		assertNull(HeartsPlusConfig.parse("null"));
	}

	/**
	 * A hand-edited file with a wrong-typed value in a double field must
	 * degrade to the defaults, not crash the client on startup: Gson throws
	 * raw RuntimeExceptions for these, which the IOException/JsonParseException
	 * catch in load() never covered.
	 */
	@Test
	void aWrongTypedDoubleFieldDegradesToDefaults() {
		assertNull(HeartsPlusConfig.parse("{" + CURRENT_VERSION + ", \"scale\": \"abc\"}"));
		assertNull(HeartsPlusConfig.parse("{" + CURRENT_VERSION + ", \"renderDistanceBlocks\": [8]}"));
	}

	/** A garbage legacy value degrades to "no key": the 0.4.6 default lands on 0 like any other version-less file. */
	@Test
	void wrongTypedLegacyKeysFallBackInsteadOfCrashing() {
		String offset = parseAndSerialize("{\"heartOffset\": \"abc\"}");
		assertTrue(offset.contains("\"offsetStanding\": 0"), offset);
		assertTrue(offset.contains("\"wallOpacity\": 50"), offset);

		String toggle = parseAndSerialize("{\"showBehindBlocks\": {}}");
		assertTrue(toggle.contains("\"wallOpacity\": 50"), toggle);
	}

	/**
	 * The Follow Smoothing to rate/cap mapping (owner spec C1): 0 = off,
	 * the default 50 = 1.5x the old rate and double the old cap.
	 */
	@Test
	void theFollowSmoothingMappingHitsTheOwnerValues() {
		// The fresh parse defaults carry smoothness 50. The silent setters
		// keep the test off disk (the saving setters persist immediately).
		assertEquals(50, HeartsPlusConfig.getFollowSmoothness());
		assertEquals(12.0, HeartsPlusConfig.followRatePerSecond(), 1e-9);
		assertEquals(8.0, HeartsPlusConfig.followSpeedCapBlocksPerSecond(), 1e-9);

		// Off at zero.
		HeartsPlusConfig.setFollowSmoothnessSilently(0);
		assertEquals(0.0, HeartsPlusConfig.followRatePerSecond(), 1e-9);
		assertEquals(4.0, HeartsPlusConfig.followSpeedCapBlocksPerSecond(), 1e-9);

		// Linear endpoints: 1 point is the fastest chase, 100 the smoothest.
		HeartsPlusConfig.setFollowSmoothnessSilently(1);
		assertEquals(21.8, HeartsPlusConfig.followRatePerSecond(), 1e-9);
		HeartsPlusConfig.setFollowSmoothnessSilently(100);
		assertEquals(2.0, HeartsPlusConfig.followRatePerSecond(), 1e-9);
		assertEquals(12.0, HeartsPlusConfig.followSpeedCapBlocksPerSecond(), 1e-9);

		// Back to the default for the rest of the suite.
		HeartsPlusConfig.setFollowSmoothnessSilently(HeartsPlusConfig.DEFAULT_FOLLOW_SMOOTHNESS);
	}
}
