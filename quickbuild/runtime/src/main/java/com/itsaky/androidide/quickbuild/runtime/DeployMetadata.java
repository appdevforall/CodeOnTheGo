package com.itsaky.androidide.quickbuild.runtime;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Parsed form of the {@code metadataJson} argument of {@code IQuickBuildTarget.onPayload}.
 *
 * Schema is in quickbuild/core/README.md. Unknown fields are ignored so the host can extend the schema without breaking installed proxy apps.
 */
final class DeployMetadata {

	static final String REASON_UNKNOWN = "unknown";

	/**
	 * Parses the deploy metadata, filling missing fields with safe defaults (null entry, empty asset list, {@link #REASON_UNKNOWN}).
	 *
	 * @throws IllegalArgumentException
	 *             on malformed JSON, which the caller treats as a bad payload.
	 */
	static DeployMetadata parse(String json) {
		Map<String, Object> obj = MiniJson.parseObject(json);
		return new DeployMetadata(
				asString(obj.get("entryActivity")),
				asStringList(obj.get("changedAssets")),
				asString(obj.get("reason")),
				"true".equals(obj.get("restart")));
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

	/**
	 * True when the recompiled set touched a service, provider, or custom Application class, so the runtime must persist the payload, ack, and exit instead of hot-swapping. On the wire this is the string {@code "restart": "true"}, per the MiniJson strings-only convention.
	 */
	final boolean restart;

	DeployMetadata(String entryActivity, List<String> changedAssets, String reason) {
		this(entryActivity, changedAssets, reason, false);
	}

	DeployMetadata(String entryActivity, List<String> changedAssets, String reason,
			boolean restart) {
		this.entryActivity = entryActivity;
		this.changedAssets = changedAssets == null
				? Collections.<String> emptyList()
				: Collections.unmodifiableList(changedAssets);
		this.reason = reason == null ? REASON_UNKNOWN : reason;
		this.restart = restart;
	}
}
