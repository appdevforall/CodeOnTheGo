package com.itsaky.androidide.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectsTest {
	private fun <T> withProjectsRoot(block: (File) -> T): T {
		val root = Files.createTempDirectory("cogo-projects").toFile()
		return try {
			block(root)
		} finally {
			root.deleteRecursively()
		}
	}

	// The filter lives in the device shell, so the only honest way to test it is to
	// run the very command we ship against a real directory tree. The command is
	// POSIX sh, which both the host and Android's shell speak.
	private fun listingOf(dir: String): ProjectListing {
		val result = SystemAdb(executable = "/bin/sh").run(listOf("-c", listProjectsCommand(dir)))

		assertEquals(0, result.exitCode, result.stderr)
		return parseProjectListing(result.stdout)
	}

	private fun namesIn(dir: String): List<String> = (listingOf(dir) as ProjectListing.Found).names

	private fun androidProject(
		root: File,
		name: String,
		buildFile: String = "build.gradle.kts",
	) {
		File(root, "$name/app").mkdirs()
		File(root, "$name/app/$buildFile").writeText("")
	}

	private fun pluginProject(
		root: File,
		name: String,
	) {
		File(root, "$name/libs").mkdirs()
		File(root, "$name/libs/plugin-api.jar").writeText("")
		File(root, "$name/build.gradle.kts").writeText("")
	}

	private fun textOf(result: CallToolResult): String =
		result.content
			.filterIsInstance<TextContent>()
			.single()
			.text

	// Mirrors the real device: the projects directory holds stray .cgt template
	// files and a .nomedia alongside the actual project directories.
	@Test
	fun `lists project directories and ignores stray files`() {
		withProjectsRoot { root ->
			androidProject(root, "MyApp Basic K")
			File(root, "Alpha_Template.cgt").writeText("")
			File(root, ".nomedia").writeText("")

			assertEquals(listOf("MyApp Basic K"), namesIn(root.absolutePath))
		}
	}

	// The regression that matters most: real project names contain spaces, and a
	// command that word-splits paths silently reports nothing.
	@Test
	fun `handles project names containing spaces`() {
		withProjectsRoot { root ->
			androidProject(root, "My Application-cgt")
			androidProject(root, "MyApp Basic J")

			assertEquals(listOf("My Application-cgt", "MyApp Basic J"), namesIn(root.absolutePath))
		}
	}

	// GetxDemo and ProviderDemo on the device are Flutter projects: directories with
	// no app/ at all, which Code On The Go does not offer to open.
	@Test
	fun `excludes a directory without an app build script`() {
		withProjectsRoot { root ->
			File(root, "GetxDemo/lib").mkdirs()
			File(root, "GetxDemo/pubspec.yaml").writeText("")
			androidProject(root, "RealApp")

			assertEquals(listOf("RealApp"), namesIn(root.absolutePath))
		}
	}

	@Test
	fun `accepts a groovy app build script`() {
		withProjectsRoot { root ->
			androidProject(root, "GroovyApp", buildFile = "build.gradle")

			assertEquals(listOf("GroovyApp"), namesIn(root.absolutePath))
		}
	}

	@Test
	fun `accepts a plugin project`() {
		withProjectsRoot { root ->
			pluginProject(root, "MyPlugin")

			assertEquals(listOf("MyPlugin"), namesIn(root.absolutePath))
		}
	}

	// A plugin-api jar alone is not the escape hatch; the root build script is
	// required too.
	@Test
	fun `rejects a plugin project missing its root build script`() {
		withProjectsRoot { root ->
			File(root, "HalfPlugin/libs").mkdirs()
			File(root, "HalfPlugin/libs/plugin-api.jar").writeText("")

			assertEquals(emptyList(), namesIn(root.absolutePath))
		}
	}

	@Test
	fun `skips hidden directories`() {
		withProjectsRoot { root ->
			androidProject(root, ".hidden")

			assertEquals(emptyList(), namesIn(root.absolutePath))
		}
	}

	@Test
	fun `reports an empty projects directory as found but empty`() {
		withProjectsRoot { root ->
			assertEquals(emptyList(), namesIn(root.absolutePath))
		}
	}

	@Test
	fun `reports a missing projects directory distinctly`() {
		withProjectsRoot { root ->
			assertEquals(ProjectListing.NoProjectsDir, listingOf(File(root, "absent").absolutePath))
		}
	}

	@Test
	fun `tolerates the CRLF line endings adb shell emits`() {
		val listing = parseProjectListing("PROJECT:MyApp Basic J\r\nPROJECT:MyApp Basic K\r\n")

		assertEquals(listOf("MyApp Basic J", "MyApp Basic K"), (listing as ProjectListing.Found).names)
	}

	@Test
	fun `ignores unrelated shell output`() {
		val listing = parseProjectListing("WARNING: linker something\nPROJECT:Solo\n")

		assertEquals(listOf("Solo"), (listing as ProjectListing.Found).names)
	}

	@Test
	fun `describes projects with a count and one name per line`() {
		val adb = Adb { AdbResult(0, "PROJECT:MyApp Basic J\nPROJECT:MyApp Basic K\n", "") }

		val result = listProjects(adb)

		assertEquals(false, result.isError ?: false)
		assertEquals(
			"2 projects in $COGO_PROJECTS_DIR:\nMyApp Basic J\nMyApp Basic K",
			textOf(result),
		)
	}

	@Test
	fun `uses the singular for a lone project`() {
		val adb = Adb { AdbResult(0, "PROJECT:Solo\n", "") }

		assertEquals("1 project in $COGO_PROJECTS_DIR:\nSolo", textOf(listProjects(adb)))
	}

	@Test
	fun `reports an empty projects directory without an error`() {
		val adb = Adb { AdbResult(0, "", "") }

		val result = listProjects(adb)

		assertEquals(false, result.isError ?: false)
		assertEquals("No projects in $COGO_PROJECTS_DIR.", textOf(result))
	}

	// A missing directory is an answer, not a failure: the app simply has not
	// created it yet.
	@Test
	fun `reports a missing projects directory without an error`() {
		val adb = Adb { AdbResult(0, "NO_PROJECTS_DIR\n", "") }

		val result = listProjects(adb)

		assertEquals(false, result.isError ?: false)
		assertTrue(textOf(result).contains("No projects directory"), textOf(result))
		assertFalse(textOf(result).contains("adb failed"), textOf(result))
	}

	@Test
	fun `reports an error when adb fails instead of an empty list`() {
		val adb = Adb { AdbResult(exitCode = 1, stdout = "", stderr = "adb: device 'x' not found\n") }

		val result = listProjects(adb)

		assertEquals(true, result.isError)
		assertTrue(textOf(result).contains("device 'x' not found"), textOf(result))
	}

	// The whole listing is one `adb shell <command>`, so a slow device is paid for
	// once rather than once per candidate directory.
	@Test
	fun `asks adb for a device shell exactly once`() {
		val calls = mutableListOf<List<String>>()
		listProjects { args ->
			calls += args
			AdbResult(0, "", "")
		}

		assertEquals(1, calls.size)
		assertEquals(listOf("shell", listProjectsCommand()), calls.single())
	}
}
