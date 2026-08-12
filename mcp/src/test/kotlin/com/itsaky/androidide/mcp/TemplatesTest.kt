package com.itsaky.androidide.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Verbatim from `unzip -p files/home/.cg/templates/core.cgt templates.json` on a
// device: the spacing really is inconsistent between entries.
private val CORE_INDEX =
	"""
	{
	"templates": [
		{ "path": "NoActivity" },
		{ "path": "EmptyActivity" },
		{ "path": "BasicActivity" },
		{ "path": "NavigationDrawer" },
		{ "path": "BottomNavActivity"},
		{ "path": "NoAndroidX"},
		{ "path": "TabbedActivity"},
		{ "path": "ComposeActivity"},
		{ "path": "CodeOnTheGoPlugin"}
	]
	}
	""".trimIndent()

// Verbatim from EmptyActivity/template/template.json. The `{identifier: "APP_NAME"}`
// keys are unquoted, so this is not strict JSON and a real parser rejects it.
private val EMPTY_ACTIVITY_TEMPLATE =
	"""
	{
		"name": "Empty Activity",
		"description": "Creates a new empty activity",
		"version": "0.1",
		"tooltipTag": "template.empty.activity",
		"parameters": {
			"required": {
				"appName": {identifier: "APP_NAME"},
				"packageName":  {identifier: "PACKAGE_NAME"},
				"saveLocation":  {identifier: "SAVE_LOCATION"}
			},
			"optional": {
				"language": {identifier: "LANGUAGE"},
				"minsdk":  {identifier: "MIN_SDK"}
			}
		},
		"system": {
		"agpVersion": { "identifier": "AGP_VERSION" }
		}
	}
	""".trimIndent()

private val NO_ACTIVITY_TEMPLATE =
	"""
	{
		"name": "No Activity",
		"description": "Creates a no activity",
		"version": "0.1",
		"parameters": {
			"required": {
				"appName": {identifier: "APP_NAME"}
			}
		}
	}
	""".trimIndent()

private class FakeAdb(
	private val responder: (String) -> AdbResult,
) : Adb {
	val commands = mutableListOf<String>()

	override fun run(args: List<String>): AdbResult {
		val command = args.joinToString(" ")
		commands += command
		return responder(command)
	}
}

private fun ok(stdout: String) = AdbResult(exitCode = 0, stdout = stdout, stderr = "")

// Answers `ls` with [listing] and every `unzip -p` from [members], keyed
// "<archive>!<member>". An unknown member fails the way unzip really does.
private fun fakeDevice(
	listing: String,
	members: Map<String, String>,
) = FakeAdb { command ->
	if (!command.contains("unzip")) {
		ok(listing)
	} else {
		members
			.entries
			.firstOrNull { (key, _) ->
				val (archive, member) = key.split("!")
				command.contains(archive) && command.contains(member)
			}?.let { ok(it.value) }
			?: AdbResult(exitCode = 11, stdout = "", stderr = "caution: filename not matched")
	}
}

private fun textOf(result: CallToolResult): String =
	result.content
		.filterIsInstance<TextContent>()
		.single()
		.text

class TemplatesTest {
	@Test
	fun `parses every template path from an archive index`() {
		assertEquals(
			listOf(
				"NoActivity",
				"EmptyActivity",
				"BasicActivity",
				"NavigationDrawer",
				"BottomNavActivity",
				"NoAndroidX",
				"TabbedActivity",
				"ComposeActivity",
				"CodeOnTheGoPlugin",
			),
			parseTemplateIndex(CORE_INDEX),
		)
	}

	@Test
	fun `parses an index with no templates as empty rather than failing`() {
		assertEquals(emptyList(), parseTemplateIndex("""{ "templates": [] }"""))
	}

	// The regression that matters: template.json is not strict JSON, so a real JSON
	// parser throws on the unquoted `{identifier: "APP_NAME"}` key.
	@Test
	fun `extracts name and description from a template json with unquoted identifier keys`() {
		assertTrue(
			EMPTY_ACTIVITY_TEMPLATE.contains("""{identifier: "APP_NAME"}"""),
			"the fixture must exercise the unquoted key",
		)

		assertEquals("Empty Activity", parseTemplateName(EMPTY_ACTIVITY_TEMPLATE))
		assertEquals("Creates a new empty activity", parseTemplateDescription(EMPTY_ACTIVITY_TEMPLATE))
	}

	@Test
	fun `reports a missing name or description as null`() {
		assertNull(parseTemplateName("""{ "version": "0.1" }"""))
		assertNull(parseTemplateDescription("""{ "name": "Empty Activity" }"""))
	}

	@Test
	fun `lists one template per line as name then description`() {
		val adb =
			fakeDevice(
				listing = "core.cgt\n",
				members =
					mapOf(
						"core.cgt!templates.json" to """{ "templates": [ { "path": "NoActivity" }, { "path": "EmptyActivity" } ] }""",
						"core.cgt!NoActivity/template/template.json" to NO_ACTIVITY_TEMPLATE,
						"core.cgt!EmptyActivity/template/template.json" to EMPTY_ACTIVITY_TEMPLATE,
					),
			)

		val result = listTemplates(adb)

		assertEquals(false, result.isError ?: false)
		assertEquals(
			"""
			Code On The Go has 2 project templates installed:
			No Activity - Creates a no activity
			Empty Activity - Creates a new empty activity
			""".trimIndent(),
			textOf(result),
		)
	}

