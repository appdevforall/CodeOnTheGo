package com.itsaky.androidide.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PROJECT_PATH = "/storage/emulated/0/CodeOnTheGoProjects/MyApp Basic K"

private class ScriptedAdb(
	private val responder: (List<String>) -> AdbResult,
) : Adb {
	val calls = mutableListOf<List<String>>()

	override fun run(args: List<String>): AdbResult {
		calls += args
		return responder(args)
	}
}

private fun prefsRecording(value: String) =
	"""
	<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
	<map>
		<boolean name="privacy.disclosure.shown" value="true" />
		<string name="ide_last_project">$value</string>
	</map>
	""".trimIndent()

// adb shell emits CRLF, so every fake listing does too.
private fun findOutput(vararg relativePaths: String) = relativePaths.joinToString("\r\n") { "$PROJECT_PATH/$it" } + "\r\n"

private fun adbServing(
	prefs: String,
	listing: String,
) = ScriptedAdb { args ->
	if (args.joinToString(" ").contains("cat shared_prefs")) {
		AdbResult(exitCode = 0, stdout = prefs, stderr = "")
	} else {
		AdbResult(exitCode = 0, stdout = listing, stderr = "")
	}
}

class ProjectFilesTest {
	private fun textOf(result: CallToolResult): String =
		result.content
			.filterIsInstance<TextContent>()
			.single()
			.text

	@Test
	fun `finds the project path recorded in the preferences document`() {
		val path = parseLastOpenedProject(prefsRecording("/storage/emulated/0/CodeOnTheGoProjects/GetxDemo"))

		assertEquals("/storage/emulated/0/CodeOnTheGoProjects/GetxDemo", path)
	}

	// Real project names contain spaces ("MyApp Basic K"), so anything that splits
	// the value on whitespace silently reads the wrong directory.
	@Test
	fun `keeps a project path that contains spaces intact`() {
		val path = parseLastOpenedProject(prefsRecording(PROJECT_PATH))

		assertEquals(PROJECT_PATH, path)
	}

	@Test
	fun `decodes xml escapes in the project path`() {
		val path = parseLastOpenedProject(prefsRecording("/storage/emulated/0/CodeOnTheGoProjects/Ben &amp; Jerry"))

		assertEquals("/storage/emulated/0/CodeOnTheGoProjects/Ben & Jerry", path)
	}

	@Test
	fun `reads the sentinel value as nothing open`() {
		assertNull(parseLastOpenedProject(prefsRecording("<NO_OPENED_PROJECT>")))
	}

	// SharedPreferences escapes the angle brackets on the way out, so the sentinel
	// arrives as &lt;NO_OPENED_PROJECT&gt; and would otherwise read as a real path.
	@Test
	fun `reads the escaped sentinel value as nothing open`() {
		assertNull(parseLastOpenedProject(prefsRecording("&lt;NO_OPENED_PROJECT&gt;")))
	}

	@Test
	fun `reads an absent preferences file as nothing open`() {
		assertNull(parseLastOpenedProject(""))
	}

	@Test
	fun `reads a preferences document without the key as nothing open`() {
		val xml =
			"""
			<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
			<map>
				<boolean name="idepref_general_autoOpenProjects" value="false" />
			</map>
			""".trimIndent()

		assertNull(parseLastOpenedProject(xml))
	}

	// "Nothing is open" is an answer, not a failure. An agent that cannot tell the
	// two apart would report a broken device when the IDE is merely at home.
	@Test
	fun `says no project is open without reporting an error`() {
		val adb = adbServing(prefsRecording("<NO_OPENED_PROJECT>"), "")

		val result = listProjectFiles(adb)

		assertEquals(false, result.isError ?: false)
		assertTrue(textOf(result).contains("No project is currently open"), textOf(result))
		assertEquals(1, adb.calls.size, "nothing to list, so no second adb call: ${adb.calls}")
	}

