package org.appdevforall.cotg.hidden.compat

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.IActivityManager
import android.app.RemoteCallback
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import rikka.hidden.compat.util.SystemServiceBinder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * @author Akash Yadav
 */
object ActivityManagerHiddenCompat {
	/**
	 * Maximum time to wait for an asynchronous heap dump (API 29+) to finish before returning.
	 */
	private const val DUMP_HEAP_TIMEOUT_SECONDS = 60L

	private val activityManagerBinder =
		SystemServiceBinder("activity", IActivityManager.Stub::asInterface)

	private val activityManager: IActivityManager
		get() = activityManagerBinder.get()

	/**
	 * Snapshot of the currently running processes, used to seed a live process list before a
	 * process observer starts delivering deltas.
	 */
	fun getRunningAppProcesses(): List<ActivityManager.RunningAppProcessInfo> = activityManager.runningAppProcesses ?: emptyList()

	/**
	 * Dumps the heap of [process] (a pid or process name) for [userId] into [fd], picking the right
	 * underlying `ActivityManager.dumpHeap` overload for the running platform. On API 29+ the dump is
	 * asynchronous, so this blocks (up to [DUMP_HEAP_TIMEOUT_SECONDS]) until it finishes, meaning the
	 * caller can safely close [fd] once this returns.
	 *
	 * @param path informational label recorded in the dump; the bytes are written to [fd].
	 * @param managed dump the managed (Java) heap as `.hprof` (vs. native heap).
	 * @param dumpBitmaps bitmap dump format; only honoured on API 35+, `null` to skip bitmaps.
	 * @return whether the dump was successfully started.
	 * @throws UnsupportedOperationException on platforms older than [Build.VERSION_CODES.O].
	 */
	fun dumpHeap(
		process: String,
		userId: Int,
		path: String,
		fd: ParcelFileDescriptor,
		managed: Boolean = true,
		mallocInfo: Boolean = false,
		runGc: Boolean = true,
		dumpBitmaps: String? = null,
	): Boolean {
		val sdk = Build.VERSION.SDK_INT
		return when {
			sdk >= Build.VERSION_CODES.VANILLA_ICE_CREAM ->
				dumpHeapApi35(process, userId, managed, mallocInfo, runGc, dumpBitmaps, path, fd)

			sdk >= Build.VERSION_CODES.Q ->
				dumpHeapApi29(process, userId, managed, mallocInfo, runGc, path, fd)

			sdk >= Build.VERSION_CODES.P ->
				dumpHeapApi28(process, userId, managed, mallocInfo, runGc, path, fd)

			sdk >= Build.VERSION_CODES.O ->
				dumpHeapApi26(process, userId, managed, path, fd)

			else ->
				throw UnsupportedOperationException("Heap dump is not supported on SDK $sdk")
		}
	}

	@SuppressLint("ObsoleteSdkInt")
	@RequiresApi(Build.VERSION_CODES.O)
	private fun dumpHeapApi26(
		process: String,
		userId: Int,
		managed: Boolean,
		path: String,
		fd: ParcelFileDescriptor,
	) = activityManager.dumpHeap(process, userId, managed, path, fd)

	@SuppressLint("ObsoleteSdkInt")
	@RequiresApi(Build.VERSION_CODES.P)
	private fun dumpHeapApi28(
		process: String,
		userId: Int,
		managed: Boolean,
		mallocInfo: Boolean,
		runGc: Boolean,
		path: String,
		fd: ParcelFileDescriptor,
	) = activityManager.dumpHeap(process, userId, managed, mallocInfo, runGc, path, fd)

	/**
	 * On API 29+ the heap dump is performed asynchronously and completion is signalled through a
	 * [RemoteCallback]. This blocks (up to [DUMP_HEAP_TIMEOUT_SECONDS]) until the dump finishes so
	 * that the caller can safely close [fd] once this returns.
	 */
	@RequiresApi(Build.VERSION_CODES.Q)
	private fun dumpHeapApi29(
		process: String,
		userId: Int,
		managed: Boolean,
		mallocInfo: Boolean,
		runGc: Boolean,
		path: String,
		fd: ParcelFileDescriptor,
	): Boolean {
		val latch = CountDownLatch(1)
		val finishCallback = RemoteCallback({ latch.countDown() }, null)
		val started =
			activityManager.dumpHeap(process, userId, managed, mallocInfo, runGc, path, fd, finishCallback)
		latch.await(DUMP_HEAP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
		return started
	}

	/**
	 * Same as [dumpHeapApi29] but for API 35 (Vanilla Ice Cream), which adds the [dumpBitmaps]
	 * parameter. Pass `null` for [dumpBitmaps] to skip dumping bitmaps.
	 */
	@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
	private fun dumpHeapApi35(
		process: String,
		userId: Int,
		managed: Boolean,
		mallocInfo: Boolean,
		runGc: Boolean,
		dumpBitmaps: String?,
		path: String,
		fd: ParcelFileDescriptor,
	): Boolean {
		val latch = CountDownLatch(1)
		val finishCallback = RemoteCallback({ latch.countDown() }, null)
		val started =
			activityManager.dumpHeap(process, userId, managed, mallocInfo, runGc, dumpBitmaps, path, fd, finishCallback)
		latch.await(DUMP_HEAP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
		return started
	}
}
