package com.itsaky.androidide.quickbuild.runtime;

/**
 * How a resource-table payload is applied on this device, selected once per process from the runtime SDK level (plan B5). Kept free of android.* so the version routing is JVM-unit-testable.
 */
enum ResourceSwapStrategy {

	/** API 30+: ResourcesLoader/ResourcesProvider hot swap, the full-fidelity path. */
	RESOURCES_LOADER,

	/**
	 * API 28/29: degraded shim. ResourcesLoader does not exist, so the relinked table is wrapped into an apk-shaped zip and appended to the live AssetManager via the hidden addAssetPath; the activity recreate every deploy already performs then re-reads values from the new table. See {@link LegacyResourceSwap}.
	 */
	LEGACY_ASSET_PATH,

	/**
	 * Below API 28: no mechanism this runtime supports; resource payloads are ignored (logged once). Unreachable in practice - the deploying CoGo host itself needs API 28+, and host and test app share one device.
	 */
	UNSUPPORTED;

	/** SDK levels inlined (Build.VERSION_CODES.R / .P) to keep this class android-free. */
	static ResourceSwapStrategy forSdk(int sdkInt) {
		if (sdkInt >= 30) {
			return RESOURCES_LOADER;
		}
		if (sdkInt >= 28) {
			return LEGACY_ASSET_PATH;
		}
		return UNSUPPORTED;
	}
}
