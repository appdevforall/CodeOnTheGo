package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Pins that an asset payload below API 30 is refused, not acked.
 *
 * Nothing below API 30 reads the merged asset dir: {@code DirectoryAssetsProvider} needs a ResourcesLoader and the API 28/29 path mounts the resource apk only. The legacy arm used to run the merge and report the swap committed, which settled a backgrounded deploy's ack on a change the app could not show. The host gates asset payloads on API 30 in another module; this test is the runtime's own assertion of it.
 *
 * Driven with a null fd and a null Context on purpose: the refusal has to come before either is touched, or the JVM stubs would throw first.
 */
class ResourceStoreLegacyAssetsTest {

	@Test
	void anAssetPayloadBelowApi30IsReportedFailedWithTheReason() throws Exception {
		ResourceStore store = new ResourceStore(ResourceSwapStrategy.LEGACY_ASSET_PATH);
		final AtomicReference<Throwable> failure = new AtomicReference<>();
		final boolean[] committed = new boolean[1];

		store.applyAssets(null, 7, "fingerprint", null, new ResourceStore.SwapOutcome() {

			@Override
			public void onSwapCommitted() {
				committed[0] = true;
			}

			@Override
			public void onSwapFailed(Throwable error) {
				failure.set(error);
			}
		});

		assertThat(committed[0]).isFalse();
		assertThat(failure.get()).isNotNull();
		assertThat(failure.get()).hasMessageThat().contains("API 30");
		assertThat(failure.get()).hasMessageThat().contains("gen 7");
	}

	@Test
	void anAssetPayloadOnAnUnsupportedSdkIsRefusedTheSameWay() throws Exception {
		ResourceStore store = new ResourceStore(ResourceSwapStrategy.UNSUPPORTED);
		final AtomicReference<Throwable> failure = new AtomicReference<>();

		store.applyAssets(null, 3, "fingerprint", null, new ResourceStore.SwapOutcome() {

			@Override
			public void onSwapCommitted() {}

			@Override
			public void onSwapFailed(Throwable error) {
				failure.set(error);
			}
		});

		assertThat(failure.get()).hasMessageThat().contains("gen 3");
	}
}
