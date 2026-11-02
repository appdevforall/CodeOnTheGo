package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [OnlineNetworks] is the state behind [AndroidConnectivityObserver]: online is derived from the
 * networks the callbacks reported, never re-queried, so losing the only network must report
 * offline (ADFA-5646 review). A repeated value is expected here; the observer's
 * `distinctUntilChanged()` drops it.
 */
class OnlineNetworksTest {
	private val sent = mutableListOf<Boolean>()
	private val networks = OnlineNetworks<String> { sent += it }

	@Test
	fun `losing the only network reports offline`() {
		networks.onCapabilities("wifi", hasValidatedInternet = true)

		networks.onLost("wifi")

		assertThat(sent).containsExactly(true, false).inOrder()
	}

	@Test
	fun `losing one of two networks stays online`() {
		networks.onCapabilities("wifi", hasValidatedInternet = true)
		networks.onCapabilities("cellular", hasValidatedInternet = true)

		networks.onLost("wifi")

		assertThat(sent).containsExactly(true)
	}

	@Test
	fun `a network without validated internet is offline until it validates`() {
		networks.onInitialSample(false)
		networks.onCapabilities("captive-portal", hasValidatedInternet = false)
		assertThat(sent).containsExactly(false)

		networks.onCapabilities("captive-portal", hasValidatedInternet = true)
		networks.onCapabilities("captive-portal", hasValidatedInternet = false)

		assertThat(sent).containsExactly(false, true, false).inOrder()
	}

	@Test
	fun `losing a network that was never online sends nothing`() {
		networks.onInitialSample(true)
		networks.onCapabilities("wifi", hasValidatedInternet = true)

		networks.onLost("unvalidated")

		assertThat(sent).containsExactly(true, true).inOrder()
	}

	@Test
	fun `with no network the initial sample is the only value`() {
		networks.onInitialSample(false)

		assertThat(sent).containsExactly(false)
	}

	// The sample is taken after registration, so a callback that already changed the state is
	// newer than it.
	@Test
	fun `an initial sample after a callback change is ignored`() {
		networks.onCapabilities("wifi", hasValidatedInternet = true)
		networks.onLost("wifi")

		networks.onInitialSample(true)

		assertThat(sent).containsExactly(true, false).inOrder()
	}
}
