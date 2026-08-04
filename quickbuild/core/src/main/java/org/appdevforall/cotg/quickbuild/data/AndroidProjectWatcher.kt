package org.appdevforall.cotg.quickbuild.data

import android.os.FileObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.appdevforall.cotg.quickbuild.domain.ChangeCoalescingDefaults
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.WatchEvent
import org.appdevforall.cotg.quickbuild.domain.WatchFilter
import org.appdevforall.cotg.quickbuild.domain.coalesceChanges
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Watches an Android project's files on-device and reports each settled burst of changes as
 * one batch.
 *
 * Hybrid by necessity: the project lives under `/storage/emulated/0/...` (sdcardfs/FUSE),
 * which can drop inotify events under load, so [FileObserver] gives the low-latency path and
 * a [pollIntervalMillis] mtime sweep bounds staleness when events are lost. Both feed one
 * raw-event channel -> [WatchFilter] -> [coalesceChanges] debounce -> one [onBatch] per burst.
 * Everything runs on [scope]; [stop] tears it all down.
 *
 * @property watchedRoots directory trees walked recursively for both the inotify watches and
 *   the poll sweep; entries that are not directories are skipped rather than failing.
 * @property watchedFiles individual files outside [watchedRoots] (gradle config and kin). Only
 *   the poll covers them - no inotify watch is registered on their parent directories.
 * @property filter relevance test applied to every raw event before coalescing; drops build
 *   intermediates and editor temp files.
 * @property scope coroutine scope the pipeline, poll, and process-reader jobs run in.
 *   Cancelling it stops the watcher as surely as [stop] does.
 * @property pollIntervalMillis delay in milliseconds between mtime+size sweeps - the upper
 *   bound on staleness when inotify drops an event.
 * @property quietMillis idle gap in milliseconds that ends a burst (see [coalesceChanges]).
 * @property maxMillis cap in milliseconds on how long one burst may keep accumulating before
 *   it is emitted regardless of quiet time.
 */
