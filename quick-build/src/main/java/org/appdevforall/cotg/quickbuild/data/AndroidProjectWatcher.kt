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
 * On-device [ProjectWatcher]: a hybrid of inotify ([FileObserver]) for latency and a
 * periodic mtime poll for correctness (design-watcher-and-testing.md section 2).
 *
 * Why hybrid: the project lives under `/storage/emulated/0/...` (sdcardfs/FUSE), where
 * inotify events can be dropped under load. inotify alone risks a silently-stale app - a
 * direct hit on the never-stale invariant. So inotify provides the low-latency path and a cheap
 * [pollIntervalMillis] mtime sweep is the floor that bounds staleness regardless of drops.
 *
 * Both feed one raw-event channel -> [WatchFilter] relevance -> [coalesceChanges] debounce
 * -> a single [onBatch] per burst. Everything runs on [scope]; [stop] tears it all down.
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
	 * Change fingerprints (path -> lastModified xor size), written by BOTH inotify and
	 * the poll but consulted ONLY by the poll. inotify events always emit (the debounce
	 * batch dedupes) and record the new fingerprint, so the poll's next sweep sees no
	 * change and stays quiet - that is what keeps the hybrid from double-building.
	 * Gating inotify on the fingerprint too would MISS a real change whose stamp
	 * collides (same-length rewrite within one mtime tick, or a tool that preserves
	 * mtime like `adb push`) - a silent never-stale violation. Concurrent access
	 * (FileObserver thread vs poll coroutine) is fine on a concurrent map - a rare lost
	 * race just costs one extra (harmless) build.
	 */
	private val fingerprints = java.util.concurrent.ConcurrentHashMap<String, Long>()

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
		// single-threaded ordering dispatcher. Batches hop back via onBatch's launch.
		pollJob = scope.launch(Dispatchers.IO) { pollLoop() }
		log.info("Project watcher started: {} inotify dirs + {}ms poll", observers.size, pollIntervalMillis)
	}

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

	/** inotify watch for one directory. A newly-created subdir is observed on the fly. */
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

	/** Build (but don't start) an observer for [dir], appending it to [into]. */
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
	 * mtime+size sweep of the watched roots and files - the safety net that catches
	 * whatever inotify dropped (sdcardfs), bounding staleness to one interval. Cheap: a
	 * `stat` walk, no file contents read. [report] dedupes against inotify via the shared
	 * fingerprints, so a change inotify already handled is not rebuilt again here.
	 */
	private suspend fun pollLoop() {
		initFingerprints() // prime without firing: current on-disk state is the baseline
		while (scope.isActive) {
			delay(pollIntervalMillis)
			sweep()
		}
	}

	/**
	 * One mtime+size sweep: emit modifications/creations via [report], then detect
	 * deletions by SET DIFF - any path we were tracking (in [fingerprints]) that this fresh
	 * walk no longer sees is gone. [report] only ever records live files, so the difference
	 * is exactly the vanished paths; this is the reliable deletion floor on sdcardfs, where
	 * inotify DELETE can be dropped. [reportDeletion] removes each from the map and emits a
	 * [WatchEvent.Removed] (whichever of inotify/poll observes the vanish first wins - the
	 * other then sees nothing to remove). Snapshot the current paths BEFORE diffing, since
	 * [report] mutates the same map it walks. Internal so unit tests can drive one sweep
	 * deterministically instead of racing the timer.
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
	 * Re-record each delivered file's fingerprint at batch-SETTLE time. The stamp taken
	 * inside an inotify callback can be stale by the time the batch flushes: `adb push`
	 * rewrites the file's mtime (utimensat, no masked event) AFTER the CLOSE_WRITE that
	 * fingerprinted it, and a MODIFY handler can read mid-write attrs. The next poll sweep
	 * would then see a stamp "change" for content this batch already delivered and re-emit
	 * it as a phantom second batch - one save became two builds, and a manifest edit became
	 * two invalidations/rebaselines (the phantom lands mid-rebaseline and re-classifies
	 * after the baseline reset). Settle time - at least [quietMillis] after the last event -
	 * is when the attrs are final, so the recorded fingerprint matches what the poll will
	 * read. A REAL later write is still never missed: its inotify event emits
	 * unconditionally, and if that event is dropped its settled stamp differs from the one
	 * recorded here, so the poll fires as before. A file already gone at settle is skipped -
	 * the deletion path owns removing its fingerprint.
	 */
	private fun restampSettled(batch: ChangedFiles.Known) {
		batch.files.forEach { file ->
			if (file.isFile) {
				fingerprints[file.absolutePath] = file.lastModified() xor file.length()
			}
		}
	}

	/**
	 * The single choke point for a live file from both inotify and the poll - files only (a
	 * directory is never a compile input, and routing one to the classifier would wrongly
	 * trip a full rebaseline). Records [file]'s current fingerprint either way; only the
	 * poll gates emission on it (see [fingerprints] for why inotify must not). Deletions
	 * take the separate [reportDeletion] path - a gone file has no stamp to fingerprint and
	 * must reach the pipeline as a [WatchEvent.Removed], not be dropped here by `!isFile`.
	 * Internal so unit tests can simulate an inotify delivery (FileObserver is inert on the
	 * JVM).
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
	 * Emit a deletion for a path we were actually tracking. Gating on [fingerprints]
	 * removal makes it fire exactly once whether inotify or the poll notices first, and
	 * skips a path we never tracked (a subdir, or a temp created-and-gone between sweeps).
	 * Whether a removal is real work or noise (an unrecognized-shape temp) is decided
	 * downstream by [org.appdevforall.cotg.quickbuild.service.QuickBuildSessionManager.onWatcherBatch].
	 */
	private fun reportDeletion(file: File) {
		if (fingerprints.remove(file.absolutePath) != null) {
			rawEvents.trySend(WatchEvent.Removed(file))
		}
	}

	private fun initFingerprints() {
		forEachWatchedFile { f -> fingerprints[f.absolutePath] = f.lastModified() xor f.length() }
	}

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
