package org.appdevforall.cotg.quickbuild.domain.watch

import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.classify.ChangeClassifier
import java.io.File

/**
 * Reconciles a raw watcher batch into the modified/removed split the pipeline builds against.
 *
 * A path reported as modified but already gone is reclassified: if it names a project file it is a
 * deletion the modify channel caught (a `git checkout` rename whose target was then dropped), and
 * otherwise it is a rename-tool temp dropped as noise - without that, a stray temp would push the
 * whole batch to a spurious
 * [org.appdevforall.cotg.quickbuild.domain.classify.BuildRoute.FullGradleBuild].
 *
 * "Names a project file" is [ChangeClassifier.namesProjectFile], not the narrower recognized-shape
 * test: an extension-bearing path the classifier calls UNSUPPORTED is still a packaged file, and
 * dropping its deletion leaves the proxy app serving content the project no longer has.
 */
object WatcherBatchReconciler {
	/**
	 * Splits [batch] into the files that still exist, the deletions, and the noise to drop.
	 *
	 * @param batch the coalesced watcher batch, whose `files` may name paths already gone.
	 * @param exists whether the path is currently a live file; production passes
	 *   `File.isFile` (a path that turned into a directory counts as vanished).
	 * @return the same batch with vanished paths moved to `removed` or dropped; never larger than
	 *   [batch].
	 */
	fun reconcile(
		batch: ChangedFiles.Known,
		exists: (File) -> Boolean,
	): ChangedFiles.Known {
		val modified = HashSet<File>()
		val removed = HashSet<File>()
		batch.removed.filterTo(removed, ChangeClassifier::namesProjectFile)
		for (file in batch.files) {
			when {
				exists(file) -> modified.add(file)
				ChangeClassifier.namesProjectFile(file) -> removed.add(file)
				// else: unrecognized vanished temp -> drop as noise.
			}
		}
		return ChangedFiles.Known(modified, removed)
	}
}
