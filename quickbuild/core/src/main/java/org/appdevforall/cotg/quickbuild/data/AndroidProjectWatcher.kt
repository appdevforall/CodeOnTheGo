package org.appdevforall.cotg.quickbuild.data

import android.os.FileObserver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.watch.ChangeCoalescingDefaults
import org.appdevforall.cotg.quickbuild.domain.watch.WatchEvent
import org.appdevforall.cotg.quickbuild.domain.watch.WatchFilter
import org.appdevforall.cotg.quickbuild.domain.watch.coalesceChanges
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Watches an Android project's files on-device, reporting each settled burst as one batch:
 * raw events -> [WatchFilter] -> [coalesceChanges] debounce -> one batch. Runs on [scope].
 *
 * Hybrid by necessity: the project lives on sdcardfs/FUSE, which can drop inotify events under
 * load, so [FileObserver] gives the low-latency path and a [pollIntervalMillis] mtime sweep
 * bounds staleness when events are lost.
 *
 * @property watchedRoots directory trees walked recursively for both the inotify watches and
 *   the poll sweep; entries that are not directories are skipped rather than failing.
 * @property watchedFiles individual files outside [watchedRoots] (gradle config and kin),
 *   covered by the poll alone - no inotify watch is registered on their parent directories.
 * @property filter relevance test applied to every raw event before coalescing; drops build
 *   intermediates and editor temp files.
 * @property scope coroutine scope the pipeline and poll jobs run in; cancelling it stops the
 *   watcher as surely as [stop] does.
 * @property pollIntervalMillis delay in milliseconds between mtime+size sweeps - the upper
 *   bound on staleness when inotify drops an event.
 * @property quietMillis idle gap in milliseconds that ends a burst (see [coalesceChanges]).
 * @property maxMillis cap in milliseconds on how long one burst may keep accumulating before
 *   it is emitted regardless of quiet time.
 * @property pollDispatcher where the recurring stat walk runs; blocking IO, so it must stay off
 *   the session manager's single-threaded ordering dispatcher.
 */
class AndroidProjectWatcher(
	private val watchedRoots: List<File>,
	private val watchedFiles: List<File>,
	private val filter: WatchFilter,
	private val scope: CoroutineScope,
	private val pollIntervalMillis: Long = DEFAULT_POLL_MILLIS,
	private val quietMillis: Long = ChangeCoalescingDefaults.QUIET_MILLIS,
	private val maxMillis: Long = ChangeCoalescingDefaults.MAX_MILLIS,
	private val pollDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ProjectWatcher {
	// Unlimited so a burst of inotify events never blocks or drops on a slow drain - coalescing
	// downstream collapses the flood into one batch per burst.
	private val rawEvents = Channel<WatchEvent>(Channel.UNLIMITED)
	private val observers = mutableListOf<FileObserver>()
	private var pipelineJob: Job? = null
	private var pollJob: Job? = null

	/**
	 * Change fingerprints (path -> lastModified xor size), written by both inotify and the poll
	 * but consulted only by the poll, so a change inotify already delivered is not built twice.
	 * inotify must NOT gate on them: a same-length rewrite inside one mtime tick, or a tool that
	 * preserves mtime like `adb push`, collides and would be missed. Concurrent because both
	 * writers race; a lost race costs one harmless extra build.
	 */
	private val fingerprints = java.util.concurrent.ConcurrentHashMap<String, Long>()

	/**
	 * Starts the coalescing pipeline, registers an inotify observer per watched directory, and
	 * launches the poll sweep.
	 *
	 * @param onBatch invoked once per settled burst on [scope], after restamping; must not block,
	 *   since it runs inline on the collecting coroutine.
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
		// append to [observers] concurrently, which would throw
		// ConcurrentModificationException in a live iteration.
		val initial = synchronized(observers) { observers.toList() }
		initial.forEach(FileObserver::startWatching)

		pollJob = scope.launch(pollDispatcher) { pollLoop() }
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
	 * @param dir the directory to watch; the observer is appended to [observers] unstarted, and
	 *   the caller starts it.
	 */
	private fun observe(dir: File) {
		synchronized(observers) { observers.add(newObserver(dir)) }
	}

	/**
	 * Builds one directory's observer.
	 *
	 * One factory for every watch, whether registered at start or for a directory that appeared
	 * mid-session: the CREATE recursion has to be in all of them, or a tree created inside a
	 * mid-session directory gets no watch below its top level and its files fall back to the poll.
	 *
	 * @param dir the directory this observer reports for; unstarted, so the caller starts it.
	 * @return the observer, not yet watching and not yet in [observers].
	 */
	@Suppress("DEPRECATION") // FileObserver(File,...) is API 29+; minSdk is 28 (B5 targets 28/29).
	private fun newObserver(dir: File): FileObserver =
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
					registerCreatedTree(changed)
				}
				if (event and DELETE_MASK != 0) {
					reportDeletion(changed)
				} else {
					report(changed, fromPoll = false)
				}
			}
		}

	/**
	 * Watches [dir] and every directory beneath it, then starts them.
	 *
	 * Recursive because a tree can arrive whole - a git checkout, an unzip - and watching only its
	 * top level leaves everything deeper on the poll path.
	 *
	 * @param dir the newly created directory; the walk includes it.
	 */
	internal fun registerCreatedTree(dir: File) {
		synchronized(observers) {
			// Built fully, then published, so a live iteration of observers never sees a
			// half-built batch.
			val fresh = arrayListOf<FileObserver>()
			dir.walkTopDown().filter(File::isDirectory).forEach { d -> fresh.add(newObserver(d)) }
			fresh.forEach(FileObserver::startWatching)
			observers.addAll(fresh)
		}
	}

	/** Live inotify watch count, so a test can assert the CREATE recursion registered a whole tree. */
	internal fun watchCount(): Int = synchronized(observers) { observers.size }

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
	 * the set difference between the paths [fingerprints] tracks and what this walk saw, each
	 * one re-stat'd before it is believed. That diff is the reliable deletion floor on sdcardfs,
	 * where inotify DELETE can be dropped, but it is only a CANDIDATE list: inotify writes
	 * [fingerprints] concurrently, so a file created after this walk passed its directory is in
	 * the map and absent from the walk while very much alive.
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
			.forEach { path ->
				val file = File(path)
				// Only a stat proves a deletion. Reporting one for a live file drops its
				// fingerprint and, because coalescing is last-event-wins, collapses a real
				// save into a removal - handing the daemon a file it is still compiling and
				// re-emitting the same change on the next sweep. isFile, not exists, so a path
				// that turned into a directory still counts as vanished.
				if (!file.isFile) reportDeletion(file)
			}
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
		private val log = LoggerFactory.getLogger("QB-ProjectWatcher")
		private const val DEFAULT_POLL_MILLIS = 2_000L

		/** Deletion bits: a file removed from, or moved out of, a watched dir. */
		private const val DELETE_MASK = FileObserver.DELETE or FileObserver.MOVED_FROM
		private const val EVENT_MASK =
			FileObserver.CREATE or FileObserver.MODIFY or
				FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE or DELETE_MASK
	}
}
