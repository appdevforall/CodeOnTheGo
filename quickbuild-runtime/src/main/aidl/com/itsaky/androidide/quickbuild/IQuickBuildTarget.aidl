package com.itsaky.androidide.quickbuild;

/**
 * Test-app side of the deploy channel (plan D1). CoGo calls this after a successful
 * quick build. Payloads travel as ParcelFileDescriptors; nothing touches shared storage.
 * The target accepts a payload only when {@code generation} is strictly newer than the
 * generation it currently runs.
 *
 * Versioning: CoGo and an installed test app can run DIFFERENT revisions of this
 * interface (the runtime AAR is baked into the test app at setup-build time). Only ever
 * APPEND methods at the end - never reorder or remove. An older test app's stub answers
 * an unknown transaction code with "not handled", and because the interface is oneway
 * the caller never notices; the message is simply ignored.
 */
oneway interface IQuickBuildTarget {

	/**
	 * Deliver generation {@code generation}.
	 *
	 * @param dexPayload      classes.dex containing ALL user classes + generated proxies,
	 *                        or null for a resources/assets-only deploy.
	 * @param resourcesPayload a resources.arsc table fd for ResourcesProvider.loadFromTable,
	 *                        or null when resources did not change.
	 * @param assetsPayload   a zip of changed asset files, or null.
	 * @param metadataJson    JSON: entry activity class, changed-asset paths, flags.
	 *                        Schema in quick-build/README.md.
	 */
	void onPayload(long generation, in @nullable ParcelFileDescriptor dexPayload,
			in @nullable ParcelFileDescriptor resourcesPayload,
			in @nullable ParcelFileDescriptor assetsPayload, String metadataJson);

	/**
	 * Build-status message (plan A1): tells the running test app that a quick build
	 * FAILED CoGo-side (a compile error never produces a payload, so without this the
	 * app would silently keep running old code with no user-visible signal), or that a
	 * build succeeded (clears a previously shown failure).
	 *
	 * @param statusJson JSON with string-only values; schema in quick-build/README.md.
	 *                   Unknown kinds and unknown fields are ignored by the runtime, so
	 *                   the schema can grow without breaking installed test apps.
	 */
	void onBuildStatus(String statusJson);
}
