package org.appdevforall.cotg.profiler

import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.os.ServiceManager
import android.util.Log
import org.appdevforall.cotg.hidden.compat.ActivityManagerHiddenCompat
import org.appdevforall.cotg.profiler.aidl.ICpuProfileObserver
import org.appdevforall.cotg.profiler.aidl.IProcessListObserver
import org.appdevforall.cotg.profiler.aidl.IProfilerService
import org.appdevforall.cotg.profiler.aidl.ProcessInfo
import rikka.hidden.compat.ActivityManagerApis
import rikka.hidden.compat.PackageManagerApis
import rikka.hidden.compat.adapter.ProcessObserverAdapter
import kotlin.system.exitProcess

/**
 * Privileged (Shizuku) user service. Besides heap dumps, it maintains a live list of running
 * processes that app-process clients can observe via [IProcessListObserver].
 *
 * All process-list state ([processes], [appMetaCache] and the pending delta buffers) is confined to
 * a single [worker] thread, so it needs no locking. Binder callbacks (from clients and from
 * ActivityManagerService) simply post onto that thread.
 *
 * @author Akash Yadav
 */
class ProfilerUserService : IProfilerService.Stub() {
	companion object {
		private const val TAG = "ProfilerUserService"

		/**
		 * [android.os.UserHandle.USER_CURRENT]. Resolved by ActivityManager to the foreground user.
		 */
		private const val USER_CURRENT = -2

		/** Per-user app id divisor (`uid / PER_USER_RANGE == userId`). */
		private const val PER_USER_RANGE = 100000

		/** Window over which process add/remove deltas are coalesced before notifying clients. */
		private const val DELTA_DEBOUNCE_MS = 250L

		/** Re-attach backoff after a system_server restart. */
		private const val REATTACH_RETRY_MS = 1000L
		private const val REATTACH_MAX_ATTEMPTS = 15

		private const val SERVICE_ACTIVITY = "activity"
	}

	private val workerThread = HandlerThread("profiler-process-observer").apply { start() }
	private val worker = Handler(workerThread.looper)

	/** Authoritative live process list, keyed by pid. Worker-thread confined. */
	private val processes = LinkedHashMap<Int, ProcessInfo>()

	/** Cache of static, package-level metadata keyed by "userId:packageName". Worker-thread confined. */
	private val appMetaCache = HashMap<String, AppMeta>()

	/** Coalesced deltas, flushed by [flushDeltas]. Worker-thread confined. */
	private val pendingAdded = LinkedHashMap<Int, ProcessInfo>()
	private val pendingRemoved = LinkedHashSet<Int>()

	/** The "activity" service binder we observe; tracked so we can detect a system_server restart. */
	private var activityBinder: IBinder? = null

	/** Guards the single CPU profiling session. */
	private val cpuLock = Any()
	private var cpuSession: CpuProfilingSession? = null

	/** Registered app-process clients. Dead clients are dropped (and may trigger teardown). */
	private val clients =
		object : RemoteCallbackList<IProcessListObserver>() {
			override fun onCallbackDied(callback: IProcessListObserver) {
				worker.post { if (registeredCallbackCount == 0) stopObserving() }
			}
		}

	private val flushDeltas = Runnable { flushDeltas() }

	private val deathRecipient =
		IBinder.DeathRecipient { worker.post { onSystemServerDied() } }

	/**
	 * Forwards ActivityManagerService process lifecycle callbacks (rikka-provided) onto [worker].
	 * [ProcessObserverAdapter] gives empty defaults for the callbacks we don't care about and papers
	 * over the per-version differences of `IProcessObserver`.
	 *
	 * `onProcessStarted` only exists on API 31+, so on older platforms new processes are discovered
	 * lazily the first time they report a foreground activity/service.
	 */
	private val processObserver =
		object : ProcessObserverAdapter() {
			override fun onProcessStarted(
				pid: Int,
				processUid: Int,
				packageUid: Int,
				packageName: String?,
				processName: String?,
			) {
				worker.post { onProcessStarted(pid, processUid, packageName, processName) }
			}

