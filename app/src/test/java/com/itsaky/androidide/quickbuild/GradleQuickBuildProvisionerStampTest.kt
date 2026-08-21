package com.itsaky.androidide.quickbuild

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The stampBaseline split across [GradleQuickBuildProvisioner]'s three proxy app builds (S7),
 * pinned on the pure [ProxyAppBuildPurpose] mapping the call sites read.
 *
 * Both flips are silent on every existing test: an unstamped provision/rebaseline re-creates
 * S7 (a manifest-only rebaseline's persisted payloads from the previous epoch outrank the
 * fresh baseline at the proxy app's next boot), and a stamped prebuild burns a generation and
 * re-runs the packaging tail on every project open.
 */
class GradleQuickBuildProvisionerStampTest {
	@Test
	fun `a provision stamps a fresh baseline generation - its APK is installed`() {
		assertThat(ProxyAppBuildPurpose.PROVISION.stampBaseline).isTrue()
	}

	@Test
	fun `a rebaseline stamps a fresh baseline generation - its APK is reinstalled`() {
		assertThat(ProxyAppBuildPurpose.REBASELINE.stampBaseline).isTrue()
	}

	@Test
	fun `the prebuild does not stamp - its APK is never installed`() {
		assertThat(ProxyAppBuildPurpose.PREBUILD.stampBaseline).isFalse()
	}
}
