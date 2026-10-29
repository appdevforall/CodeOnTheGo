package com.itsaky.androidide.quickbuild.runtime;

import java.util.Map;

/**
 * Parsed form of the {@code metadataJson} argument of {@code IQuickBuildTarget.onPayload}.
 *
 * The fields below are the schema this class reads: the host writes it from {@code PayloadDeployer.metadata}, and writes more than this. Unknown fields are ignored, so the host can extend the schema without breaking installed proxy apps.
 */
final class DeployMetadata {

	/**
	 * Parses the deploy metadata, defaulting every absent or wrongly-typed field.
	 *
	 * @param json
	 *            the {@code metadataJson} argument of {@code onPayload}; must be a JSON object
	 * @return the parsed metadata, with a null entry activity and no restart when the fields are absent
	 * @throws IllegalArgumentException
	 *             on malformed JSON, which the caller treats as a bad payload
	 */
	static DeployMetadata parse(String json) {
		Map<String, Object> obj = MiniJson.parseObject(json);
		return new DeployMetadata(
				asString(obj.get("entryActivity")),
				"true".equals(obj.get("restart")));
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

	/**
	 * Fully-qualified USER entry activity class; may be null. Not launched by the runtime (a deploy with no live activity applies silently, so a save never takes the screen); kept on the wire for compatibility.
	 */
	final String entryActivity;

	/**
	 * True when the recompiled set touched a service, provider, or custom Application class, so the runtime must persist the payload, ack, and exit instead of hot-swapping. On the wire this is the string {@code "restart": "true"}, per the MiniJson strings-only convention.
	 */
	final boolean restart;

	/**
	 * @param entryActivity
	 *            user entry activity class, per {@link #entryActivity}; null when unknown
	 * @param restart
	 *            true to persist-and-exit instead of hot-swapping, per {@link #restart}
	 */
	DeployMetadata(String entryActivity, boolean restart) {
		this.entryActivity = entryActivity;
		this.restart = restart;
	}
}
