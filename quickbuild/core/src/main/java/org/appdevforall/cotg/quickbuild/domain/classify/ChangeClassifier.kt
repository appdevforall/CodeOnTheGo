package org.appdevforall.cotg.quickbuild.domain.classify

import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.annotations.AnnotationImpact
import java.io.File

/**
 * Picks the cheapest correct [BuildRoute] for a coalesced changed-set.
 *
 * Classification is by path shape, not file content: Gradle build files and
 * `AndroidManifest.xml` invalidate the session, `src/<sourceSet>/res` and `.../assets` hold
 * resources and assets, `.kt`/`.java` are code, and anything else under `src/` routes to Gradle
 * because the live reload path does not implement its packaging.
 *
 * @param annotationImpact the one content-aware step: with a KSP/kapt processor configured, a
 *   changed source that could have moved generated code escalates to a Gradle rebaseline
 *   ([AnnotationImpact.Inactive] leaves a processor-free project unaffected).
 * @param fastPathRoots the app module's live-reload source scope - the quick path compiles only
 *   that module against a frozen dependency classpath, so a change elsewhere routes to
 *   [InvalidationReason.NON_APP_MODULE_SOURCE_CHANGED], and an empty list disables the boundary.
 * @param assetsLiveReloadable whether this device can serve a deployed asset payload - the
 *   runtime's asset overlay rides the API 30+ `ResourcesLoader`, so false routes any
 *   asset-bearing set to Gradle rather than acking a reload the app cannot see.
 */
