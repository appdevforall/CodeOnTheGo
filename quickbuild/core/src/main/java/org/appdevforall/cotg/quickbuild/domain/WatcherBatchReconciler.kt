package org.appdevforall.cotg.quickbuild.domain

import java.io.File

/**
 * Reconciles a raw watcher batch into the modified/removed split the pipeline builds against.
 *
 * A path reported as modified but already gone is reclassified: with a recognized project-file
 * shape it is a deletion the modify channel caught (a `git checkout` rename whose target was
 * then dropped), and without one it is a rename-tool temp and is dropped as noise - otherwise a
 * stray temp would push the whole batch to a spurious [BuildRoute.FullGradleBuild]. A file that
 * persists but cannot be classified stays in the modified set and keeps its Gradle fallback.
 */
object WatcherBatchReconciler {
	/**
	 * Splits [batch] into the files that still exist, the deletions, and the noise to drop.
	 *
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
				// else: unrecognized vanished temp -> drop as noise.
			}
		}
		return ChangedFiles.Known(modified, removed)
	}
}
