package moe.shizuku.manager.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.util.Consumer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

@RequiresApi(Build.VERSION_CODES.R)
class AdbMdns(
	private val nsdManager: NsdManager,
	private val serviceType: String,
	private val observer: Consumer<Int>,
) {
	/** Takes the process-wide NsdManager for [serviceType]; see [managerFor]. */
	constructor(context: Context, serviceType: String, observer: Consumer<Int>) :
		this(
			nsdManager = managerFor(context, serviceType),
			serviceType = serviceType,
			observer = observer,
		)

	private var registered = false
	private var running = false
	private var serviceName: String? = null
	private val listener = DiscoveryListener(this)

	fun start() {
		if (running) return
		running = true
		if (!registered) {
			nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
		}
	}

	fun stop() {
		if (!running) return
		running = false
		if (registered) {
			nsdManager.stopServiceDiscovery(listener)
		}
	}

	fun restart() {
		stop()
		start()
	}

	private fun onDiscoveryStart() {
		registered = true
	}

	private fun onDiscoveryStop() {
		registered = false
	}

	private fun onServiceFound(info: NsdServiceInfo) {
		nsdManager.resolveService(info, ResolveListener(this))
	}

	private fun onServiceLost(info: NsdServiceInfo) {
		if (info.serviceName == serviceName) observer.accept(-1)
	}

	private fun onServiceResolved(resolvedService: NsdServiceInfo) {
		if (running &&
			NetworkInterface
				.getNetworkInterfaces()
				.asSequence()
				.any { networkInterface ->
					networkInterface.inetAddresses
						.asSequence()
						.any { resolvedService.host.hostAddress == it.hostAddress }
				} &&
			isPortAvailable(resolvedService.port)
		) {
			serviceName = resolvedService.serviceName
			observer.accept(resolvedService.port)
		}
	}

	private fun isPortAvailable(port: Int) =
		try {
			ServerSocket().use {
				it.bind(InetSocketAddress("127.0.0.1", port), 1)
				false
			}
		} catch (e: IOException) {
			true
		}

	internal class DiscoveryListener(
		private val adbMdns: AdbMdns,
	) : NsdManager.DiscoveryListener {
		override fun onDiscoveryStarted(serviceType: String) {
			Log.v(TAG, "onDiscoveryStarted: $serviceType")

			adbMdns.onDiscoveryStart()
		}

		override fun onStartDiscoveryFailed(
			serviceType: String,
			errorCode: Int,
		) {
			Log.v(TAG, "onStartDiscoveryFailed: $serviceType, $errorCode")
		}

		override fun onDiscoveryStopped(serviceType: String) {
			Log.v(TAG, "onDiscoveryStopped: $serviceType")

			adbMdns.onDiscoveryStop()
		}

		override fun onStopDiscoveryFailed(
			serviceType: String,
			errorCode: Int,
		) {
			Log.v(TAG, "onStopDiscoveryFailed: $serviceType, $errorCode")
		}

		override fun onServiceFound(serviceInfo: NsdServiceInfo) {
			Log.v(TAG, "onServiceFound: ${serviceInfo.serviceName}")

			adbMdns.onServiceFound(serviceInfo)
		}

		override fun onServiceLost(serviceInfo: NsdServiceInfo) {
			Log.v(TAG, "onServiceLost: ${serviceInfo.serviceName}")

			adbMdns.onServiceLost(serviceInfo)
		}
	}

	internal class ResolveListener(
		private val adbMdns: AdbMdns,
	) : NsdManager.ResolveListener {
		override fun onResolveFailed(
			nsdServiceInfo: NsdServiceInfo,
			errorCode: Int,
		) {
			// An empty body here hid every resolve failure: no port is delivered and the caller
			// just times out. FAILURE_ALREADY_ACTIVE (3) means two resolves raced on one manager.
			Log.w(TAG, "onResolveFailed: ${nsdServiceInfo.serviceName}, $errorCode")
		}

		override fun onServiceResolved(nsdServiceInfo: NsdServiceInfo) {
			adbMdns.onServiceResolved(nsdServiceInfo)
		}
	}

	companion object {
		const val TLS_CONNECT = "_adb-tls-connect._tcp"
		const val TLS_PAIRING = "_adb-tls-pairing._tcp"

		private val managers = ConcurrentHashMap<String, NsdManager>()

		/**
		 * One NsdManager per service type for the whole process.
		 *
		 * getSystemService caches NsdManager per Context, and the framework keeps each manager (and
		 * the Context it holds) alive for the process, so one taken from an Activity or Service
		 * retains it; stopServiceDiscovery() does not release it. An attribution context off the
		 * application context avoids that. One per type rather than one per instance: each new
		 * manager is never freed, and below Android 13 also starts a HandlerThread, so per-instance
		 * managers grew with every pairing retry (ADFA-6392). Separate types still get separate
		 * NsdService clients, which matters below Android 13, where a second concurrent
		 * resolveService on one manager fails with FAILURE_ALREADY_ACTIVE.
		 */
		private fun managerFor(
			context: Context,
			serviceType: String,
		): NsdManager =
			managers.computeIfAbsent(serviceType) {
				context.applicationContext
					.createAttributionContext(null)
					.getSystemService(NsdManager::class.java)
			}

		const val TAG = "AdbMdns"
	}
}
