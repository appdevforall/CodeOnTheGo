package com.itsaky.androidide.quickbuild.runtime;

/**
 * The boot-time decision of whether a persisted payload supersedes the baked baseline.
 *
 * Extracted from {@link PayloadStore}'s boot path so it is JVM-testable: the classloader half of that path is dalvik-only, but this gate is not, and it is the S7 fix. A rebaseline can leave the baseline dex byte-identical (manifest/asset-only change), so the fingerprint alone would adopt the previous epoch's persisted payload over the fresh, strictly newer baseline and boot superseded code - only gating on the STAMPED generation, not a constant 0, prevents that.
 */
final class PersistedSelection {

	/**
	 * Loads the persisted payload and gates it against the stamped baseline generation.
	 *
	 * @param stampedBaselineGeneration
	 *            the baked baseline's stamped generation ({@link BaselineGeneration}); only a strictly newer persisted payload may replace it
	 * @param store
	 *            the persisted-payload store found at boot
	 * @param baselineFingerprint
	 *            the running baseline's fingerprint, which {@link PayloadPersistence#load} keys the store on
	 * @return the persisted payload to boot, or null to boot the baked baseline
	 */
	static PayloadPersistence.Loaded selectPersisted(long stampedBaselineGeneration,
			PayloadPersistence store, String baselineFingerprint) {
		PayloadPersistence.Loaded loaded = store.load(baselineFingerprint);
		if (loaded == null || !Generations.accepts(stampedBaselineGeneration, loaded.generation)) {
			return null;
		}
		return loaded;
	}

	private PersistedSelection() {}
}
