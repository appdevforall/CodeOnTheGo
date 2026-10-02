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
 * [ConnectivityManager]-backed [ConnectivityObserver]. [Context.isNetworkConnected] answers "is
 * the active network capable of internet right now"; this wraps that same check in a
 * [NetworkCallback] so callers get updates instead of having to poll.
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

			// Re-derives from the active network on every callback rather than trusting
			// onAvailable/onLost's own network in isolation: onLost fires for the network that
			// was lost, not the device's overall state, so switching Wi-Fi -> cellular must not
			// be reported as "offline" just because the Wi-Fi network specifically went away.
			val callback =
				object : ConnectivityManager.NetworkCallback() {
					override fun onAvailable(network: Network) {
						trySend(context.isNetworkConnected())
					}

					override fun onLost(network: Network) {
						trySend(context.isNetworkConnected())
					}

					override fun onCapabilitiesChanged(
						network: Network,
						networkCapabilities: NetworkCapabilities,
					) {
						trySend(context.isNetworkConnected())
					}
				}

			trySend(context.isNetworkConnected())

			val request =
				NetworkRequest
					.Builder()
					.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
					.build()
			connectivityManager.registerNetworkCallback(request, callback)

			awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
		}.distinctUntilChanged()
}
