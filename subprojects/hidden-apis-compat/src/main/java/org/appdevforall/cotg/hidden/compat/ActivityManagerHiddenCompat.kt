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
	fun getRunningAppProcesses(): List<ActivityManager.RunningAppProcessInfo> =
		activityManager.runningAppProcesses ?: emptyList()

	@SuppressLint("ObsoleteSdkInt")
	@RequiresApi(Build.VERSION_CODES.O)
	fun dumpHeapApi26(
		process: String,
		userId: Int,
		managed: Boolean,
		path: String,
		fd: ParcelFileDescriptor
	) = activityManager.dumpHeap(process, userId, managed, path, fd)

	@SuppressLint("ObsoleteSdkInt")
	@RequiresApi(Build.VERSION_CODES.P)
	fun dumpHeapApi28(
		process: String,
		userId: Int,
		managed: Boolean,
		mallocInfo: Boolean,
		runGc: Boolean,
		path: String,
		fd: ParcelFileDescriptor
	) = activityManager.dumpHeap(process, userId, managed, mallocInfo, runGc, path, fd)

	/**
	 * On API 29+ the heap dump is performed asynchronously and completion is signalled through a
	 * [RemoteCallback]. This blocks (up to [DUMP_HEAP_TIMEOUT_SECONDS]) until the dump finishes so
	 * that the caller can safely close [fd] once this returns.
	 */
	@RequiresApi(Build.VERSION_CODES.Q)
	fun dumpHeapApi29(
		process: String,
		userId: Int,
		managed: Boolean,
		mallocInfo: Boolean,
		runGc: Boolean,
		path: String,
		fd: ParcelFileDescriptor
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
	fun dumpHeapApi35(
		process: String,
		userId: Int,
		managed: Boolean,
		mallocInfo: Boolean,
		runGc: Boolean,
		dumpBitmaps: String?,
		path: String,
		fd: ParcelFileDescriptor
	): Boolean {
		val latch = CountDownLatch(1)
		val finishCallback = RemoteCallback({ latch.countDown() }, null)
		val started =
			activityManager.dumpHeap(process, userId, managed, mallocInfo, runGc, dumpBitmaps, path, fd, finishCallback)
		latch.await(DUMP_HEAP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
		return started
	}
}
