package org.appdevforall.cotg.profiler.service

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.IBinder
import org.appdevforall.cotg.profiler.ProfilerUserService
import org.appdevforall.cotg.profiler.aidl.IProfilerService
import org.slf4j.LoggerFactory
import rikka.shizuku.Shizuku

/**
 * Binds/unbinds the privileged [ProfilerUserService] through Shizuku.
 *
 * Lifecycle-agnostic: the owner (the profiler fragment) calls [connect] when it becomes visible and
 * [disconnect] when it leaves. Callbacks are delivered on the main thread.
 */
class ProfilerServiceConnection(
	context: Context,
	private val onConnected: (IProfilerService) -> Unit,
	private val onDisconnected: () -> Unit,
	private val onUnavailable: () -> Unit,
) {
	private companion object {
		private val logger = LoggerFactory.getLogger(ProfilerServiceConnection::class.java)
	}

	private val appContext = context.applicationContext

	private val args =
		Shizuku
			.UserServiceArgs(ComponentName(appContext, ProfilerUserService::class.java))
			.daemon(true)
			.processNameSuffix("profiler")
			.debuggable((appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
			.version(1)
			.tag("cotg-profiler")

	private var bound = false

	private val connection =
		object : ServiceConnection {
			override fun onServiceConnected(
				name: ComponentName?,
				binder: IBinder?,
			) {
				logger.info("onServiceConnection: name={}, binder={}", name, binder)
				val service = binder?.takeIf { it.pingBinder() }?.let(IProfilerService.Stub::asInterface)
				if (service != null) {
					onConnected(service)
				} else {
					logger.warn("Profiler service connected with a dead binder")
					onUnavailable()
				}
			}

			override fun onServiceDisconnected(name: ComponentName?) {
				onDisconnected()
			}
		}

	/** Binds the privileged profiler service. No-op if already bound. */
	fun connect() {
		if (bound) return
		if (!Shizuku.pingBinder()) {
			onUnavailable()
			return
		}
		try {
			logger.info("trying to bind profiler user service")
			Shizuku.bindUserService(args, connection)
			bound = true
		} catch (t: Throwable) {
			logger.error("Failed to bind profiler user service", t)
			onUnavailable()
		}
	}

	/** Unbinds (and stops) the service. No-op if not bound. */
	fun disconnect() {
		if (!bound) return
		bound = false
		try {
			logger.info("trying to unbind profiler user service")
			// pass remove = true so Shizuku also stops the user service
			Shizuku.unbindUserService(args, connection, true)
		} catch (t: Throwable) {
			logger.warn("Failed to unbind profiler user service", t)
		}
		onDisconnected()
	}
}