	// Plugins drop their own plugin_<id>_*.cgt beside core.cgt.
	@Test
	fun `reads templates from every archive in the directory`() {
		val adb =
			fakeDevice(
				listing = "core.cgt\nplugin_demo_extras.cgt\n",
				members =
					mapOf(
						"core.cgt!templates.json" to """{ "templates": [ { "path": "NoActivity" } ] }""",
						"core.cgt!NoActivity/template/template.json" to NO_ACTIVITY_TEMPLATE,
						"plugin_demo_extras.cgt!templates.json" to """{ "templates": [ { "path": "EmptyActivity" } ] }""",
						"plugin_demo_extras.cgt!EmptyActivity/template/template.json" to EMPTY_ACTIVITY_TEMPLATE,
					),
			)

		val result = listTemplates(adb)

		assertEquals(false, result.isError ?: false)
		assertTrue(textOf(result).contains("No Activity - Creates a no activity"), textOf(result))
		assertTrue(textOf(result).contains("Empty Activity - Creates a new empty activity"), textOf(result))
	}

	// An untrimmed archive name becomes `unzip -p .../core.cgt\r`, which matches
	// nothing on the device.
	@Test
	fun `tolerates the CRLF line endings adb shell emits`() {
		val adb =
			fakeDevice(
				listing = "core.cgt\r\n",
				members =
					mapOf(
						"core.cgt!templates.json" to "{ \"templates\": [ { \"path\": \"NoActivity\" } ] }\r\n",
						"core.cgt!NoActivity/template/template.json" to NO_ACTIVITY_TEMPLATE.replace("\n", "\r\n"),
					),
			)

		val result = listTemplates(adb)

		assertEquals(false, result.isError ?: false)
		assertEquals(
			"""
			Code On The Go has 1 project template installed:
			No Activity - Creates a no activity
			""".trimIndent(),
			textOf(result),
		)
		assertTrue(adb.commands.none { it.contains("\r") }, adb.commands.toString())
	}

	// Templates are unpacked during first-run onboarding, so an empty directory is
	// an answer about the app's state, not a failure to get one.
	@Test
	fun `reports a friendly non-error message when no archives are installed`() {
		val result = listTemplates(fakeDevice(listing = "", members = emptyMap()))

		assertEquals(false, result.isError ?: false)
		assertTrue(textOf(result).contains("No project templates are installed"), textOf(result))
		assertTrue(textOf(result).contains("onboarding"), textOf(result))
	}

	// `ls` on an absent directory exits 1, which would otherwise read as adb failing
	// to reach the device.
	@Test
	fun `guards the listing so an absent templates directory still exits zero`() {
		val adb = fakeDevice(listing = "", members = emptyMap())

		listTemplates(adb)

		assertTrue(adb.commands.single().contains("2>/dev/null || true"), adb.commands.toString())
	}

	@Test
	fun `ignores directory entries that are not template archives`() {
		val adb =
			fakeDevice(
				listing = "core.cgt\nREADME.txt\n",
				members =
					mapOf(
						"core.cgt!templates.json" to """{ "templates": [ { "path": "NoActivity" } ] }""",
						"core.cgt!NoActivity/template/template.json" to NO_ACTIVITY_TEMPLATE,
					),
			)

		val result = listTemplates(adb)

		assertEquals(false, result.isError ?: false)
		assertTrue(adb.commands.none { it.contains("README.txt") }, adb.commands.toString())
	}

	@Test
	fun `falls back to the archive path when a template declares no name`() {
		val adb =
			fakeDevice(
				listing = "core.cgt\n",
				members =
					mapOf(
						"core.cgt!templates.json" to """{ "templates": [ { "path": "MysteryActivity" } ] }""",
						"core.cgt!MysteryActivity/template/template.json" to """{ "version": "0.1" }""",
					),
			)

		val result = listTemplates(adb)

		assertEquals(false, result.isError ?: false)
		assertTrue(textOf(result).contains("MysteryActivity"), textOf(result))
	}

	@Test
	fun `reports an error when adb fails`() {
		val failing = Adb { AdbResult(exitCode = 1, stdout = "", stderr = "adb: no devices/emulators found") }

		val result = listTemplates(failing)

		assertEquals(true, result.isError)
		assertTrue(textOf(result).contains("no devices/emulators found"), textOf(result))
	}

	@Test
	fun `reports an error when an archive cannot be read`() {
		val adb = fakeDevice(listing = "core.cgt\n", members = emptyMap())

		val result = listTemplates(adb)

		assertEquals(true, result.isError)
		assertTrue(textOf(result).contains("filename not matched"), textOf(result))
	}
}
