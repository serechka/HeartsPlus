package com.heartsplus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Clamping, round-trip and the one-time 0.4.7 height migration of the JSON config. */
class HeartsPlusConfigTest {
	/** A current-schema file: versioned JSON skips the pre-0.4.7 migration. */
	private static final String CURRENT_VERSION = "\"configVersion\": 1";

	private static String parseAndSerialize(String json) {
		return HeartsPlusConfig.serialize(HeartsPlusConfig.parse(json));
	}

	@Test
	void outOfRangeValuesAreClamped() {
		String json = parseAndSerialize(
				"{" + CURRENT_VERSION + ", \"scale\": 99.0, \"renderDistanceBlocks\": 1.0, \"heartOffset\": 100}");

		assertTrue(json.contains("\"scale\": 4.0"), json);
		assertTrue(json.contains("\"renderDistanceBlocks\": 8.0"), json);
		assertTrue(json.contains("\"heartOffset\": 40"), json);
	}

	@Test
	void nanValuesFallBackToDefaults() {
		String json = parseAndSerialize(
				"{" + CURRENT_VERSION + ", \"scale\": NaN, \"renderDistanceBlocks\": NaN}");

		assertTrue(json.contains("\"scale\": 1.0"), json);
		assertTrue(json.contains("\"renderDistanceBlocks\": 128.0"), json);
	}

	@Test
	void defaultsMatchTheDocumentedValues() {
		String json = parseAndSerialize("{" + CURRENT_VERSION + "}");

		assertTrue(json.contains("\"modEnabled\": true"), json);
		assertTrue(json.contains("\"showBehindBlocks\": true"), json);
		assertTrue(json.contains("\"blinkAnimation\": true"), json);
		assertTrue(json.contains("\"heartOffset\": 0"), json);
		assertFalse(json.contains("showSneakingPlayers"), json);
	}

	/**
	 * 0.4.7 folded the old 10px default lift into the anchor, so every stored
	 * offset shifts down by 10 to keep rendering at its old height (the
	 * pre-0.4.4 -10 first lands on the 0.4.6 position, then shifts too).
	 */
	@Test
	void theHeightShiftPreservesEveryStoredRender() {
		// Legacy -10 -> 0.4.6 landing 0 -> shifted -10.
		assertTrue(parseAndSerialize("{\"heartOffset\": -10}").contains("\"heartOffset\": -10"));
		assertTrue(parseAndSerialize("{\"heartOffset\": 0}").contains("\"heartOffset\": -10"));
		assertTrue(parseAndSerialize("{\"heartOffset\": 10}").contains("\"heartOffset\": 0"));
		assertTrue(parseAndSerialize("{\"heartOffset\": 40}").contains("\"heartOffset\": 30"));
		assertTrue(parseAndSerialize("{\"heartOffset\": -20}").contains("\"heartOffset\": -30"));
	}

	@Test
	void anOldFileWithoutHeartOffsetLandsOnTheNewDefault() {
		// It stored the 0.4.6 default (10), which the shift turns into 0 —
		// exactly the new default, so the file reads as freshly created.
		String json = parseAndSerialize("{}");

		assertTrue(json.contains("\"heartOffset\": 0"), json);
		assertTrue(json.contains(CURRENT_VERSION), json);
	}

	@Test
	void aHandEditedOldFileStaysInRangeAfterClampAndShift() {
		// Clamp folds an out-of-range stored value before the shift, so the
		// shift itself must re-clamp: nothing off-range may ever persist.
		String json = parseAndSerialize("{\"heartOffset\": -100}");

		assertTrue(json.contains("\"heartOffset\": -40"), json);
		assertTrue(json.contains(CURRENT_VERSION), json);
	}

	@Test
	void theShiftRunsExactlyOncePerFile() {
		// Migrate a 0.4.6 file, write it back, read it again: the written
		// schema version must suppress any second shift.
		String migrated = parseAndSerialize("{\"heartOffset\": 10}");
		assertTrue(migrated.contains(CURRENT_VERSION), migrated);
		assertTrue(migrated.contains("\"heartOffset\": 0"), migrated);

		String reread = parseAndSerialize(migrated);
		assertTrue(reread.contains("\"heartOffset\": 0"), reread);
	}

	@Test
	void roundTripPreservesLegalValues() {
		String json = parseAndSerialize(
				"{" + CURRENT_VERSION + ", \"modEnabled\": false, \"showBehindBlocks\": true, \"scale\": 2.5, \"renderDistanceBlocks\": 64.0, \"heartOffset\": -5}");

		assertTrue(json.contains("\"modEnabled\": false"), json);
		assertTrue(json.contains("\"showBehindBlocks\": true"), json);
		assertTrue(json.contains("\"scale\": 2.5"), json);
		assertTrue(json.contains("\"renderDistanceBlocks\": 64.0"), json);
		assertTrue(json.contains("\"heartOffset\": -5"), json);
	}

	@Test
	void emptyDocumentParsesToNull() {
		assertNull(HeartsPlusConfig.parse("null"));
	}
}
