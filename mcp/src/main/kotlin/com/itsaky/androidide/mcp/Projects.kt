package com.itsaky.androidide.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

/**
 * Environment.PROJECTS_DIR. World-readable external storage, so listing it needs no
 * run-as.
 */
const val COGO_PROJECTS_DIR = "/storage/emulated/0/CodeOnTheGoProjects"

private const val NO_DIR_MARKER = "NO_PROJECTS_DIR"

// Names contain spaces, so a bare listing cannot be told apart from shell noise.
// The prefix makes each line unambiguous and lets anything else be discarded.
private const val PROJECT_MARKER = "PROJECT:"

internal sealed interface ProjectListing {
	/** The directory is absent, which is an answer rather than a failure. */
	data object NoProjectsDir : ProjectListing

	data class Found(
		val names: List<String>,
	) : ProjectListing
}

/**
 * One shell command rather than a listing followed by a probe per candidate: it is a
 * single round-trip, and it never moves a filename across the adb boundary, where a
 * name containing spaces would have to be re-quoted to survive.
 *
 * Validity mirrors ProjectValidations.kt: a directory holding app/build.gradle{,.kts},
 * or the plugin-project escape hatch of libs/plugin-api.jar plus a root build.gradle.kts.
 * Those checks use `-e`, not `-f`, because the app tests only File.exists() too.
 */
internal fun listProjectsCommand(dir: String = COGO_PROJECTS_DIR): String =
	buildString {
		append("if [ ! -d \"$dir\" ]; then echo $NO_DIR_MARKER; exit 0; fi; ")
		append("cd \"$dir\" || exit 0; ")
		// `*/` expands to directories only and skips dot entries, so stray .cgt
		// template files and .nomedia are excluded before any test runs. Glob results
		// are not word-split, which is what keeps names like "MyApp Basic J" intact.
		append("for p in */; do n=\${p%/}; ")
		append("if [ -e \"\$n/app/build.gradle\" ] || [ -e \"\$n/app/build.gradle.kts\" ]")
		append(" || { [ -e \"\$n/libs/plugin-api.jar\" ] && [ -e \"\$n/build.gradle.kts\" ]; }; ")
		append("then echo \"$PROJECT_MARKER\$n\"; fi; ")
		append("done")
	}

internal fun parseProjectListing(stdout: String): ProjectListing {
	// trimEnd only: adb shell terminates lines with CRLF, and trimming the front
	// would corrupt a name that legitimately starts with a space.
	val lines = stdout.lineSequence().map { it.trimEnd() }.toList()
	if (lines.any { it == NO_DIR_MARKER }) {
		return ProjectListing.NoProjectsDir
	}

	return ProjectListing.Found(
		lines.filter { it.startsWith(PROJECT_MARKER) }.map { it.removePrefix(PROJECT_MARKER) },
	)
}

internal fun describeProjects(listing: ProjectListing): String =
	when (listing) {
		ProjectListing.NoProjectsDir -> {
			"No projects directory on the device ($COGO_PROJECTS_DIR) - no projects have been created yet."
		}

		is ProjectListing.Found -> {
			if (listing.names.isEmpty()) {
				"No projects in $COGO_PROJECTS_DIR."
			} else {
				val noun = if (listing.names.size == 1) "project" else "projects"
				"${listing.names.size} $noun in $COGO_PROJECTS_DIR:\n" + listing.names.joinToString("\n")
			}
		}
	}

fun listProjects(adb: Adb): CallToolResult {
	val result = adb.shell(listProjectsCommand())
	if (result.exitCode != 0) {
		return adbFailure(result)
	}

	return CallToolResult(content = listOf(TextContent(describeProjects(parseProjectListing(result.stdout)))))
}