			override fun onProcessDied(
				pid: Int,
				uid: Int,
			) {
				worker.post { onProcessDied(pid) }
			}

			override fun onForegroundActivitiesChanged(
				pid: Int,
				uid: Int,
				foregroundActivities: Boolean,
			) {
				worker.post { discoverIfUnknown(pid) }
			}

			override fun onForegroundServicesChanged(
				pid: Int,
				uid: Int,
				serviceTypes: Int,
			) {
				worker.post { discoverIfUnknown(pid) }
			}
		}

	override fun destroy() {
		exit()
	}

	override fun exit() {
		synchronized(cpuLock) {
			cpuSession?.let { runCatching { it.abort() } }
			cpuSession = null
		}
		runCatching { ActivityManagerApis.unregisterProcessObserver(processObserver) }
		workerThread.quitSafely()
		exitProcess(0)
	}

	override fun registerProcessListObserver(observer: IProcessListObserver) {
		worker.post {
			val firstClient = clients.registeredCallbackCount == 0
			if (clients.register(observer) && firstClient) {
				startObserving()
			}
			// Hand this client the current full state.
			sendSnapshot(observer)
		}
	}

	override fun unregisterProcessListObserver(observer: IProcessListObserver) {
		worker.post {
			clients.unregister(observer)
			if (clients.registeredCallbackCount == 0) {
				stopObserving()
			}
		}
	}

	private fun startObserving() {
		if (!attachObserver()) {
			Log.w(TAG, "Failed to start observing processes; ActivityManager unavailable")
		}
	}

	private fun stopObserving() {
		worker.removeCallbacks(flushDeltas)
		runCatching { ActivityManagerApis.unregisterProcessObserver(processObserver) }
		unlinkDeath()
		processes.clear()
		appMetaCache.clear()
		pendingAdded.clear()
		pendingRemoved.clear()
	}

	/**
	 * Registers the AMS observer, links a death recipient so we can recover from a system_server
	 * restart, then seeds [processes] with the current snapshot. Registering before seeding means any
	 * lifecycle callback that races in is simply queued behind us on [worker] and applied on top of
	 * the seed. Returns `false` if the activity service is unavailable.
	 */
	private fun attachObserver(): Boolean {
		val binder = ServiceManager.getService(SERVICE_ACTIVITY)
		if (binder == null || !binder.isBinderAlive) {
			return false
		}
		return runCatching {
			binder.linkToDeath(deathRecipient, 0)
			activityBinder = binder
			ActivityManagerApis.registerProcessObserver(processObserver)
			seedSnapshot()
			true
		}.getOrElse {
			Log.w(TAG, "Failed to attach process observer", it)
			unlinkDeath()
			false
		}
	}

	private fun unlinkDeath() {
		activityBinder?.let { runCatching { it.unlinkToDeath(deathRecipient, 0) } }
		activityBinder = null
	}

	private fun onSystemServerDied() {
		Log.i(TAG, "system_server died; will re-attach process observer")
		unlinkDeath()
		processes.clear()
		appMetaCache.clear()
		pendingAdded.clear()
		pendingRemoved.clear()
		if (clients.registeredCallbackCount > 0) {
			reattach(attempt = 0)
		}
	}

	/** Re-attaches after a restart, retrying while the new system_server comes up. */
	private fun reattach(attempt: Int) {
		if (clients.registeredCallbackCount == 0) {
			return
		}
		if (attachObserver()) {
			// Tell every client to replace its list with the fresh snapshot.
			broadcast { it.onProcessSnapshot(processes.values.toList()) }
			return
		}
		if (attempt < REATTACH_MAX_ATTEMPTS) {
			worker.postDelayed({ reattach(attempt + 1) }, REATTACH_RETRY_MS)
		} else {
			Log.w(TAG, "Giving up re-attaching process observer after $attempt attempts")
		}
	}

