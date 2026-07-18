package com.itsaky.androidide.quickbuild.runtime;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Parsed form of the {@code metadataJson} argument of {@code IQuickBuildTarget.onPayload} (schema in quick-build/README.md). Plain Java so the parsing is JVM-unit-testable; unknown fields are ignored so the host can extend the schema without breaking already-installed test apps.
 */
final class DeployMetadata {

	static final String REASON_UNKNOWN = "unknown";

	/**
	 * Parses the deploy metadata. Missing fields fall back to safe defaults (null entry, empty asset list, {@link #REASON_UNKNOWN}); malformed JSON throws {@link IllegalArgumentException} for the caller to handle as a bad payload.
	 */
	static DeployMetadata parse(String json) {
		Map<String, Object> obj = MiniJson.parseObject(json);
		return new DeployMetadata(
				asString(obj.get("entryActivity")),
				asStringList(obj.get("changedAssets")),
				asString(obj.get("reason")));
	}

	private static String asString(Object value) {
		return value instanceof String ? (String) value : null;
	}

	@SuppressWarnings("unchecked")
	private static List<String> asStringList(Object value) {
		return value instanceof List ? (List<String>) value : null;
	}

	/** Fully-qualified USER activity class to launch when no activity is alive; may be null. */
	final String entryActivity;

	/** Asset paths (relative, e.g. "data/levels.json") carried by the assets payload. Never null. */
	final List<String> changedAssets;

	/** Why this payload exists: code|resources|assets|mixed|forced, or {@link #REASON_UNKNOWN}. */
	final String reason;

	DeployMetadata(String entryActivity, List<String> changedAssets, String reason) {
		this.entryActivity = entryActivity;
		this.changedAssets = changedAssets == null
				? Collections.<String> emptyList()
				: Collections.unmodifiableList(changedAssets);
		this.reason = reason == null ? REASON_UNKNOWN : reason;
	}
}
