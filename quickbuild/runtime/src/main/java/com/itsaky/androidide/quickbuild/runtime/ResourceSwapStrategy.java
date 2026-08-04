package com.itsaky.androidide.quickbuild.runtime;

/**
 * How a resource-table payload is applied on this device, chosen once per process from the SDK
 * level.
 *
 * Free of android.* imports so the version routing is JVM-unit-testable.
 */
enum ResourceSwapStrategy {

	/** API 30+: ResourcesLoader/ResourcesProvider hot swap, the full-fidelity path. */
	RESOURCES_LOADER,

	/**
	 * API 28/29: no ResourcesLoader, so {@link LegacyResourceSwap} appends the relinked apk to
	 * the live AssetManager and the per-deploy activity recreate re-reads from it.
	 */
	LEGACY_ASSET_PATH,

	/**
	 * Below API 28: no mechanism this runtime supports, so resource payloads are ignored.
	 * Unreachable in practice, since the deploying CoGo host needs API 28+ on the same device.
	 */
	UNSUPPORTED;

	/**
	 * Maps an SDK level to its strategy; the levels are inlined (R and P) to keep this class
	 * android-free.
	 *
	 * @param sdkInt the device's {@code Build.VERSION.SDK_INT}, passed in by the caller so no
	 *     android.* symbol is referenced here
	 * @return the strategy this process must use for every resource payload it receives
	 */
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
