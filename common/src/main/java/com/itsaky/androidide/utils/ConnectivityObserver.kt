package com.itsaky.androidide.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Live internet-connectivity state, for UI that must react to connectivity changes rather than
 * check it once - e.g. hiding an action that requires the network before the user taps it and
 * hits a dead end, instead of only refusing the tap after the fact.
 */
interface ConnectivityObserver {
	/** Emits the current state immediately upon collection, then again on every change. */
	fun observe(): Flow<Boolean>
}

/**
 * [ConnectivityManager]-backed [ConnectivityObserver]. Online means the default network - the one
 * the app's traffic uses, a VPN included - has validated internet: Android has confirmed it works,
 * so a captive portal, a Wi-Fi with no uplink, or a broken VPN counts as offline.
 *
 * State comes from the default-network callback's own data, via [DefaultNetworkState], never from
 * re-querying [ConnectivityManager] inside a callback: that can race callback delivery, and in
 * onLost `activeNetwork` may still name the network just lost. With no network left, no later
 * callback would correct the stale value.
 */
class AndroidConnectivityObserver(
	private val context: Context,
) : ConnectivityObserver {
	override fun observe(): Flow<Boolean> =
		callbackFlow {
			val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
			if (connectivityManager == null) {
				trySend(false)
				close()
				return@callbackFlow
			}

			val state = DefaultNetworkState<Network> { online -> trySend(online) }
			val callback =
				object : ConnectivityManager.NetworkCallback() {
					// No onAvailable: from API 26 every onAvailable is followed by
					// onCapabilitiesChanged, which carries the capabilities this needs.
					override fun onCapabilitiesChanged(
						network: Network,
						networkCapabilities: NetworkCapabilities,
					) {
						state.onCapabilities(network, networkCapabilities.hasValidatedInternet())
					}

					override fun onLost(network: Network) {
						state.onLost(network)
					}
				}

			// The default network, not a NetworkRequest: a request excludes VPNs, while the initial
			// sample's activeNetwork does not, so the two could disagree about which network counts.
			// Register before sampling: a change after the sample then still arrives as a callback.
			connectivityManager.registerDefaultNetworkCallback(callback)
			state.onInitialSample(connectivityManager.hasValidatedInternet())

			awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
		}.distinctUntilChanged()
}

/**
 * Whether the default network has validated internet, from default-network callback data in
 * callback order. Calls [send] only when that answer changes.
 *
 * A loss counts only for the current default network: when the default switches (Wi-Fi to
 * cellular, say), the new one is reported and a later onLost for the old one is not a disconnect.
 *
 * Registration reports the current default network but nothing when there is none, so the first
 * value comes from [onInitialSample] - unless a callback has already answered, which is newer.
 *
 * Thread-safe: callbacks arrive on ConnectivityManager's thread, the sample on the collector's.
 * [N] is the network handle; generic so the logic is testable without Android.
 */
internal class DefaultNetworkState<N>(
	private val send: (online: Boolean) -> Unit,
) {
	private var defaultNetwork: N? = null
	private var lastSent: Boolean? = null
	private var answeredByCallback = false

	@Synchronized
	fun onCapabilities(
		network: N,
		hasValidatedInternet: Boolean,
	) {
		defaultNetwork = network
		answeredByCallback = true
		sendIfChanged(hasValidatedInternet)
	}

	@Synchronized
	fun onLost(network: N) {
		if (network != defaultNetwork) return
		defaultNetwork = null
		answeredByCallback = true
		sendIfChanged(false)
	}

	@Synchronized
	fun onInitialSample(online: Boolean) {
		if (!answeredByCallback) sendIfChanged(online)
	}

	private fun sendIfChanged(online: Boolean) {
		if (online == lastSent) return
		lastSent = online
		send(online)
	}
}

private fun NetworkCapabilities.hasValidatedInternet(): Boolean =
	hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
		hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

private fun ConnectivityManager.hasValidatedInternet(): Boolean {
	val network = activeNetwork ?: return false
	return getNetworkCapabilities(network)?.hasValidatedInternet() == true
}
