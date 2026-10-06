package com.heartsplus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Clamping and round-trip behaviour of the JSON config. */
class HeartsPlusConfigTest {

	@Test
	void outOfRangeValuesAreClamped() {
		String json = HeartsPlusConfig.serialize(HeartsPlusConfig.parse(
				"{\"scale\": 99.0, \"renderDistanceBlocks\": 1.0, \"heartOffset\": 100}"));

		assertTrue(json.contains("\"scale\": 4.0"), json);
		assertTrue(json.contains("\"renderDistanceBlocks\": 8.0"), json);
		assertTrue(json.contains("\"heartOffset\": 40"), json);
	}

	@Test
	void nanValuesFallBackToDefaults() {
		String json = HeartsPlusConfig.serialize(HeartsPlusConfig.parse(
				"{\"scale\": NaN, \"renderDistanceBlocks\": NaN}"));

		assertTrue(json.contains("\"scale\": 1.0"), json);
		assertTrue(json.contains("\"renderDistanceBlocks\": 128.0"), json);
	}

	@Test
	void defaultsMatchTheDocumentedValues() {
		String json = HeartsPlusConfig.serialize(HeartsPlusConfig.parse("{}"));

		assertTrue(json.contains("\"modEnabled\": true"), json);
		assertTrue(json.contains("\"showBehindBlocks\": true"), json);
		assertTrue(json.contains("\"blinkAnimation\": true"), json);
		assertTrue(json.contains("\"heartOffset\": 0"), json);
		assertFalse(json.contains("showSneakingPlayers"), json);
	}

	@Test
	void legacyMinusTenOffsetMigratesToZero() {
		// -10 was the old default compensating the higher anchor; with the
		// anchor lowered it must read as "the old default position" = 0.
		String json = HeartsPlusConfig.serialize(HeartsPlusConfig.parse("{\"heartOffset\": -10}"));

		assertTrue(json.contains("\"heartOffset\": 0"), json);
	}

	@Test
	void roundTripPreservesLegalValues() {
		String json = HeartsPlusConfig.serialize(HeartsPlusConfig.parse(
				"{\"modEnabled\": false, \"showBehindBlocks\": true, \"scale\": 2.5, \"renderDistanceBlocks\": 64.0, \"heartOffset\": -5}"));

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