	private fun seedSnapshot() {
		processes.clear()
		ActivityManagerHiddenCompat.getRunningAppProcesses().forEach { proc ->
			val packageName = proc.pkgList?.firstOrNull()
			val info = buildProcessInfo(proc.pid, proc.uid, packageName, proc.processName)
			processes[info.pid] = info
		}
	}

	private fun onProcessStarted(
		pid: Int,
		uid: Int,
		packageName: String?,
		processName: String?,
	) {
		val info = buildProcessInfo(pid, uid, packageName, processName)
		if (processes.put(pid, info) == info) {
			// Nothing actually changed (e.g. duplicate of a snapshot entry).
			return
		}
		pendingRemoved.remove(pid)
		pendingAdded[pid] = info
		scheduleFlush()
	}

	private fun onProcessDied(pid: Int) {
		if (processes.remove(pid) == null) {
			return
		}
		// If it was added within the current (un-flushed) window, clients never saw it: drop silently.
		if (pendingAdded.remove(pid) == null) {
			pendingRemoved.add(pid)
		}
		scheduleFlush()
	}

	/**
	 * Resolves and adds a process we don't yet know about. Used on API < 31 where there is no
	 * `onProcessStarted`; the process is discovered the first time it reports a foreground change.
	 */
	private fun discoverIfUnknown(pid: Int) {
		if (processes.containsKey(pid)) {
			return
		}
		val proc =
			ActivityManagerHiddenCompat.getRunningAppProcesses().firstOrNull { it.pid == pid }
				?: return
		onProcessStarted(pid, proc.uid, proc.pkgList?.firstOrNull(), proc.processName)
	}

	private fun scheduleFlush() {
		worker.removeCallbacks(flushDeltas)
		worker.postDelayed(flushDeltas, DELTA_DEBOUNCE_MS)
	}

	private fun flushDeltas() {
		if (pendingAdded.isNotEmpty()) {
			val added = pendingAdded.values.toList()
			pendingAdded.clear()
			broadcast { it.onProcessesAdded(added) }
		}
		if (pendingRemoved.isNotEmpty()) {
			val removed = pendingRemoved.toIntArray()
			pendingRemoved.clear()
			broadcast { it.onProcessesRemoved(removed) }
		}
	}

	/** Static, package-level attributes of an app. */
	private data class AppMeta(
		val label: String?,
		val debuggable: Boolean,
		val profileable: Boolean,
	)

	private fun buildProcessInfo(
		pid: Int,
		uid: Int,
		packageName: String?,
		processName: String?,
	): ProcessInfo {
		val meta = packageName?.let { appMetaFor(it, uid / PER_USER_RANGE) }
		return ProcessInfo(
			pid = pid,
			uid = uid,
			processName = processName ?: packageName.orEmpty(),
			packageName = packageName,
			appLabel = meta?.label,
			debuggable = meta?.debuggable ?: false,
			profileable = meta?.profileable ?: false,
		)
	}

	private fun appMetaFor(
		packageName: String,
		userId: Int,
	): AppMeta {
		val key = "$userId:$packageName"
		appMetaCache[key]?.let { return it }

		val appInfo = PackageManagerApis.getApplicationInfoNoThrow(packageName, 0L, userId)
		val meta =
			if (appInfo == null) {
				AppMeta(label = null, debuggable = false, profileable = false)
			} else {
				val debuggable = appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
				val profileable =
					debuggable ||
						(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && appInfo.isProfileable) ||
						(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && appInfo.isProfileableByShell)
				AppMeta(loadAppLabel(appInfo) ?: packageName, debuggable, profileable)
			}

		appMetaCache[key] = meta
		return meta
	}

