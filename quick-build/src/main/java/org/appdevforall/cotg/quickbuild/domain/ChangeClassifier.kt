package org.appdevforall.cotg.quickbuild.domain

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
 * session manager's job to detect via fingerprints, not the classifier's.)
 */
class ChangeClassifier {
	fun classify(changes: ChangedFiles): BuildRoute {
		val known =
			when (changes) {
				ChangedFiles.Unknown -> return BuildRoute.CodeAndResources
				is ChangedFiles.Known -> changes
			}

		if (known.files.isEmpty()) {
			return BuildRoute.NoOp
		}

		var hasCode = false
		var hasResources = false
		var hasAssets = false

		for (file in known.files) {
			when (kindOf(file)) {
				FileKind.GRADLE_CONFIG ->
					return BuildRoute.FullGradleBuild(InvalidationReason.GRADLE_CONFIG_CHANGED)
				FileKind.MANIFEST ->
					return BuildRoute.FullGradleBuild(InvalidationReason.MANIFEST_CHANGED)
				FileKind.UNSUPPORTED ->
					return BuildRoute.FullGradleBuild(InvalidationReason.UNSUPPORTED_FILE_CHANGED)
				FileKind.CODE -> hasCode = true
				FileKind.RESOURCE -> hasResources = true
				FileKind.ASSET -> hasAssets = true
			}
		}

		return when {
			hasCode && hasResources -> BuildRoute.CodeAndResources
			hasCode -> BuildRoute.CodeOnly
			hasResources -> BuildRoute.ResourcesOnly
			hasAssets -> BuildRoute.AssetsOnly
			else -> BuildRoute.NoOp
		}
	}

	private enum class FileKind { GRADLE_CONFIG, MANIFEST, CODE, RESOURCE, ASSET, UNSUPPORTED }

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

	private companion object {
		val GRADLE_FILE_NAMES =
			setOf(
				"build.gradle",
				"build.gradle.kts",
				"settings.gradle",
				"settings.gradle.kts",
				"gradle.properties",
				"local.properties",
			)
	}
}
