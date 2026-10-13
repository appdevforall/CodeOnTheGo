package org.appdevforall.codeonthego.plugins.ai.prompt

import org.appdevforall.codeonthego.plugins.ai.prompt.PromptConfigFixture.load
import org.appdevforall.codeonthego.plugins.ai.prompt.PromptConfigFixture.loadWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PromptConfigObject], what a plugin's parser reads its config through: a mistake
 * in a prompt file is refused naming that file and the key, rather than reaching the model as a
 * prompt with a hole in it.
 */
class PromptConfigObjectTest {
	private val config = load()

	@Test
	fun givenTheFiles_whenParsing_thenEveryTextIsLabelledWithItsOwnFileAndPath() {
		assertEquals("agent.yml: identity", config.identity.label)
		assertEquals("rules.yml: rules[0].items[1]", config.rules[0].items[1].label)
		assertEquals("layout.yml: layout.system_prompt", config.systemPrompt.label)
		val argument =
			config.tools
				.getValue("read_file")
				.arguments
				.getValue("path")
		assertEquals("layout.yml: tools.read_file.arguments.path", argument.label)
	}

	@Test
	fun givenABlockScalar_whenParsing_thenItsTrailingNewlineIsKept() {
		// Whitespace can matter in a code block, so the text reaches the template as YAML parsed it.
		assertEquals("{{IDENTITY}}\n{{RULE}}\n", config.systemPrompt.template)
	}

	@Test
	fun givenAFoldedScalar_whenParsing_thenItsLinesAreJoinedIntoOneSentence() {
		// Source line wraps must not reach the model as newlines mid-sentence.
		assertEquals("You help with any question, not only Android questions.\n", config.identity.template)
	}

	@Test
	fun givenAnAbsentOptionalMapping_whenParsing_thenItIsEmpty() {
		assertEquals(emptyMap<String, PromptText>(), config.tools.getValue("list_files").arguments)
	}

	@Test
	fun givenAMissingNestedKey_whenParsing_thenItIsNamedWithItsFileAndPath() {
		assertRefused("rules.yml: rules[0].heading is missing") {
			loadWith("rules.yml") { it.replace("  - heading: IMPORTANT\n    items:", "  - items:") }
		}
	}

	@Test
	fun givenAMissingTopLevelKey_whenParsing_thenItIsReportedAgainstTheEntryFile() {
		// No file holds it, so the entry file, which decides what is read, is the one to fix.
		assertRefused("agent.yml: rules is missing") { loadWith("rules.yml") { "other: x\n" } }
	}

	@Test
	fun givenAMisspelledKey_whenParsing_thenTheRealKeyIsReportedMissing() {
		val error =
			refused {
				loadWith("rules.yml") { it.replace("    items:\n      - Reply", "    itmes:\n      - Reply") }
			}

		assertEquals("rules.yml: rules[0].items is missing", error.message)
	}

	@Test
	fun givenAnExtraNestedKey_whenParsing_thenItIsRefusedAsUnknown() {
		assertRefused("layout.yml: layout: unknown key tone; expected system_prompt") {
			loadWith("layout.yml") { it.replace("layout:\n", "layout:\n  tone: friendly\n") }
		}
	}

	@Test
	fun givenAnExtraTopLevelKey_whenParsing_thenTheFileHoldingItIsNamed() {
		val error = refused { loadWith("layout.yml") { "$it\ntone: friendly\n" } }

		assertTrue(error.message!!, error.message!!.startsWith("layout.yml: unknown key tone; expected "))
	}

	@Test
	fun givenAnUnquotedNumber_whenParsing_thenItIsRefusedAsNotText() {
		assertRefused("rules.yml: rules[1].heading expected text; quote it") {
			loadWith("rules.yml") { it.replace("heading: STYLE", "heading: 42") }
		}
	}

	@Test
	fun givenABlankText_whenParsing_thenItIsRefused() {
		assertRefused("rules.yml: rules[1].items[0] is blank") {
			loadWith("rules.yml") { it.replace("- Use plain words.", "- \"  \"") }
		}
	}

	@Test
	fun givenAnEmptyList_whenParsing_thenItIsRefused() {
		assertRefused("rules.yml: rules[1].items is empty") {
			loadWith("rules.yml") { it.replace(Regex("(?s)(- heading: STYLE\n    items:).*"), "$1 []\n") }
		}
	}

	@Test
	fun givenAListItemThatIsNotAMapping_whenParsing_thenItIsRefused() {
		assertRefused("rules.yml: rules[0] expected a mapping") {
			loadWith("rules.yml") { "rules:\n  - just text\n" }
		}
	}

	@Test
	fun givenAnEmptyEntryMapping_whenParsing_thenItIsRefused() {
		assertRefused("layout.yml: tools is empty") {
			loadWith("layout.yml") { it.replace(Regex("(?s)tools:.*"), "tools: {}\n") }
		}
	}

	@Test
	fun givenAWholeNumberAsText_whenParsingAnInt_thenItIsRefused() {
		assertRefused("agent.yml: schema_version expected a whole number") {
			loadWith("agent.yml") { it.replace("schema_version: 1", "schema_version: \"1\"") }
		}
	}

	@Test
	fun givenAParserRefusal_whenParsing_thenItNamesTheFileAndKey() {
		assertRefused("agent.yml: schema_version is 2, but this plugin reads 1") {
			loadWith("agent.yml") { it.replace("schema_version: 1", "schema_version: 2") }
		}
	}

	@Test
	fun givenAKey_whenLabelled_thenItNamesTheFileDefiningIt() {
		val files = listOf("agent.yml" to mapOf("a" to "x"), "tools.yml" to mapOf("heading" to "H"))
		val document = PromptConfigDocument.merge("agent.yml", files)

		val label =
			document.read {
				labelOf("heading").also {
					text("heading")
					text("a")
				}
			}

		assertEquals("tools.yml: heading", label)
	}

	@Test
	fun givenLoadedText_whenRendered_thenTheEngineExpandsIt() {
		val values =
			mapOf(
				"IDENTITY" to config.identity,
				"RULE" to config.rules[0].items[1],
				"TOOL" to "read_file",
			)

		val out = PromptTemplateEngine.render(config.systemPrompt, values)

		// Each block keeps its final newline, so the identity's own newline and the layout's both land.
		val expected = "You help with any question, not only Android questions.\n\nCall read_file to read a file.\n"
		assertEquals(expected, out)
	}
}
