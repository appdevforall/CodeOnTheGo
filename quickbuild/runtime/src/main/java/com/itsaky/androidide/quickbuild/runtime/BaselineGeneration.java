package com.itsaky.androidide.quickbuild.runtime;

import java.io.InputStream;

/**
 * Parses the baseline-generation stamp asset the Gradle plugin writes next to the baseline payload dex.
 *
 * The proxy app build stamps the generation the host allocated for the baseline, drawn from the same persistent counter that numbers hot deploys. Booting the baseline at that number makes a post-rebaseline reconnect read in-sync by construction, and keeps every later hot deploy strictly newer. A missing or malformed stamp parses as {@link #UNSTAMPED}, so an APK built by an older plugin behaves exactly as before stamping existed.
 */
final class BaselineGeneration {

	/** The fallback: an unstamped baseline is generation 0, as before stamping existed. */
	static final long UNSTAMPED = 0L;

	/**
	 * Parses stamp text into a generation.
	 *
	 * @param text
	 *            the asset's content; surrounding whitespace is tolerated
	 * @return the parsed generation, or {@link #UNSTAMPED} for null, non-numeric or negative input - the host's counter only hands out positive numbers, so a negative stamp is corruption, and adopting it would let payloads at or below generation 0 replace the baseline
	 */
	static long parse(String text) {
		if (text == null) {
			return UNSTAMPED;
		}
		try {
			long value = Long.parseLong(text.trim());
			return value < 0 ? UNSTAMPED : value;
		} catch (NumberFormatException error) {
			return UNSTAMPED;
		}
	}

	/**
	 * Reads and parses the stamp from an asset stream, closing it.
	 *
	 * @param in
	 *            the stamp asset's stream, or null when the APK carries none
	 * @return the stamped generation, or {@link #UNSTAMPED} when the stream is null or unreadable
	 */
	static long read(InputStream in) {
		if (in == null) {
			return UNSTAMPED;
		}
		try {
			return parse(new String(Streams.readFully(in), "UTF-8"));
		} catch (Throwable error) {
			RuntimeLog.w("unreadable baseline-generation stamp: " + error);
			return UNSTAMPED;
		} finally {
			Streams.closeQuietly(in);
		}
	}

	private BaselineGeneration() {}
}
