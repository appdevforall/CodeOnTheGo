package com.itsaky.androidide.quickbuild.runtime;

import java.util.Map;

/**
 * Parsed form of the {@code statusJson} argument of {@code IQuickBuildTarget.onBuildStatus} (schema in quick-build/README.md). All values are strings on the wire because {@link MiniJson} deliberately reads only strings. Plain Java so the parsing is JVM-unit-testable; unknown kinds parse to null and unknown fields are ignored, so CoGo can extend the schema without breaking already-installed test apps.
 */
final class BuildStatus {

	static final String KIND_BUILD_FAILED = "build_failed";
	static final String KIND_BUILD_OK = "build_ok";

	/**
	 * Parses a build status. Returns null for a kind this runtime does not know (the defensive-versioning contract: ignore, don't fail); malformed JSON throws {@link IllegalArgumentException} for the caller to log and drop.
	 */
	static BuildStatus parse(String json) {
		Map<String, Object> obj = MiniJson.parseObject(json);
		String kind = asString(obj.get("kind"));
		if (KIND_BUILD_OK.equals(kind)) {
			return new BuildStatus(KIND_BUILD_OK, null, -1, -1, null, 0);
		}
		if (KIND_BUILD_FAILED.equals(kind)) {
			return new BuildStatus(
					KIND_BUILD_FAILED,
					asString(obj.get("file")),
					asInt(obj.get("line"), -1),
					asInt(obj.get("column"), -1),
					asString(obj.get("message")),
					Math.max(0, asInt(obj.get("moreErrors"), 0)));
		}
		return null;
	}

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

	private static String asString(Object value) {
		return value instanceof String ? (String) value : null;
	}

	/** {@link #KIND_BUILD_FAILED} or {@link #KIND_BUILD_OK}; never anything else. */
	final String kind;

	/** Failing source file (host-side absolute path), or null. */
	final String file;

	/** 1-based line of the first error, or -1 when unknown. */
	final int line;

	/** 1-based column of the first error, or -1 when unknown. */
	final int column;

	/** First line of the first error message, or null. */
	final String message;

	/** How many further errors the build reported beyond the first, >= 0. */
	final int moreErrors;

	private BuildStatus(String kind, String file, int line, int column, String message,
			int moreErrors) {
		this.kind = kind;
		this.file = file;
		this.line = line;
		this.column = column;
		this.message = message;
		this.moreErrors = moreErrors;
	}
}
