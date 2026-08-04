package com.itsaky.androidide.quickbuild.runtime;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Parsed form of the {@code metadataJson} argument of {@code IQuickBuildTarget.onPayload}.
 *
 * The fields below are the schema: this class is its only reader, and the host writes it from
 * {@code PayloadDeployer.metadata}. Unknown fields are ignored so the host can extend the schema
 * without breaking installed proxy apps.
 */
final class DeployMetadata {

	/** {@link #reason} when the host sent none, so the field is never null. */
	static final String REASON_UNKNOWN = "unknown";

	/**
	 * Parses the deploy metadata, filling missing fields with safe defaults (null entry, empty
	 * asset list, {@link #REASON_UNKNOWN}).
	 *
	 * @param json the {@code metadataJson} argument of {@code onPayload}; must be a JSON object
	 * @return the parsed metadata, with every absent or wrongly-typed field defaulted
	 * @throws IllegalArgumentException on malformed JSON, which the caller treats as a bad payload
	 */
	static DeployMetadata parse(String json) {
		Map<String, Object> obj = MiniJson.parseObject(json);
		return new DeployMetadata(
				asString(obj.get("entryActivity")),
				asStringList(obj.get("changedAssets")),
				asString(obj.get("reason")),
				"true".equals(obj.get("restart")));
	}

	/**
	 * Narrows a parsed JSON value to a string, so an unexpected type defaults instead of throwing.
	 *
	 * @param value the raw value {@link MiniJson} produced, possibly null
	 * @return {@code value} as a string, or null when absent or of another type
	 */
	private static String asString(Object value) {
		return value instanceof String ? (String) value : null;
	}

	/**
	 * Narrows a parsed JSON value to a list, so an unexpected type defaults instead of throwing.
	 *
	 * The element cast is unchecked but safe: {@link MiniJson} only ever produces string elements.
	 *
	 * @param value the raw value {@link MiniJson} produced, possibly null
	 * @return {@code value} as a list, or null when absent or of another type
	 */
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
	 * True when the recompiled set touched a service, provider, or custom Application class, so
	 * the runtime must persist the payload, ack, and exit instead of hot-swapping. On the wire
	 * this is the string {@code "restart": "true"}, per the MiniJson strings-only convention.
	 */
	final boolean restart;

	/**
	 * Convenience overload for a hot-swappable deploy, i.e. {@code restart} false.
	 *
	 * @param entryActivity user activity class to launch when none is alive; null when unknown
	 * @param changedAssets relative asset paths in this payload; null becomes the empty list
	 * @param reason the deploy reason; null becomes {@link #REASON_UNKNOWN}
	 */
	DeployMetadata(String entryActivity, List<String> changedAssets, String reason) {
		this(entryActivity, changedAssets, reason, false);
	}

	/**
	 * Stores the metadata, defaulting the nullable fields so no consumer has to null-check.
	 *
	 * @param entryActivity user activity class to launch when none is alive; null when unknown
	 * @param changedAssets relative asset paths in this payload; null becomes the empty list, and
	 *     a non-null list is wrapped unmodifiable rather than copied
	 * @param reason the deploy reason; null becomes {@link #REASON_UNKNOWN}
	 * @param restart true to persist-and-exit instead of hot-swapping, per {@link #restart}
	 */
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
