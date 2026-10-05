package com.heartsplus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single logger used across the mod so log lines can be traced back to HeartsPlus.
 */
public final class HeartsPlusLog {
	public static final Logger LOGGER = LoggerFactory.getLogger("HeartsPlus");

	private HeartsPlusLog() {
	}
}
