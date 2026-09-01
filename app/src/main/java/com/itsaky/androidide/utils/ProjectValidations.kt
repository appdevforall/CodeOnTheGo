package com.itsaky.androidide.utils

import java.io.File
import java.text.Normalizer
import kotlin.collections.filter
import kotlin.collections.orEmpty

/** Checks if the file is a readable, visible directory. */
internal fun File.isProjectCandidateDir(): Boolean = isDirectory && canRead() && !name.startsWith(".") && !isHidden

/** Scans the given root directory for valid Android project subdirectories. */
internal fun findValidProjects(projectsRoot: File): List<File> {
	if (!projectsRoot.isProjectCandidateDir()) return emptyList()

	val subdirs =
		projectsRoot
			.listFiles()
			?.filter { it.isProjectCandidateDir() }
			.orEmpty()
	if (subdirs.isEmpty()) return emptyList()

	return subdirs.filter { dir -> isValidProjectDirectory(dir) }
}

/**
 * Resolves [name] directly to `[projectsRoot]/[name]` and validates just that one directory --
 * the O(1) counterpart to [findValidProjects] for callers (e.g. deep links) that already know the
 * exact project name and don't need every project under [projectsRoot] scanned to find it.
 *
 * [name] is attacker-controllable (a deep-link URL segment), so it's resolved through
 * [resolveWithinDirectory] rather than a bare `File(projectsRoot, name)` -- [findValidProjects]
 * only ever matches against names of directories it already enumerated under [projectsRoot], so it
 * can't be pointed outside it, but a direct `File(root, name)` join can (e.g. `name = "../../etc"`).
 */
internal fun findValidProjectByName(
	projectsRoot: File,
	name: String,
): File? {
	// A project name is always a single path segment. resolveWithinDirectory's lexical check only
	// rejects ".."/a leading separator, so without this, name = "." would resolve to projectsRoot
	// itself (opening the whole projects directory as "a project" if it happens to satisfy
	// isValidProjectDirectory), and an embedded separator like "foo/bar" would resolve two levels
	// deep instead of naming a direct child.
	if (name.isEmpty() || name == "." || name.contains("/") || name.contains("\\")) {
		return null
	}
	if (!projectsRoot.isProjectCandidateDir()) return null

	// A deep-link name is typically authored/normalized as NFC by web tooling, but an on-disk
	// project directory imported from elsewhere (e.g. a git clone authored on macOS, which
	// decomposes accented filenames to NFD) may not codepoint-match it even though the two look
	// identical. Try both normal forms -- still O(1) filesystem lookups, not a directory scan --
	// rather than reporting a visually-identical project as "not found".
	val candidateNames = linkedSetOf(name, Normalizer.normalize(name, Normalizer.Form.NFC), Normalizer.normalize(name, Normalizer.Form.NFD))
	for (candidateName in candidateNames) {
		val candidate = resolveWithinDirectory(projectsRoot, candidateName) ?: continue
		if (candidate.isProjectCandidateDir() && isValidProjectDirectory(candidate)) {
			return candidate
		}
	}
	return null
}

/**
 * True if [a] and [b] name the same project, tolerating an NFC/NFD codepoint difference (e.g. an
 * accented project name authored as NFD on macOS vs. the NFC form a deep-link URL typically
 * carries) - the same normalization [findValidProjectByName] applies for its filesystem lookup,
 * but as a direct string comparison here rather than multiple candidate paths.
 */
internal fun projectNamesMatch(
	a: String,
	b: String,
): Boolean {
	if (a == b) return true
	return Normalizer.normalize(a, Normalizer.Form.NFC) == Normalizer.normalize(b, Normalizer.Form.NFC)
}

/**
 * Whether [openProjectPath] is the project a deep link naming [projectName] would resolve to.
 *
 * The name alone is not enough. A deep link can only ever resolve to `<projectsRoot>/<name>`, but a
 * project can be opened from anywhere -- the file picker (`BaseFragment`'s ACTION_OPEN_DOCUMENT_TREE
 * uses the projects dir as a starting hint, not a constraint), Recents, or a clone destination. With
 * `/storage/emulated/0/Download/work/MyApp` open and an unrelated `<projectsRoot>/MyApp` on disk, a
 * leaf-name comparison says "same project" and the link's file path is then resolved against the
 * OPEN one -- for two clones of a repo the path exists in both, so the wrong file opens silently.
 * So the parent has to match as well.
 *
 * Canonicalised, since either side can reach the same directory through a symlink; a failure to
 * canonicalise (an unreadable parent) falls back to the absolute path rather than throwing.
 */
internal fun isDeepLinkTargetOfOpenProject(
	openProjectPath: String,
	projectName: String,
	projectsRoot: File,
): Boolean {
	if (openProjectPath.isBlank()) return false
	val open = File(openProjectPath)
	if (!projectNamesMatch(open.name, projectName)) return false
	return canonicalOrAbsolute(open.parentFile ?: return false) == canonicalOrAbsolute(projectsRoot)
}

private fun canonicalOrAbsolute(file: File): String = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }

/** Determines if the directory contains a valid Android project structure. */
fun isValidProjectDirectory(selectedDir: File): Boolean {
	if (isPluginProject(selectedDir)) {
		return true
	}

	val appFolder = File(selectedDir, "app")
	val buildGradleFile = File(appFolder, "build.gradle")
	val buildGradleKtsFile = File(appFolder, "build.gradle.kts")
	return appFolder.exists() && appFolder.isDirectory &&
		(buildGradleFile.exists() || buildGradleKtsFile.exists())
}

/**
 * Determines if the selected directory is either:
 *  1. A valid Android project itself, OR
 *  2. A container that includes one or more valid Android projects.
 */
internal fun isValidProjectOrContainerDirectory(selectedDir: File): Boolean {
	if (!selectedDir.isProjectCandidateDir()) {
		return false
	}

	if (isValidProjectDirectory(selectedDir)) {
		return true
	}

	// Check if it contains valid Android projects as subdirectories
	val subDirs = selectedDir.listFiles()?.filter { it.isProjectCandidateDir() } ?: return false
	return subDirs.any { sub -> isValidProjectDirectory(sub) }
}

/** Checks if the directory contains a specific plugin project structure. */
internal fun isPluginProject(dir: File): Boolean {
	val pluginApiJar = File(dir, "libs/plugin-api.jar")
	val buildGradle = File(dir, "build.gradle.kts")
	return pluginApiJar.exists() && buildGradle.exists()
}