class AndroidProjectWatcher(
	private val watchedRoots: List<File>,
	private val watchedFiles: List<File>,
	private val filter: WatchFilter,
	private val scope: CoroutineScope,
	private val pollIntervalMillis: Long = DEFAULT_POLL_MILLIS,
	private val quietMillis: Long = ChangeCoalescingDefaults.QUIET_MILLIS,
	private val maxMillis: Long = ChangeCoalescingDefaults.MAX_MILLIS,
) : ProjectWatcher {
	private val rawEvents = Channel<WatchEvent>(Channel.UNLIMITED)
	private val observers = mutableListOf<FileObserver>()
	private var pipelineJob: Job? = null
	private var pollJob: Job? = null

	/**
	 * Change fingerprints (path -> lastModified xor size), written by both inotify and the
	 * poll but consulted only by the poll, so a change inotify already delivered is not
	 * built twice. inotify must NOT gate on them: a same-length rewrite inside one mtime
	 * tick, or a tool that preserves mtime like `adb push`, collides and would be missed.
	 * Concurrent map because the FileObserver thread and the poll coroutine both write; a
	 * lost race costs one harmless extra build.
	 */
	private val fingerprints = java.util.concurrent.ConcurrentHashMap<String, Long>()

	/**
	 * Starts the coalescing pipeline, registers an inotify observer per watched directory, and
	 * launches the poll sweep.
	 *
	 * @param onBatch invoked once per settled burst, on [scope], after the batch's fingerprints
	 *   have been restamped. Must not block - it runs inline on the collecting coroutine.
	 */
	override fun start(onBatch: (ChangedFiles.Known) -> Unit) {
		pipelineJob =
			scope.launch {
				rawEvents
					.consumeAsFlow()
					.filter { filter.isRelevant(it.file) }
					.coalesceChanges(quietMillis, maxMillis)
					.collect { batch ->
						restampSettled(batch)
						onBatch(batch)
					}
			}

		watchedRoots.filter(File::isDirectory).forEach { root ->
			root.walkTopDown().filter(File::isDirectory).forEach(::observe)
		}
		// Snapshot before starting: an already-started observer's CREATE handler can
		// append to [observers] concurrently, which would CME a live iteration.
		val initial = synchronized(observers) { observers.toList() }
		initial.forEach(FileObserver::startWatching)

		// The recurring stat walk is blocking IO; keep it off the session manager's
		// single-threaded ordering dispatcher.
		pollJob = scope.launch(Dispatchers.IO) { pollLoop() }
		log.info("Project watcher started: {} inotify dirs + {}ms poll", observers.size, pollIntervalMillis)
	}

	/** Cancels both jobs, stops and drops every observer, and closes the raw-event channel. */
	override fun stop() {
		pollJob?.cancel()
		pollJob = null
		pipelineJob?.cancel()
		pipelineJob = null
		synchronized(observers) {
			observers.forEach(FileObserver::stopWatching)
			observers.clear()
		}
		rawEvents.close()
	}

	/**
	 * Registers an inotify observer for one directory; subdirectories created later get their own.
	 *
	 * @param dir the directory to watch. The observer is appended to [observers] but left
	 *   unstarted; the caller starts it.
	 */
	@Suppress("DEPRECATION") // FileObserver(File,...) is API 29+; minSdk is 28 (B5 targets 28/29).
	private fun observe(dir: File) {
		val observer =
			object : FileObserver(dir.absolutePath, EVENT_MASK) {
				override fun onEvent(
					event: Int,
					path: String?,
				) {
					if (path == null) return
					val changed = File(dir, path)
					// A new directory (new package, git checkout) needs its own watch, or
					// files created inside it later are invisible to inotify.
					if (event and CREATE != 0 && changed.isDirectory) {
						synchronized(observers) {
							val fresh = arrayListOf<FileObserver>()
							changed.walkTopDown().filter(File::isDirectory).forEach { d ->
								observeInto(d, fresh)
							}
							fresh.forEach(FileObserver::startWatching)
							observers.addAll(fresh)
						}
					}
					if (event and DELETE_MASK != 0) {
						reportDeletion(changed)
					} else {
						report(changed, fromPoll = false)
					}
				}
			}
		synchronized(observers) { observers.add(observer) }
	}

	/**
	 * Builds (but does not start) an observer for [dir], appending it to [into].
	 *
	 * @param dir the newly created directory to watch.
	 * @param into collector the caller starts and then merges into [observers], so a live
	 *   iteration of [observers] cannot see a half-built batch.
	 */
	@Suppress("DEPRECATION") // FileObserver(File,...) is API 29+; minSdk is 28 (B5 targets 28/29).
	private fun observeInto(dir: File, into: MutableList<FileObserver>) {
		into.add(
			object : FileObserver(dir.absolutePath, EVENT_MASK) {
				override fun onEvent(
					event: Int,
					path: String?,
				) {
					if (path == null) return
					val changed = File(dir, path)
					if (event and DELETE_MASK != 0) {
						reportDeletion(changed)
					} else {
						report(changed, fromPoll = false)
					}
				}
			},
		)
	}

	/**
	 * Sweeps the watched roots on a timer - the safety net that catches whatever inotify
	 * dropped, bounding staleness to one interval. Only stats files, never reads them.
	 */
	private suspend fun pollLoop() {
		initFingerprints() // prime without firing: current on-disk state is the baseline
		while (scope.isActive) {
			delay(pollIntervalMillis)
			sweep()
		}
	}

	/**
	 * Runs one mtime+size sweep: modifications and creations via [report], then deletions as
	 * the set difference between the paths [fingerprints] tracks and what this walk saw. That
	 * diff is the reliable deletion floor on sdcardfs, where inotify DELETE can be dropped.
	 * The `filterTo` copy is required - [reportDeletion] mutates the map being walked.
	 * Internal so tests can drive one sweep instead of racing the timer.
	 */
	internal fun sweep() {
		val current = HashSet<String>()
		forEachWatchedFile { file ->
			current.add(file.absolutePath)
			report(file, fromPoll = true)
		}
		fingerprints.keys
			.filterTo(ArrayList()) { it !in current }
			.forEach { path -> reportDeletion(File(path)) }
	}

	/**
	 * Re-records each delivered file's fingerprint once the batch has settled, so the next poll
	 * sweep does not re-emit it as a phantom second batch (one save, two builds). Stamps taken
	 * inside an inotify callback can be stale - `adb push` rewrites mtime after the CLOSE_WRITE
	 * that fingerprinted it. A later real write is still never missed: its own event emits
	 * unconditionally, and a dropped event leaves a stamp differing from the one recorded here.
	 *
	 * @param batch the coalesced set about to be handed to the caller; only its still-existing
	 *   regular files are restamped, and [ChangedFiles.Known.removed] is deliberately untouched.
	 */
	private fun restampSettled(batch: ChangedFiles.Known) {
		batch.files.forEach { file ->
			if (file.isFile) {
				fingerprints[file.absolutePath] = file.lastModified() xor file.length()
			}
		}
	}

	/**
	 * Fingerprints a live file and emits it - the one choke point for both inotify and the poll.
	 * Only the poll gates emission on the fingerprint (see [fingerprints] for why inotify must
	 * not). Directories are dropped: never a compile input, and routing one to the classifier
	 * would wrongly trip a full rebaseline. Deletions must take the separate [reportDeletion]
	 * path, or the `!isFile` guard here would swallow them. Internal so tests can drive it.
	 *
	 * @param file the path that changed; ignored unless it is an existing regular file, so a
	 *   directory or an already-deleted path is a no-op.
	 * @param fromPoll true when the mtime sweep found it, which emits only if the fingerprint
	 *   actually moved; false for an inotify delivery, which always emits.
	 */
	internal fun report(
		file: File,
		fromPoll: Boolean,
	) {
		if (!file.isFile) return
		val stamp = file.lastModified() xor file.length()
		val previous = fingerprints.put(file.absolutePath, stamp)
		if (!fromPoll || previous != stamp) {
			rawEvents.trySend(WatchEvent.Modified(file))
		}
	}

	/**
	 * Emits a [WatchEvent.Removed] for a path we were actually tracking. Gating on the
	 * [fingerprints] removal makes it fire exactly once whether inotify or the poll notices
	 * first, and skips paths never tracked (a subdir, or a temp created and gone between
	 * sweeps). Whether a removal is real work or noise is decided downstream.
	 *
	 * @param file the vanished path; ignored unless [fingerprints] was tracking it.
	 */
	private fun reportDeletion(file: File) {
		if (fingerprints.remove(file.absolutePath) != null) {
			rawEvents.trySend(WatchEvent.Removed(file))
		}
	}

	/** Stamps the current on-disk state as the poll's baseline, without emitting any event. */
	private fun initFingerprints() {
		forEachWatchedFile { f -> fingerprints[f.absolutePath] = f.lastModified() xor f.length() }
	}

	/**
	 * Visits every regular file currently under [watchedRoots], then each existing entry of
	 * [watchedFiles].
	 *
	 * @param action called per file, not deduplicated - a [watchedFiles] entry that also sits
	 *   under a watched root is visited twice.
	 */
	private inline fun forEachWatchedFile(action: (File) -> Unit) {
		watchedRoots.filter(File::isDirectory).forEach { root ->
			root.walkTopDown().filter(File::isFile).forEach(action)
		}
		watchedFiles.filter(File::isFile).forEach(action)
	}

	private companion object {
		private val log = LoggerFactory.getLogger(AndroidProjectWatcher::class.java)
		private const val DEFAULT_POLL_MILLIS = 2_000L

		/** Deletion bits: a file removed from, or moved out of, a watched dir. */
		private const val DELETE_MASK = FileObserver.DELETE or FileObserver.MOVED_FROM
		private const val EVENT_MASK =
			FileObserver.CREATE or FileObserver.MODIFY or
				FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE or DELETE_MASK
	}
}
