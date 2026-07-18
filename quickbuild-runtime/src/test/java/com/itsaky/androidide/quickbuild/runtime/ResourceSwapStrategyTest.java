package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The plan-B5 version routing: 30+ = ResourcesLoader, 28/29 = the degraded addAssetPath shim, below 28 = unsupported.
 */
class ResourceSwapStrategyTest {

	@Test
	void api28And29UseLegacyAssetPath() {
		assertThat(ResourceSwapStrategy.forSdk(28)).isEqualTo(ResourceSwapStrategy.LEGACY_ASSET_PATH);
		assertThat(ResourceSwapStrategy.forSdk(29)).isEqualTo(ResourceSwapStrategy.LEGACY_ASSET_PATH);
	}

	@Test
	void api30AndAboveUseResourcesLoader() {
		assertThat(ResourceSwapStrategy.forSdk(30)).isEqualTo(ResourceSwapStrategy.RESOURCES_LOADER);
		assertThat(ResourceSwapStrategy.forSdk(31)).isEqualTo(ResourceSwapStrategy.RESOURCES_LOADER);
		assertThat(ResourceSwapStrategy.forSdk(36)).isEqualTo(ResourceSwapStrategy.RESOURCES_LOADER);
	}

	@Test
	void below28IsUnsupported() {
		assertThat(ResourceSwapStrategy.forSdk(27)).isEqualTo(ResourceSwapStrategy.UNSUPPORTED);
		assertThat(ResourceSwapStrategy.forSdk(16)).isEqualTo(ResourceSwapStrategy.UNSUPPORTED);
	}
}
