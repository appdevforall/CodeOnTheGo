package com.itsaky.androidide.plugins.ai.prompt

import com.itsaky.androidide.plugins.ai.prompt.PromptConfigFixture.files
import com.itsaky.androidide.plugins.ai.prompt.PromptConfigFixture.load
import com.itsaky.androidide.plugins.ai.prompt.PromptConfigFixture.loadWith
import com.itsaky.androidide.plugins.ai.prompt.PromptConfigFixture.parser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for [PromptConfigLoader] and [PromptConfigDocument]: `agent.yml` and its `include`
 * list become one config, and a mistake in any file is refused naming that file.
 */
class PromptConfigLoaderTest {
	@Test
	fun givenAnEntryFileWithIncludes_whenLoading_thenItAndEveryIncludeAreReadInOrder() {
		val paths = mutableListOf<String>()
		val source =
			PromptConfigSource { path ->
				paths += path
				files.getValue(path)
			}

		runBlocking { PromptConfigLoader.load(source, parser) }

		assertEquals(listOf("agent.yml", "rules.yml", "layout.yml"), paths)
	}

	@Test
	fun givenNoInclude_whenLoading_thenOneFileMayHoldTheWholeConfig() {
		val single =
			files.getValue("agent.yml").substringBefore("include:") +
				(files - "agent.yml").values.joinToString("\n")

		val config = load(mapOf("agent.yml" to single))

		assertEquals("agent.yml: rules[0].items[0]", config.rules[0].items[0].label)
	}

	@Test
	fun givenAKeyInTwoFiles_whenLoading_thenBothFilesAreNamed() {
		// Neither copy may win silently: an edit to the losing one would change nothing.
		assertRefused("layout.yml: identity is also defined in agent.yml; a key belongs to one file") {
			loadWith("layout.yml") { "$it\nidentity: Twice.\n" }
		}
	}

	@Test
	fun givenAMissingInclude_whenLoading_thenTheMissingFileIsNamed() {
		assertRefused("agent.yml: include names rules.yml, which does not exist") { load(files - "rules.yml") }
	}

	@Test
	fun givenNoEntryFile_whenLoading_thenItIsNamed() {
		assertRefused("agent.yml does not exist") { load(files - "agent.yml") }
	}

	@Test
	fun givenAnIncludeListedTwice_whenLoading_thenItIsRefused() {
		assertRefused("agent.yml: include lists rules.yml twice") {
			loadWith("agent.yml") { it.replace("  - rules.yml\n", "  - rules.yml\n  - rules.yml\n") }
		}
	}

	@Test
	fun givenAnIncludeOutsideTheRoot_whenLoading_thenItIsRefused() {
		val escapes = listOf("../rules.yml", "/rules.yml", "sub/../../rules.yml", "..\\rules.yml", "C:\\rules.yml", "file:rules.yml")
		for (escape in escapes) {
			assertRefused("agent.yml: include[0] expected a .yml file under prompts/") {
				loadWith("agent.yml") { it.replace("  - rules.yml\n", "  - $escape\n") }
			}
		}
	}

	@Test
	fun givenAFileThatCannotBeRead_whenLoading_thenTheRefusalNamesIt() {
		val source =
			PromptConfigSource { path ->
				if (path == "rules.yml") throw IOException("Permission denied")
				files.getValue(path)
			}

		assertRefused("rules.yml: could not be read: Permission denied") {
			runBlocking { PromptConfigLoader.load(source, parser) }
		}
	}

	@Test
	fun givenAnIncludeThatIsNotYaml_whenLoading_thenItIsRefused() {
		assertRefused("agent.yml: include[0] expected a .yml file under prompts/") {
			loadWith("agent.yml") { it.replace("  - rules.yml\n", "  - rules.txt\n") }
		}
	}

	@Test
	fun givenAnIncludeNamingTheEntryFile_whenLoading_thenItIsRefused() {
		assertRefused("agent.yml: include[0] names agent.yml itself") {
			loadWith("agent.yml") { it.replace("  - rules.yml\n", "  - agent.yml\n") }
		}
	}

	@Test
	fun givenAnIncludeThatIsNotAList_whenLoading_thenItIsRefused() {
		assertRefused("agent.yml: include expected a list of file names") {
			loadWith("agent.yml") { it.replace(Regex("(?s)include:.*"), "include: rules.yml\n") }
		}
	}

	@Test
	fun givenAnIncludedFileWithItsOwnInclude_whenLoading_thenItIsRefused() {
		// One level only, so the whole prompt is always listed in agent.yml.
		assertRefused("rules.yml: include is only read in agent.yml") {
			loadWith("rules.yml") { "$it\ninclude:\n  - more.yml\n" }
		}
	}

	@Test
	fun givenAnEmptyIncludedFile_whenLoading_thenItIsRefused() {
		assertRefused("layout.yml: expected a mapping at the top") { loadWith("layout.yml") { "# nothing yet\n" } }
	}

	@Test
	fun givenEditorArtifacts_whenLoading_thenABomAndCrlfLineEndsAreAccepted() {
		val config = loadWith("layout.yml") { "\uFEFF" + it.replace("\n", "\r\n") }

		assertFalse(config.systemPrompt.template.contains('\r'))
		assertEquals("layout.yml: layout.system_prompt", config.systemPrompt.label)
	}

	@Test
	fun givenABomOnTheEntryFile_whenLoading_thenItsFirstKeyIsStillRead() {
		// A BOM glued to `schema_version` would make the parser report that key missing.
		val config = loadWith("agent.yml") { "\uFEFF$it" }

		assertEquals("agent.yml: identity", config.identity.label)
	}

	@Test
	fun givenBrokenYaml_whenLoading_thenTheFileNameAndPositionAreReported() {
		val error = refused { loadWith("rules.yml") { "rules: [unclosed" } }

		assertTrue(error.message!!.startsWith("rules.yml: "))
		assertTrue(error.message!!.contains("line"))
	}

	@Test
	fun givenADuplicateKeyInOneFile_whenLoading_thenItIsRefused() {
		// YAML would otherwise keep the second silently, and an edit to the first would do nothing.
		val error = refused { loadWith("agent.yml") { "$it\nidentity: again\n" } }

		assertTrue(error.message!!.startsWith("agent.yml: "))
	}

	@Test
	fun givenTheParser_whenLoading_thenItReceivesTheMergedDocumentWithoutInclude() {
		val document = runBlocking { PromptConfigLoader.load(PromptConfigFixture.sourceOf(files), { it }) }

		assertEquals(listOf("schema_version", "identity", "rules", "layout", "tools"), document.values.keys.toList())
		assertEquals("rules.yml", document.fileOf("rules"))
		assertEquals("agent.yml", document.fileOf("absent"))
	}
}
