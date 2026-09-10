package com.itsaky.androidide.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val HOME_DUMPSYS =
	"    topResumedActivity=ActivityRecord{127760145 u0 com.itsaky.androidide/.activities.MainActivity t84}"

private const val EDITOR_DUMPSYS =
	"    topResumedActivity=ActivityRecord{127760145 u0 com.itsaky.androidide/.activities.editor.EditorActivityKt t84}"

private class RecordingAdb(
	private val responder: (List<String>) -> AdbResult,
) : Adb {
	val calls = mutableListOf<List<String>>()

	override fun run(args: List<String>): AdbResult {
		calls += args
		return responder(args)
	}
}

private fun adbLandingOn(dumpsys: String) =
	RecordingAdb { args ->
		if (args.any { it.contains("dumpsys") }) {
			AdbResult(exitCode = 0, stdout = dumpsys, stderr = "")
		} else {
			AdbResult(exitCode = 0, stdout = "", stderr = "")
		}
	}

class CogoHomeTest {
	private fun goHome(adb: Adb): CallToolResult =
		withConnectedClient({ cogoMcpServer(adb = adb, homePollDelayMillis = 0) }) { client ->
			client.callTool(name = "cogo_home", arguments = emptyMap())
		}

	private fun textOf(result: CallToolResult): String =
		result.content
			.filterIsInstance<TextContent>()
			.single()
			.text

	@Test
	fun `reports success when the app lands on MainActivity`() {
		val result = goHome(adbLandingOn(HOME_DUMPSYS))

		assertEquals(false, result.isError ?: false)
		assertEquals("Code On The Go is on its home screen (MainActivity).", textOf(result))
	}

	// Auto-open-project sends the app straight to the editor. If that still
	// happens, saying "done" would be a lie the agent cannot detect.
	@Test
	fun `reports an error when the app lands somewhere other than home`() {
		val result = goHome(adbLandingOn(EDITOR_DUMPSYS))

		assertEquals(true, result.isError)
		assertTrue(textOf(result).contains("EditorActivityKt"), textOf(result))
		assertTrue(textOf(result).contains("not the home screen"), textOf(result))
	}

	@Test
	fun `reports an error when adb fails`() {
		val failing = Adb { AdbResult(exitCode = 1, stdout = "", stderr = "adb: no devices/emulators found") }

		val result = goHome(failing)

		assertEquals(true, result.isError)
		assertTrue(textOf(result).contains("no devices/emulators found"), textOf(result))
	}

	@Test
	fun `force-stops, reads prefs, writes prefs, then launches MainActivity`() {
		val adb = adbLandingOn(HOME_DUMPSYS)

		goHome(adb)

		assertEquals(
			listOf("shell", "am", "force-stop", "com.itsaky.androidide"),
			adb.calls[0],
			"the app must be stopped before its preferences are rewritten, or it would overwrite them on exit",
		)
		assertTrue(adb.calls[1].joinToString(" ").contains("cat shared_prefs"), "${adb.calls[1]}")
		assertTrue(adb.calls[2].joinToString(" ").contains("base64 -d"), "${adb.calls[2]}")
		assertEquals(
			listOf("shell", "am", "start", "-n", "com.itsaky.androidide/.activities.MainActivity"),
			adb.calls[3],
		)
		assertTrue(adb.calls[4].any { it.contains("dumpsys") }, "${adb.calls[4]}")
	}

	// tryOpenLastProject() falls back to the most recently modified project when
	// no last project is recorded, so clearing ide_last_project is not enough --
	// only the boolean actually prevents the jump to the editor.
	@Test
	fun `writes back preferences with autoOpenProjects disabled`() {
		val adb = adbLandingOn(HOME_DUMPSYS)

		goHome(adb)

		val written = decodeWrittenXml(adb.calls[2].joinToString(" "))
		assertTrue(
			written.contains("""<boolean name="idepref_general_autoOpenProjects" value="false" />"""),
			written,
		)
		assertTrue(adb.calls[2].joinToString(" ").contains("com.itsaky.androidide_preferences.xml"))
	}

	// A missing prefs file must not look like an adb failure: the read is
	// deliberately written so it exits 0 with empty output when absent.
	@Test
	fun `treats an absent preferences file as empty rather than an error`() {
		val adb = adbLandingOn(HOME_DUMPSYS)

		goHome(adb)

		assertTrue(adb.calls[1].joinToString(" ").contains("2>/dev/null"), "${adb.calls[1]}")
		val written = decodeWrittenXml(adb.calls[2].joinToString(" "))
		assertTrue(written.contains("<map>"), written)
	}

	private fun decodeWrittenXml(command: String): String {
		val encoded = command.substringAfter("echo ").substringBefore(" |").trim()
		return String(Base64.getDecoder().decode(encoded))
	}
}