	/**
	 * Loads an app's label without a [android.content.Context]. Prefers the non-localized label, then
	 * resolves [ApplicationInfo.labelRes] against the apk's resources via a throwaway [AssetManager]
	 * (the privileged process is exempt from hidden-API restrictions, so reflection is allowed).
	 */
	private fun loadAppLabel(appInfo: ApplicationInfo): String? {
		appInfo.nonLocalizedLabel?.let { return it.toString() }

		val labelRes = appInfo.labelRes
		val apkPath = appInfo.publicSourceDir ?: appInfo.sourceDir
		if (labelRes == 0 || apkPath == null) {
			return null
		}

		return runCatching {
			val assets =
				AssetManager::class.java
					.getDeclaredConstructor()
					.apply { isAccessible = true }
					.newInstance()
			assets.use {
				AssetManager::class.java
					.getMethod("addAssetPath", String::class.java)
					.invoke(it, apkPath)
				val system = Resources.getSystem()
				@Suppress("DEPRECATION")
				Resources(it, system.displayMetrics, system.configuration).getString(labelRes)
			}
		}.getOrNull()
	}

	private fun sendSnapshot(observer: IProcessListObserver) {
		try {
			observer.onProcessSnapshot(processes.values.toList())
		} catch (e: RemoteException) {
			Log.w(TAG, "Failed to send process snapshot", e)
		}
	}

	private inline fun broadcast(action: (IProcessListObserver) -> Unit) {
		val count = clients.beginBroadcast()
		try {
			for (i in 0 until count) {
				try {
					action(clients.getBroadcastItem(i))
				} catch (e: RemoteException) {
					Log.w(TAG, "Failed to notify process-list observer", e)
				}
			}
		} finally {
			clients.finishBroadcast()
		}
	}

	override fun dumpHeapForPid(
		out: ParcelFileDescriptor,
		pid: Int,
	) {
		// ActivityManager.dumpHeap() resolves its `process` argument as a pid when it is a valid
		// integer, so we can target the process directly by its pid.
		dumpHeap(out, process = pid.toString())
	}

	override fun dumpHeapForPackage(
		out: ParcelFileDescriptor,
		packageName: String,
	) {
		// For a package, the process name matches the package name (for the default process).
		dumpHeap(out, process = packageName)
	}

	override fun startCpuProfiling(
		pid: Int,
		packageName: String?,
		observer: ICpuProfileObserver,
	) {
		synchronized(cpuLock) {
			if (cpuSession?.isActive() == true) {
				runCatching { observer.onProfilingError("A CPU profiling session is already running") }
				return
			}
			val session = CpuProfilingSession(pid, packageName, observer)
			cpuSession = if (session.start()) session else null
		}
	}

	override fun stopCpuProfiling(reportOut: ParcelFileDescriptor) {
		// Keep [cpuSession] set while we finalize so cancelCpuProfiling() can still reach (and kill)
		// this session's report generation; clear it only once stop() returns.
		val session = synchronized(cpuLock) { cpuSession }
		if (session == null) {
			// Nothing recording: hand the client an empty report so it isn't left waiting.
			runCatching { ParcelFileDescriptor.AutoCloseOutputStream(reportOut).close() }
			return
		}
		try {
			session.stop(reportOut)
		} finally {
			synchronized(cpuLock) { if (cpuSession === session) cpuSession = null }
		}
	}

	override fun cancelCpuProfiling() {
		val session = synchronized(cpuLock) { cpuSession.also { cpuSession = null } }
		session?.let { runCatching { it.cancel() } }
	}

	/**
	 * Dumps the managed (Java) heap of the given [process] into [out].
	 *
	 * [process] may either be a pid (as a string) or a process/package name. The dump is written
	 * directly to the file descriptor backing [out]; the `path` passed to ActivityManager is only
	 * used as a label. [out] is closed once the dump completes.
	 */
	private fun dumpHeap(
		out: ParcelFileDescriptor,
		process: String,
	) {
		// Informational label only - the heap dump is written to the file descriptor, not this path.
		val path = "/data/local/tmp/$process.hprof"
		out.use { fd ->
			ActivityManagerHiddenCompat.dumpHeap(process, USER_CURRENT, path, fd)
		}
	}
}
