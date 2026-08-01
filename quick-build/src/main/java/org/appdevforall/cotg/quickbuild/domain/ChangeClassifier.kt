package org.appdevforall.cotg.quickbuild.domain

import org.appdevforall.cotg.quickbuild.domain.annotations.AnnotationImpact
import java.io.File

/**
 * Classifies a coalesced changed-set into the cheapest correct [BuildRoute]
 * (plan section 2.3). Pure logic — safe to unit-test without a project on disk.
 *
 * Classification is by path shape, not file content:
 * - Gradle build files and `AndroidManifest.xml` invalidate the session (full Gradle build).
 * - Files under a `res/` directory inside `src/` are resources; under `assets/` are assets.
 * - `.kt`/`.java` sources are code.
 * - Anything else under `src/` (e.g. a java-resource `.properties`) is honest-fallback
 *   territory: the quick path doesn't implement its packaging, so route to Gradle rather
 *   than risk serving a stale artifact.
 *
 * [ChangedFiles.Unknown] routes to [BuildRoute.CodeAndResources]: the incremental engine
 * recompiles everything and resources are relinked — a slow-but-correct quick build, not
 * a Gradle fallback. (Baseline drift while CoGo was dead — manifest/gradle edits — is the
 * session manager's job to detect via fingerprints, not the classifier's.) With annotation
 * processors configured, `Unknown` DOES fall back: we cannot prove an unenumerable change
 * missed processor input.
 *
 * The one content-aware step is [annotationImpact]: on a project with a KSP/kapt processor,
 * a changed source that could have moved generated code escalates to a Gradle rebaseline.
 * Default [AnnotationImpact.Inactive] keeps a processor-free project exactly as it was.
 *
 * [fastPathRoots] are the app module's live-reload source scope (its `src` root). The quick
 * path incrementally compiles ONLY the app module against a frozen dependency classpath, so
 * a code/resource/asset change in ANOTHER Gradle module (a library/feature module) cannot be
 * absorbed - it routes to [BuildRoute.FullGradleBuild] ([InvalidationReason.NON_APP_MODULE_SOURCE_CHANGED]).
 * Empty [fastPathRoots] disables the boundary (single-module projects, and the pure-shape
 * unit tests), preserving pre-multi-module behavior. Watching those other modules at all is
 * the session/layer's job (so the edit is SEEN and rebaselined, not silently dropped); the
 * classifier's job is only to route a seen out-of-module change honestly.
 */
class ChangeClassifier(
	private val annotationImpact: AnnotationImpact = AnnotationImpact.Inactive,
	private val fastPathRoots: List<File> = emptyList(),
) {
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

		// A removed file classifies by the same path SHAPE as a modified one - its role is
		// still legible from its extension/path even though it's gone (see
		// [ChangedFiles.Known.removed]). A removed `.kt`/`.java` is a code change (its
		// output must be dropped + dependents recompiled), a removed `res/` file a resource
		// change (the relink re-links the shrunk current set), a removed gradle/manifest a
		// rebaseline. Removals with no recognized shape are dropped upstream
		// ([QuickBuildSessionManager.onWatcherBatch]), so only recognized ones arrive here.
		for (file in known.files + known.removed) {
			val kind = kindOf(file)
			// A code/resource/asset change outside the app module's live-reload scope belongs
			// to another Gradle module the quick path can't incrementally build - rebaseline
			// rather than fast-compile it against the app module (or silently drop it). Empty
			// fastPathRoots = no boundary (single-module fallback / pure-shape unit tests).
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

			val underSrc = hasSegment(file, "src")
			if (underSrc && hasSegment(file, "res")) {
				return FileKind.RESOURCE
			}
			if (underSrc && hasSegment(file, "assets")) {
				return FileKind.ASSET
			}
			if (name.endsWith(".kt") || name.endsWith(".java")) {
				return FileKind.CODE
			}
			return FileKind.UNSUPPORTED
		}

		/**
		 * True when [file] is [dir] or lives underneath it. Walks the parent chain by
		 * equality (like [hasSegment]) so it works for both the absolute paths the watcher
		 * emits and the relative paths unit tests use, as long as both sides share a base.
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

		/** True when [segment] appears as a whole path segment of [file]'s parent chain. */
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
		 * True when [file]'s path SHAPE ALONE — no filesystem access, existence-agnostic —
		 * is one this classifier can name a role for (Gradle config, manifest, code,
		 * resource, or asset), as opposed to the honest-fallback/unsupported bucket.
		 *
		 * Lets a caller tell a genuinely deleted project file (still has a recognized
		 * shape, e.g. a `git checkout` that removes a tracked `.kt`) from a vanished path
		 * with NO recognizable shape at all — e.g. an external atomic-rename tool's sibling
		 * temp file (`sedXXXXXX`) — which is safe to treat as pure noise. See
		 * [org.appdevforall.cotg.quickbuild.service.QuickBuildSessionManager.onWatcherBatch].
		 *
		 * Deliberately narrow: a "true" result does NOT by itself force
		 * [BuildRoute.FullGradleBuild] for a deletion — [kindOf] never checks existence, so
		 * a kept-but-deleted `.kt` still classifies as ordinary [FileKind.CODE] and takes
		 * the same live reload path a live edit would. This function only decides what's safe to
		 * drop as noise, not how a genuine deletion should ultimately be handled — that's
		 * unchanged, pre-existing classifier behavior, out of scope here.
		 */
		fun hasRecognizedShape(file: File): Boolean = kindOf(file) != FileKind.UNSUPPORTED
	}
}