	@Test
	fun `says no project is open when the preferences file is missing`() {
		val result = listProjectFiles(adbServing("", ""))

		assertEquals(false, result.isError ?: false)
		assertTrue(textOf(result).contains("No project is currently open"), textOf(result))
	}

	@Test
	fun `lists files relative to the project root`() {
		val listing = findOutput("settings.gradle.kts", "app/src/main/AndroidManifest.xml")

		val result = listProjectFiles(adbServing(prefsRecording(PROJECT_PATH), listing))
		val text = textOf(result)

		assertEquals(false, result.isError ?: false)
		assertTrue(text.contains("MyApp Basic K"), text)
		assertTrue(text.lines().contains("settings.gradle.kts"), text)
		assertTrue(text.lines().contains("app/src/main/AndroidManifest.xml"), text)
		assertFalse(text.contains("$PROJECT_PATH/app"), "paths must be relative, not absolute: $text")
	}

	// The whole command travels as one string through adb and the device shell, so
	// an unquoted path turns "MyApp Basic K" into three arguments.
	@Test
	fun `quotes a project path containing spaces in the listing command`() {
		val adb = adbServing(prefsRecording(PROJECT_PATH), findOutput("settings.gradle.kts"))

		listProjectFiles(adb)

		val command = adb.calls[1].joinToString(" ")
		assertTrue(command.contains("'$PROJECT_PATH'"), command)
	}

	@Test
	fun `leaves out build, gradle and git entries`() {
		val listing =
			findOutput(
				"build/generated/Noise.kt",
				"app/build/intermediates/noise.jar",
				".gradle/file-system.probe",
				".git/HEAD",
				"app/build.gradle.kts",
				"buildSrc/Deps.kt",
			)

		val text = textOf(listProjectFiles(adbServing(prefsRecording(PROJECT_PATH), listing)))

		assertFalse(text.contains("Noise.kt"), text)
		assertFalse(text.contains("intermediates"), text)
		assertFalse(text.contains("file-system.probe"), text)
		assertFalse(text.contains("HEAD"), text)
		assertTrue(text.lines().contains("app/build.gradle.kts"), "a file merely named build.gradle.kts must survive: $text")
		assertTrue(text.lines().contains("buildSrc/Deps.kt"), "buildSrc is not a build directory: $text")
	}

	// Silent truncation reads as "that is the whole project", which is a lie the
	// agent cannot detect.
	@Test
	fun `announces that a long listing is truncated`() {
		val paths = (1..MAX_LISTED_FILES + 25).map { "src/File$it.kt" }.toTypedArray()

		val text = textOf(listProjectFiles(adbServing(prefsRecording(PROJECT_PATH), findOutput(*paths))))

		assertTrue(text.contains("TRUNCATED"), text)
		assertTrue(text.contains("${MAX_LISTED_FILES + 25}"), text)
		assertEquals(MAX_LISTED_FILES, text.lines().count { it.startsWith("src/File") }, text)
	}

	@Test
	fun `reports an error when the preferences read fails`() {
		val failing = Adb { AdbResult(exitCode = 1, stdout = "", stderr = "adb: no devices/emulators found") }

		val result = listProjectFiles(failing)

		assertEquals(true, result.isError)
		assertTrue(textOf(result).contains("no devices/emulators found"), textOf(result))
	}

	@Test
	fun `reports an error when the file listing fails`() {
		val adb =
			ScriptedAdb { args ->
				if (args.joinToString(" ").contains("cat shared_prefs")) {
					AdbResult(exitCode = 0, stdout = prefsRecording(PROJECT_PATH), stderr = "")
				} else {
					AdbResult(exitCode = 1, stdout = "", stderr = "find: '$PROJECT_PATH': No such file or directory")
				}
			}

		val result = listProjectFiles(adb)

		assertEquals(true, result.isError)
		assertTrue(textOf(result).contains("No such file or directory"), textOf(result))
	}
}
