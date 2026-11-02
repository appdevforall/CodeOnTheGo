package com.itsaky.androidide.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
 * [ConnectivityManager]-backed [ConnectivityObserver]. Online means some network has validated
 * internet: Android has confirmed it works, so a captive portal or a Wi-Fi with no uplink counts
 * as offline.
 *
 * State comes from the callbacks' own network data, via [OnlineNetworks], never from re-querying
 * [ConnectivityManager] inside a callback: that can race callback delivery, and in onLost
 * `activeNetwork` may still name the network just lost. With no network left, no later callback
 * would correct the stale value.
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

			val onlineNetworks = OnlineNetworks<Network> { online -> trySend(online) }
			val callback =
				object : ConnectivityManager.NetworkCallback() {
					// No onAvailable: from API 26 every onAvailable is followed by
					// onCapabilitiesChanged, which carries the capabilities this needs.
					override fun onCapabilitiesChanged(
						network: Network,
						networkCapabilities: NetworkCapabilities,
					) {
						onlineNetworks.onCapabilities(network, networkCapabilities.hasValidatedInternet())
					}

					override fun onLost(network: Network) {
						onlineNetworks.onLost(network)
					}
				}

			val request =
				NetworkRequest
					.Builder()
					.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
					.build()
			// Register before sampling: a change after the sample then still arrives as a callback.
			connectivityManager.registerNetworkCallback(request, callback)
			onlineNetworks.onInitialSample(connectivityManager.hasValidatedInternet())

			awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
		}.distinctUntilChanged()
}

/**
 * Which networks have validated internet, from callback data in callback order. Calls [send]
 * whenever that set goes from empty to non-empty or back, and only when the set changes, so a
 * callback about a network that was never online (or a loss of one) sends nothing.
 *
 * Registration reports every matching network but nothing when there is none, so the first value
 * comes from [onInitialSample] - unless a callback has already changed the set, which is newer.
 *
 * Thread-safe: callbacks arrive on ConnectivityManager's thread, the sample on the collector's.
 * [N] is the network handle; generic so the logic is testable without Android.
 */
internal class OnlineNetworks<N>(
	private val send: (online: Boolean) -> Unit,
) {
	private val networks = mutableSetOf<N>()
	private var changedByCallback = false

	@Synchronized
	fun onCapabilities(
		network: N,
		hasValidatedInternet: Boolean,
	) = update { if (hasValidatedInternet) add(network) else remove(network) }

	@Synchronized
	fun onLost(network: N) = update { remove(network) }

	@Synchronized
	fun onInitialSample(online: Boolean) {
		if (!changedByCallback) send(online)
	}

	private inline fun update(change: MutableSet<N>.() -> Boolean) {
		val wasOnline = networks.isNotEmpty()
		if (!networks.change()) return
		changedByCallback = true
		if (networks.isNotEmpty() != wasOnline) send(!wasOnline)
	}
}

private fun NetworkCapabilities.hasValidatedInternet(): Boolean =
	hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
		hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

private fun ConnectivityManager.hasValidatedInternet(): Boolean {
	val network = activeNetwork ?: return false
	return getNetworkCapabilities(network)?.hasValidatedInternet() == true
}
