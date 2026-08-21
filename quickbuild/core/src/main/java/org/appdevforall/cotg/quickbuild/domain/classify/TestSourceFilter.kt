package org.appdevforall.cotg.quickbuild.domain.classify

import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import java.io.File

/**
 * Splits a watcher batch into the part a build should act on and the test-source saves it should
 * ignore.
 *
 * Test source sets are watched but never built: the watch scope is the whole `src` tree (narrowing
 * it would trade a wrong build for silent staleness elsewhere), while nothing under `src/test`,
 * `src/androidTest` or `testFixtures` ships in the variant Quick Build deploys. Routing such a save
 * to a full Gradle build was honest but useless - it spends minutes producing an app that cannot
 * differ. Ignoring it outright is right, and the once-per-session notice is what keeps "ignored"
 * from reading as "broken".
 *
 * Only test-type source sets. `src/debug` and flavor source sets DO ship in the built variant, so
 * they keep their full-Gradle-build route.
 */
object TestSourceFilter {
	/**
	 * The buildable remainder of [batch], and whether anything was dropped as a test source.
	 *
	 * @property buildable everything [batch] held that is not a test source; may be empty, which
	 *   means the whole batch was test sources and no build should run at all.
	 * @property droppedTestSources whether at least one path was dropped, which is what the
	 *   user is owed a notice about - true even when [buildable] still has work, since the
	 *   dropped save did not deploy either way.
	 */
	data class Split(
		val buildable: ChangedFiles.Known,
		val droppedTestSources: Boolean,
	)

	/**
	 * Partitions one batch by source set.
	 *
	 * @param batch a reconciled watcher batch; modified and removed paths are split alike, since
	 *   deleting a test file is no more deployable than saving one.
	 * @return the split; [Split.buildable] is never larger than [batch].
	 */
	fun split(batch: ChangedFiles.Known): Split {
		val modified = batch.files.filterNotTo(HashSet(), ::isTestSource)
		val removed = batch.removed.filterNotTo(HashSet(), ::isTestSource)
		val dropped =
			modified.size != batch.files.size || removed.size != batch.removed.size
		return Split(ChangedFiles.Known(modified, removed), dropped)
	}

	/**
	 * True when [file] sits in a test-type source set, `<module>/src/<sourceSet>/...`.
	 *
	 * Reads the source set the same way the classifier does - the innermost `src` child on the
	 * parent chain - so a package named `test` cannot make an ordinary source look like a test.
	 *
	 * @param file the changed path; no filesystem access, so it need not still exist.
	 * @return true when its source set is a test one, in any module.
	 */
	fun isTestSource(file: File): Boolean = ChangeClassifier.sourceSetName(file)?.let(::namesTestSourceSet) == true

	/**
	 * True when [name] is a source set AGP builds for tests rather than for the app.
	 *
	 * Matches on the camelCase boundary rather than a bare prefix, because AGP appends the flavor
	 * and build type (`testProDebug`, `androidTestDebug`) and a flavor may itself begin with the
	 * letters "test" - `testflavor` is a shipping source set and must not be ignored.
	 *
	 * @param name a source-set directory name, e.g. `main`, `debug`, `androidTestDebug`.
	 * @return true for `test*`, `androidTest*` and `testFixtures*`; false for `main`, `debug` and
	 *   every flavor source set.
	 */
	private fun namesTestSourceSet(name: String): Boolean = hasCamelPrefix(name, "test") || hasCamelPrefix(name, "androidTest")

	/**
	 * True when [name] is exactly [prefix] or continues it at a camelCase boundary.
	 *
	 * @param name the source-set name to test.
	 * @param prefix the lower-camel prefix that identifies the family.
	 */
	private fun hasCamelPrefix(
		name: String,
		prefix: String,
	): Boolean {
		if (!name.startsWith(prefix)) return false
		return name.length == prefix.length || name[prefix.length].isUpperCase()
	}
}
