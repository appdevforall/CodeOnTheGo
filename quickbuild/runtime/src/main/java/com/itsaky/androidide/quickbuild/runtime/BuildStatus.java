package com.itsaky.androidide.quickbuild.runtime;

import java.util.Map;

/**
 * Parsed form of the {@code statusJson} argument of {@code IQuickBuildTarget.onBuildStatus}.
 *
 * Schema is in quickbuild/protocol/README.md. Every value is a string on the wire because {@link MiniJson} reads only strings. Unknown kinds parse to null and unknown fields are ignored, so CoGo can extend the schema without breaking installed proxy apps.
 */
final class BuildStatus {

	/** A compile failed; {@link #message} carries the first error's first line. */
	static final String KIND_BUILD_FAILED = "build_failed";

	/** A build succeeded, so any error banner can come down; carries no further fields. */
	static final String KIND_BUILD_OK = "build_ok";

	/** A build started; only {@link #runningGeneration} is meaningful. */
	static final String KIND_BUILDING = "building";

	/** An update is built but its reinstall awaits a confirm only CoGo can show; no further fields. */
	static final String KIND_REINSTALL_PENDING = "reinstall_pending";

	/**
	 * Parses one build status message.
	 *
	 * @param json
	 *            the {@code statusJson} argument of {@code onBuildStatus}; must be a JSON object
	 * @return the parsed status, or null for a kind this runtime does not know; unknown kinds are ignored, not errors
	 * @throws IllegalArgumentException
	 *             on malformed JSON, for the caller to log and drop
	 */
	static BuildStatus parse(String json) {
		Map<String, Object> obj = MiniJson.parseObject(json);
		String kind = asString(obj.get("kind"));
		if (KIND_BUILD_OK.equals(kind)) {
			return new BuildStatus(KIND_BUILD_OK, null, 0, -1);
		}
		if (KIND_BUILD_FAILED.equals(kind)) {
			return new BuildStatus(
					KIND_BUILD_FAILED,
					asString(obj.get("message")),
					Math.max(0, asInt(obj.get("moreErrors"), 0)),
					-1);
		}
		if (KIND_BUILDING.equals(kind)) {
			return new BuildStatus(KIND_BUILDING, null, 0, asLong(obj.get("runningGeneration"), -1));
		}
		if (KIND_REINSTALL_PENDING.equals(kind)) {
			return new BuildStatus(KIND_REINSTALL_PENDING, null, 0, -1);
		}
		return null;
	}

	/**
	 * Reads a wire value as an int, since every JSON value here is a string.
	 *
	 * @param value
	 *            the raw value {@link MiniJson} produced, possibly null
	 * @param fallback
	 *            returned when the value is absent, not a string, or not a number
	 * @return the parsed int, or {@code fallback}
	 */
	private static int asInt(Object value, int fallback) {
		if (!(value instanceof String)) {
			return fallback;
		}
		try {
			return Integer.parseInt((String) value);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/**
	 * Reads a wire value as a long, for the generation counter.
	 *
	 * @param value
	 *            the raw value {@link MiniJson} produced, possibly null
	 * @param fallback
	 *            returned when the value is absent, not a string, or not a number
	 * @return the parsed long, or {@code fallback}
	 */
	private static long asLong(Object value, long fallback) {
		if (!(value instanceof String)) {
			return fallback;
		}
		try {
			return Long.parseLong((String) value);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/**
	 * Narrows a parsed JSON value to a string, so an unexpected type defaults instead of throwing.
	 *
	 * @param value
	 *            the raw value {@link MiniJson} produced, possibly null
	 * @return {@code value} as a string, or null when absent or of another type
	 */
	private static String asString(Object value) {
		return value instanceof String ? (String) value : null;
	}

	/** One of the KIND_ constants; never anything else, since an unknown kind parses to null. */
	final String kind;

	/** First line of the first error message, or null. */
	final String message;

	/** How many further errors the build reported beyond the first, >= 0. */
	final int moreErrors;

	/** For {@link #KIND_BUILDING}: the generation the app still runs, or -1 if unknown. */
	final long runningGeneration;

	/**
	 * Stores one already-defaulted status; only {@link #parse} constructs these.
	 *
	 * @param kind
	 *            one of the KIND_ constants
	 * @param message
	 *            first line of the first error message, or null
	 * @param moreErrors
	 *            further error count beyond the first, already clamped to >= 0
	 * @param runningGeneration
	 *            generation still running, for {@link #KIND_BUILDING}, else -1
	 */
	private BuildStatus(String kind, String message, int moreErrors, long runningGeneration) {
		this.kind = kind;
		this.message = message;
		this.moreErrors = moreErrors;
		this.runningGeneration = runningGeneration;
	}
}
