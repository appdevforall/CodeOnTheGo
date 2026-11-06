package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [DefaultNetworkState] is the state behind [AndroidConnectivityObserver]: online is derived from
 * the default-network callbacks' data, never re-queried, so losing the default network must report
 * offline (ADFA-5646 review).
 */
class DefaultNetworkStateTest {
	private val sent = mutableListOf<Boolean>()
	private val state = DefaultNetworkState<String> { sent += it }

	@Test
	fun `losing the default network reports offline`() {
		state.onCapabilities("wifi", hasValidatedInternet = true)

		state.onLost("wifi")

		assertThat(sent).containsExactly(true, false).inOrder()
	}

	@Test
	fun `a switch to a new default network stays online`() {
		state.onCapabilities("wifi", hasValidatedInternet = true)

		state.onCapabilities("cellular", hasValidatedInternet = true)
		state.onLost("wifi")

		assertThat(sent).containsExactly(true)
	}

	// A VPN is the default network while it is up; when it breaks, the app's traffic breaks with it,
	// whatever the network underneath it is doing.
	@Test
	fun `a default network that loses validation reports offline`() {
		state.onCapabilities("vpn", hasValidatedInternet = true)

		state.onCapabilities("vpn", hasValidatedInternet = false)

		assertThat(sent).containsExactly(true, false).inOrder()
	}

	@Test
	fun `a default network is offline until it validates`() {
		state.onCapabilities("captive-portal", hasValidatedInternet = false)

		state.onCapabilities("captive-portal", hasValidatedInternet = true)

		assertThat(sent).containsExactly(false, true).inOrder()
	}

	@Test
	fun `with no network the initial sample is the only value`() {
		state.onInitialSample(false)

		assertThat(sent).containsExactly(false)
	}

	// The sample is taken after registration, so a callback that already answered is newer.
	@Test
	fun `an initial sample after a callback is ignored`() {
		state.onCapabilities("wifi", hasValidatedInternet = true)
		state.onLost("wifi")

		state.onInitialSample(true)

		assertThat(sent).containsExactly(true, false).inOrder()
	}

	// Without the sample, the first answer must still be sent, even when it is offline.
	@Test
	fun `a first callback reporting offline is sent`() {
		state.onCapabilities("captive-portal", hasValidatedInternet = false)

		state.onInitialSample(true)

		assertThat(sent).containsExactly(false)
	}

	@Test
	fun `an unchanged answer is not sent again`() {
		state.onInitialSample(true)
		state.onCapabilities("wifi", hasValidatedInternet = true)

		assertThat(sent).containsExactly(true)
	}
}