class ChangeClassifier(
	private val annotationImpact: AnnotationImpact = AnnotationImpact.Inactive,
	private val fastPathRoots: List<File> = emptyList(),
	private val assetsLiveReloadable: Boolean = true,
) {
	/**
	 * Routes one coalesced changed-set.
	 *
	 * [ChangedFiles.Unknown] recompiles everything ON the quick path
	 * ([BuildRoute.CodeAndResources]), not as a Gradle fallback - unless a processor is
	 * configured, since an unenumerable change cannot be proven to miss processor input.
	 *
	 * @param changes the coalesced changed-set for one build; modified and removed paths are
	 *   classified alike, by shape.
	 * @return the cheapest correct route, which is [BuildRoute.FullGradleBuild] as soon as any
	 *   single path in the set demands it - the verdict is not per file.
	 */
	fun classify(changes: ChangedFiles): BuildRoute {
		val known =
			when (changes) {
				ChangedFiles.Unknown -> {
					return if (annotationImpact.active) {
						BuildRoute.FullGradleBuild(InvalidationReason.ANNOTATION_PROCESSOR_INPUT_CHANGED)
					} else {
						BuildRoute.CodeAndResources
					}
				}

				is ChangedFiles.Known -> {
					changes
				}
			}

		if (known.isEmpty) {
			return BuildRoute.NoOp
		}

		var hasResources = false
		var hasAssets = false
		val codeFiles = mutableListOf<File>()

		// A removed file classifies by the same path shape as a modified one - its role is
		// still legible from its extension even though the file is gone. Removals with no
		// recognized shape are dropped upstream (QuickBuildSessionManager.onWatcherBatch).
		for (file in known.files + known.removed) {
			val kind = kindOf(file)
			if (kind == FileKind.CODE || kind == FileKind.RESOURCE || kind == FileKind.ASSET) {
				if (fastPathRoots.isNotEmpty() && fastPathRoots.none { isUnder(file, it) }) {
					return BuildRoute.FullGradleBuild(InvalidationReason.NON_APP_MODULE_SOURCE_CHANGED)
				}
			}
			when (kind) {
				FileKind.GRADLE_CONFIG -> {
					return BuildRoute.FullGradleBuild(InvalidationReason.GRADLE_CONFIG_CHANGED)
				}

				FileKind.MANIFEST -> {
					return BuildRoute.FullGradleBuild(InvalidationReason.MANIFEST_CHANGED)
				}

				FileKind.UNSUPPORTED -> {
					return BuildRoute.FullGradleBuild(InvalidationReason.UNSUPPORTED_FILE_CHANGED)
				}

				FileKind.CODE -> {
					codeFiles += file
				}

				FileKind.RESOURCE -> {
					hasResources = true
				}

				FileKind.ASSET -> {
					hasAssets = true
				}
			}
		}

		if (codeFiles.isNotEmpty() && annotationImpact.escalation(codeFiles.sorted()) != null) {
			return BuildRoute.FullGradleBuild(InvalidationReason.ANNOTATION_PROCESSOR_INPUT_CHANGED)
		}

		// Changed assets ride in EVERY route's deploy payload, so a device that cannot serve
		// them makes any asset-bearing set stale - not just an assets-only one.
		if (hasAssets && !assetsLiveReloadable) {
			return BuildRoute.FullGradleBuild(InvalidationReason.UNSUPPORTED_FILE_CHANGED)
		}

		return when {
			codeFiles.isNotEmpty() && hasResources -> BuildRoute.CodeAndResources
			codeFiles.isNotEmpty() -> BuildRoute.CodeOnly
			hasResources -> BuildRoute.ResourcesOnly
			hasAssets -> BuildRoute.AssetsOnly
			else -> BuildRoute.NoOp
		}
	}

	private enum class FileKind { GRADLE_CONFIG, MANIFEST, CODE, RESOURCE, ASSET, UNSUPPORTED }

	companion object {
		private val GRADLE_FILE_NAMES =
			setOf(
				"build.gradle",
				"build.gradle.kts",
				"settings.gradle",
				"settings.gradle.kts",
				"gradle.properties",
				"local.properties",
			)

		private fun kindOf(file: File): FileKind {
			val name = file.name

			if (name in GRADLE_FILE_NAMES || (name.endsWith(".toml") && hasSegment(file, "gradle"))) {
				return FileKind.GRADLE_CONFIG
			}
			if (hasSegment(file, "wrapper") && name == "gradle-wrapper.properties") {
				return FileKind.GRADLE_CONFIG
			}
			if (name == "AndroidManifest.xml") {
				return FileKind.MANIFEST
			}

			if (hasSourceSetDir(file, "res")) {
				return FileKind.RESOURCE
			}
			if (hasSourceSetDir(file, "assets")) {
				return FileKind.ASSET
			}
			if (name.endsWith(".kt") || name.endsWith(".java")) {
				return FileKind.CODE
			}
			return FileKind.UNSUPPORTED
		}

		/**
		 * True when [file] is [dir] or lives under it.
		 *
		 * @param file the changed path being classified; absolute from the watcher,
		 *   relative in unit tests.
		 * @param dir the candidate ancestor, which must share a base with [file] because
		 *   parent-chain entries are compared by equality, not canonicalized.
		 * @return true when [file] equals [dir] or [dir] appears in its parent chain.
		 */
		private fun isUnder(
			file: File,
			dir: File,
		): Boolean {
			var current: File? = file
			while (current != null) {
				if (current == dir) return true
				current = current.parentFile
			}
			return false
		}

		/**
		 * True when [segment] appears as a whole path segment of [file]'s parent chain.
		 *
		 * @param file the changed path; only its directories are scanned, never its own name.
		 * @param segment one exact directory name to match, e.g. "gradle" or "wrapper".
		 * @return true when some ancestor directory of [file] is named [segment].
		 */
		private fun hasSegment(
			file: File,
			segment: String,
		): Boolean {
			var current: File? = file.parentFile
			while (current != null) {
				if (current.name == segment) return true
				current = current.parentFile
			}
			return false
		}

		/**
		 * True when [file] sits at `<module>/src/<sourceSet>/<segment>/...`.
		 *
		 * Anchored to that depth rather than scanning the whole parent chain because `res` and
		 * `assets` are legal package names: an unanchored scan reads
		 * `src/main/java/com/example/res/Strings.kt` as a resource, so aapt2 relinks, nothing
		 * compiles, and the user's edit is silently absent from the running app.
		 *
		 * @param file the changed path; only its directories are scanned, never its own name.
		 * @param segment the source-set child to match exactly, "res" or "assets".
		 * @return true when some ancestor is named [segment] and is a grandchild of a `src` dir.
		 */
		private fun hasSourceSetDir(
			file: File,
			segment: String,
		): Boolean {
			var current: File? = file.parentFile
			while (current != null) {
				if (current.name == segment && current.parentFile?.parentFile?.name == "src") {
					return true
				}
				current = current.parentFile
			}
			return false
		}

		/**
		 * True when [file]'s path shape alone names a role this classifier knows (Gradle config,
		 * manifest, code, resource, asset). No filesystem access - the file need not exist.
		 *
		 * Lets a caller drop a vanished path that never had a role, such as an atomic-rename
		 * tool's temp sibling (`sedXXXXXX`), as noise.
		 *
		 * @param file the path to weigh; usually one already deleted from disk.
		 * @return true when the shape names a known role, which alone does not force a Gradle
		 *   fallback for a deletion - the caller still decides.
		 */
		fun hasRecognizedShape(file: File): Boolean = kindOf(file) != FileKind.UNSUPPORTED

		/**
		 * True when [file]'s path shape says it is an Android resource - under a source set's own
		 * `res/` directory, `src/<sourceSet>/res/...`. No filesystem access.
		 *
		 * Lets a caller attribute a diagnostic to aapt2 rather than kotlinc without plumbing the
		 * producing tool through the outcome: the two never mix, because a failed compile returns
		 * before the relink runs.
		 *
		 * @param file the path a diagnostic named.
		 * @return true when the path is a resource under the app's sources.
		 */
		fun namesResource(file: File): Boolean = kindOf(file) == FileKind.RESOURCE
	}
}
