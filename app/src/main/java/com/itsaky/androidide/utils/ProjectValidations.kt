package com.itsaky.androidide.utils

import java.io.File
import java.io.IOException
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
 * The outcome of [lookupValidProjectByName], which unlike a bare `File?` keeps "no project by that
 * name" apart from "whether one exists could not be determined".
 *
 * Callers act on the two very differently: a definitive absence is worth remembering (so a link
 * naming a project that does not exist stops re-reporting itself on every recreate), while an
 * unverifiable result says nothing at all about the project and must leave every such decision
 * untouched.
 */
internal sealed interface ProjectNameLookup {
	data class Found(
		val dir: File,
	) : ProjectNameLookup

	/** No project of that name exists under the projects root. */
	data object NotFound : ProjectNameLookup

	/**
	 * A filesystem failure other than absence (EACCES right after a storage-permission change, EIO
	 * on a flaky SD/FUSE mount) stopped the lookup from reaching an answer.
	 */
	data class Unverifiable(
		val cause: IOException,
	) : ProjectNameLookup
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
 *
 * Reports *why* it found nothing -- see [ProjectNameLookup]; [findValidProjectByName] is this
 * reduced to a nullable directory for callers that cannot act on the difference.
 */
internal fun lookupValidProjectByName(
	projectsRoot: File,
	name: String,
): ProjectNameLookup {
	// A project name is always a single path segment. resolveWithinDirectory's lexical check only
	// rejects ".."/a leading separator, so without this, name = "." would resolve to projectsRoot
	// itself (opening the whole projects directory as "a project" if it happens to satisfy
	// isValidProjectDirectory), and an embedded separator like "foo/bar" would resolve two levels
	// deep instead of naming a direct child.
	if (name.isEmpty() || name == "." || name.contains("/") || name.contains("\\")) {
		return ProjectNameLookup.NotFound
	}
	if (!projectsRoot.isProjectCandidateDir()) return ProjectNameLookup.NotFound

	// A deep-link name is typically authored/normalized as NFC by web tooling, but an on-disk
	// project directory imported from elsewhere (e.g. a git clone authored on macOS, which
	// decomposes accented filenames to NFD) may not codepoint-match it even though the two look
	// identical. Try both normal forms -- still O(1) filesystem lookups, not a directory scan --
	// rather than reporting a visually-identical project as "not found".
	val candidateNames = linkedSetOf(name, Normalizer.normalize(name, Normalizer.Form.NFC), Normalizer.normalize(name, Normalizer.Form.NFD))
	// Remembered rather than returned on the spot: a later candidate form may still resolve cleanly,
	// and a definite Found has to win over an earlier form's transient IO failure.
	var unverifiable: IOException? = null
	val resolver = ContainedPathResolver(projectsRoot)
	for (candidateName in candidateNames) {
		when (val resolution = resolver.resolve(candidateName)) {
			is ContainedPathResolver.Resolution.Contained -> {
				val candidate = resolution.file
				if (candidate.isProjectCandidateDir() && isValidProjectDirectory(candidate)) {
					return ProjectNameLookup.Found(candidate)
				}
			}

			is ContainedPathResolver.Resolution.Unverifiable -> {
				unverifiable = resolution.cause
			}

			// A traversal attempt is a definitive "not this project", not an unknown.
			is ContainedPathResolver.Resolution.Rejected -> {
				Unit
			}
		}
	}
	return unverifiable?.let(ProjectNameLookup::Unverifiable) ?: ProjectNameLookup.NotFound
}

/** [lookupValidProjectByName] reduced to the project directory, or null for any other outcome. */
internal fun findValidProjectByName(
	projectsRoot: File,
	name: String,
): File? = (lookupValidProjectByName(projectsRoot, name) as? ProjectNameLookup.Found)?.dir

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
	// Whatever can be settled without touching the disk, settle here.
	deepLinkTargetOfOpenProjectWithoutIo(openProjectPath, projectName, projectsRoot)?.let { return it }

	val open = File(openProjectPath)
	return canonicalOrAbsolute(open.parentFile ?: return false) == canonicalOrAbsolute(projectsRoot)
}

/**
 * The part of [isDeepLinkTargetOfOpenProject] decidable without any filesystem call, for callers
 * that must answer on a thread where I/O is not allowed -- `null` means "only canonicalisation can
 * tell", so the caller has to go off-thread for the rest.
 *
 * Lives here, beside the full rule, rather than being reimplemented at the call site: a writer with
 * its own private copy of "is this project linkable" would keep answering the old question if this
 * rule were ever tightened.
 */
internal fun deepLinkTargetOfOpenProjectWithoutIo(
	openProjectPath: String,
	projectName: String,
	projectsRoot: File,
): Boolean? {
	if (openProjectPath.isBlank()) return false
	val open = File(openProjectPath)
	if (!projectNamesMatch(open.name, projectName)) return false
	val parent = open.parentFile ?: return false

	// Equal path strings name the same directory, so canonicalising both sides could only agree.
	// This is the case for every project reached through the projects list or a deep link, since
	// both are resolved against projectsRoot to begin with.
	if (parent.absolutePath == projectsRoot.absolutePath) return true

	// They differ as text, so a symlink on either side may still make them one directory -- and only
	// canonicalPath can say, which is exactly the call this function exists to avoid.
	return null
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
