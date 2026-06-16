package org.appdevforall.cotg.profiler

import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.util.Log
import org.appdevforall.cotg.hidden.compat.ActivityManagerHiddenCompat
import org.appdevforall.cotg.profiler.aidl.IProcessListObserver
import org.appdevforall.cotg.profiler.aidl.IProfilerService
import org.appdevforall.cotg.profiler.aidl.ProcessInfo
import rikka.hidden.compat.ActivityManagerApis
import rikka.hidden.compat.PackageManagerApis
import rikka.hidden.compat.adapter.ProcessObserverAdapter
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/**
 * @author Akash Yadav
 */
class ProfilerUserService : IProfilerService.Stub() {
	companion object {
		private const val TAG = "ProfilerUserService"

		/**
		 * [android.os.UserHandle.USER_CURRENT]. Resolved by ActivityManager to the foreground user.
		 */
		private const val USER_CURRENT = -2
	}

	/** Registered app-process clients. Handles linkToDeath/unregister of dead clients for us. */
	private val clients = RemoteCallbackList<IProcessListObserver>()

	/** Authoritative live process list, keyed by pid. Mutated from binder threads. */
	private val processes = ConcurrentHashMap<Int, ProcessInfo>()

	/**
	 * Forwards ActivityManagerService process lifecycle callbacks (rikka-provided) into [processes]
	 * and out to [clients]. [ProcessObserverAdapter] gives empty defaults for the callbacks we
	 * don't care about and papers over the per-version differences of `IProcessObserver`.
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
				val info = buildProcessInfo(pid, processUid, packageName, processName)
				processes[pid] = info
				broadcast { it.onProcessesAdded(listOf(info)) }
			}

			override fun onProcessDied(
				pid: Int,
				uid: Int,
			) {
				if (processes.remove(pid) != null) {
					broadcast { it.onProcessesRemoved(intArrayOf(pid)) }
				}
			}
		}

	override fun destroy() {
		exit()
	}

	override fun exit() {
		exitProcess(0)
	}

	override fun registerProcessListObserver(observer: IProcessListObserver) {
		val firstClient = clients.registeredCallbackCount == 0
		clients.register(observer)

		if (firstClient) {
			// Seed before registering the AMS observer; deltas that race in simply overwrite/remove
			// entries in the concurrent map, so the snapshot can't go stale.
			seedSnapshot()
			ActivityManagerApis.registerProcessObserver(processObserver)
		}

		// Hand this client the current full state.
		runCatching { observer.onProcessSnapshot(processes.values.toList()) }
	}

	override fun unregisterProcessListObserver(observer: IProcessListObserver) {
		clients.unregister(observer)
		if (clients.registeredCallbackCount == 0) {
			ActivityManagerApis.unregisterProcessObserver(processObserver)
			processes.clear()
		}
	}

	private fun seedSnapshot() {
		processes.clear()
		ActivityManagerHiddenCompat.getRunningAppProcesses().forEach { proc ->
			val packageName = proc.pkgList?.firstOrNull()
			processes[proc.pid] = buildProcessInfo(proc.pid, proc.uid, packageName, proc.processName)
		}
	}

	/** Enriches a process with its app's static label/debuggable/profileable attributes. */
	private fun buildProcessInfo(
		pid: Int,
		uid: Int,
		packageName: String?,
		processName: String?,
	): ProcessInfo {
		var appLabel: String? = null
		var debuggable = false
		var profileable = false

		if (packageName != null) {
			val userId = uid / 100000
			PackageManagerApis.getApplicationInfoNoThrow(packageName, 0L, userId)?.let { appInfo ->
				debuggable = appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
				profileable = debuggable ||
					(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && appInfo.isProfileable) ||
					(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && appInfo.isProfileableByShell)
				// Best-effort label without a Context/Resources; a localized label would require
				// resolving labelRes against the package's resources.
				appLabel = appInfo.nonLocalizedLabel?.toString() ?: packageName
			}
		}

		return ProcessInfo(
			pid = pid,
			uid = uid,
			processName = processName ?: packageName.orEmpty(),
			packageName = packageName,
			appLabel = appLabel,
			debuggable = debuggable,
			profileable = profileable,
		)
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
		val managed = true
		val mallocInfo = false
		val runGc = true

		out.use { fd ->
			val sdk = Build.VERSION.SDK_INT
			when {
				sdk >= Build.VERSION_CODES.VANILLA_ICE_CREAM ->
					// noinspection NewApi
					ActivityManagerHiddenCompat.dumpHeapApi35(
						process,
						USER_CURRENT,
						managed,
						mallocInfo,
						runGc,
						// dumpBitmaps =
						null,
						path,
						fd,
					)

				sdk >= Build.VERSION_CODES.Q ->
					// noinspection NewApi
					ActivityManagerHiddenCompat.dumpHeapApi29(
						process,
						USER_CURRENT,
						managed,
						mallocInfo,
						runGc,
						path,
						fd,
					)

				sdk >= Build.VERSION_CODES.P ->
					ActivityManagerHiddenCompat.dumpHeapApi28(
						process,
						USER_CURRENT,
						managed,
						mallocInfo,
						runGc,
						path,
						fd,
					)

				sdk >= Build.VERSION_CODES.O ->
					ActivityManagerHiddenCompat.dumpHeapApi26(
						process,
						USER_CURRENT,
						managed,
						path,
						fd,
					)

				else -> {
					Log.e(TAG, "Heap dump is not supported on SDK $sdk")
					throw UnsupportedOperationException("Heap dump is not supported on SDK $sdk")
				}
			}
		}
	}
}
