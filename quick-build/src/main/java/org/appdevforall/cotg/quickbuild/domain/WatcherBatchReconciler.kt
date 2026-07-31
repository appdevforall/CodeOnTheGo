package org.appdevforall.cotg.quickbuild.domain

import java.io.File

/**
 * Reconciles a raw watcher batch into the modified/removed split the pipeline builds
 * against. Pure decision logic - the filesystem probe is injected, so the policy
 * unit-tests without files on disk (the session manager passes `File.isFile`).
 *
 * Rules:
 * - A modified path the probe still sees is a live edit/create -> kept as modified.
 * - A modified path that has since VANISHED but has a recognized project-file shape is
 *   a deletion the modify channel caught (a `git checkout` MOVED_TO whose target was
 *   then dropped) -> reclassified as a removal, so it takes the same removed-sources
 *   path a poll-detected deletion does, rather than being compiled as a (gone) source.
 * - A path in the watcher's removed set with a recognized shape is a real deletion ->
 *   kept as a removal.
 * - A vanished path with NO recognized shape is pure noise (an external atomic-rename
 *   tool's sibling temp, `sedXXXXXX`, or a `patch` dropping the delete detector caught)
 *   -> dropped, exactly as bug11 drops a vanished modified temp; without this a stray
 *   temp would poison the whole batch to a spurious [BuildRoute.FullGradleBuild].
 *
 * A genuinely persisting unclassifiable file (a real java-resource) is left in the
 * modified set and still reaches the classifier, preserving its honest Gradle fallback.
 */
object WatcherBatchReconciler {
	/**
	 * @param exists whether the path is currently a live file; production passes
	 *   `File.isFile` (a path that turned into a directory counts as vanished).
	 */
	fun reconcile(
		batch: ChangedFiles.Known,
		exists: (File) -> Boolean,
	): ChangedFiles.Known {
		val modified = HashSet<File>()
		val removed = HashSet<File>()
		batch.removed.filterTo(removed, ChangeClassifier::hasRecognizedShape)
		for (file in batch.files) {
			when {
				exists(file) -> modified.add(file)
				ChangeClassifier.hasRecognizedShape(file) -> removed.add(file)
				// else: unrecognized vanished temp -> drop as noise (bug11 semantics).
			}
		}
		return ChangedFiles.Known(modified, removed)
	}
}
