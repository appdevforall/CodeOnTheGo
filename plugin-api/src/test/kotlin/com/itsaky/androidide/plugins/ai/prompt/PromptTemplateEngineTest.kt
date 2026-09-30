package com.itsaky.androidide.plugins.ai.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Unit tests for [PromptTemplateEngine], which renders every AI plugin's prompt layouts in one pass. */
class PromptTemplateEngineTest {
	@Test
	fun givenPlaceholdersWithValues_whenRendering_thenEachIsReplaced() {
		val out = PromptTemplateEngine.render("{{A}} and {{B}}, {{A}}", mapOf("A" to "x", "B" to "y"))

		assertEquals("x and y, x", out)
	}

	@Test
	fun givenAStringThatLooksLikeATag_whenRendering_thenItIsNotExpandedAgain() {
		// A contributed tool's description is third-party text and must reach the model as written.
		val out = PromptTemplateEngine.render("{{A}}", mapOf("A" to "{{B}} {{#B}}", "B" to "no"))

		assertEquals("{{B}} {{#B}}", out)
	}

	@Test
	fun givenPromptText_whenRendering_thenItIsRenderedWithTheValuesInScope() {
		val values = mapOf("A" to PromptText("use {{TOOL}}", "a"), "TOOL" to "respond")

		assertEquals("use respond", PromptTemplateEngine.render("{{A}}", values))
	}

	@Test
	fun givenPromptTextInsideAList_whenRendering_thenItSeesTheItemsKeys() {
		// How a module line in ide_context.yml names the module it is repeated for.
		val values =
			mapOf(
				"LINE" to PromptText("module {{NAME}}", "line"),
				"MODULES" to listOf(mapOf("NAME" to "app"), mapOf("NAME" to "lib")),
			)

		val out = PromptTemplateEngine.render("{{#MODULES}}[{{LINE}}]{{/MODULES}}", values)

		assertEquals("[module app][module lib]", out)
	}

	@Test
	fun givenATypoInPromptText_whenRendering_thenTheFailureNamesWhereTheTextCameFrom() {
		val values =
			mapOf(
				"OUTER" to PromptText("{{INNER}}", "layout.yml: layout.system_prompt"),
				"INNER" to PromptText("{{TERMINAL_TOLL}}", "rules.yml: rules[0].items[1]"),
			)

		val error =
			assertThrows(IllegalArgumentException::class.java) {
				PromptTemplateEngine.render(PromptText("{{OUTER}}", "root"), values)
			}

		assertEquals("rules.yml: rules[0].items[1]: unknown name {{TERMINAL_TOLL}}", error.message)
	}

	@Test
	fun givenPromptTextThatNamesItself_whenRendering_thenItFailsInsteadOfLooping() {
		val values = mapOf("LOOP" to PromptText("{{LOOP}}", "loop"))

		assertThrows(IllegalArgumentException::class.java) {
			PromptTemplateEngine.render("{{LOOP}}", values)
		}
	}

	@Test
	fun givenRegexReplacementCharacters_whenRendering_thenTheyAreInsertedLiterally() {
		val out = PromptTemplateEngine.render("{{A}}", mapOf("A" to """$1 \n"""))

		assertEquals("""$1 \n""", out)
	}

	@Test
	fun givenAnUnknownName_whenRendering_thenItFailsNamingIt() {
		val error =
			assertThrows(IllegalArgumentException::class.java) {
				PromptTemplateEngine.render("{{A}} {{MISSING}}", mapOf("A" to "x"))
			}

		assertEquals("unknown name {{MISSING}}", error.message)
	}

	@Test
	fun givenCamelCaseAndLowerCaseNames_whenRendering_thenTheyRenderLikeUpperCaseOnes() {
		val values =
			mapOf(
				"fileName" to "a.kt",
				"items" to listOf(mapOf("name" to "x"), mapOf("name" to "y")),
				"none" to false,
			)

		val out = PromptTemplateEngine.render("{{fileName}}:{{#items}} {{name}}{{/items}}{{^none}}!{{/none}}", values)

		assertEquals("a.kt: x y!", out)
	}

	@Test
	fun givenADottedName_whenRendering_thenItIsATag() {
		val values = mapOf("item.name" to "a.kt", "file.list" to listOf(mapOf("x" to "1")))

		val out = PromptTemplateEngine.render("{{item.name}}{{#file.list}} {{x}}{{/file.list}}", values)

		assertEquals("a.kt 1", out)
	}

	@Test
	fun givenANullValue_whenRenderedAsText_thenItFails() {
		// Null opens no section; printed as text it would be a hole in the prompt.
		assertThrows(IllegalArgumentException::class.java) {
			PromptTemplateEngine.render("{{A}}", mapOf("A" to null))
		}
	}

	@Test
	fun givenABoolean_whenRenderedAsText_thenItFails() {
		// A flag is for a section; printed it would put "true" in the prompt.
		assertThrows(IllegalArgumentException::class.java) {
			PromptTemplateEngine.render("{{A}}", mapOf("A" to true))
		}
	}

	@Test
	fun givenJsonBraces_whenRendering_thenOnlyTagsAreTouched() {
		// The tool-call example ends in `"}}`, which must not be read as a tag.
		val template = """{"args":{"file_path":"{{PATH}}"}}"""

		val out = PromptTemplateEngine.render(template, mapOf("PATH" to "a.kt"))

		assertEquals("""{"args":{"file_path":"a.kt"}}""", out)
	}

	@Test
	fun givenAList_whenRenderingASection_thenItRepeatsPerItemWithTheItemsKeys() {
		val values =
			mapOf(
				"TOOLS" to listOf(mapOf("NAME" to "a"), mapOf("NAME" to "b")),
				"NAME" to "outer",
			)

		val out = PromptTemplateEngine.render("{{#TOOLS}}[{{NAME}}]{{/TOOLS}} {{NAME}}", values)

		assertEquals("[a][b] outer", out)
	}

	@Test
	fun givenAnItemWithoutAKey_whenRenderingASection_thenTheOuterValueIsUsed() {
		val values = mapOf("ITEMS" to listOf(mapOf("X" to "1")), "TOOL" to "respond")

		val out = PromptTemplateEngine.render("{{#ITEMS}}{{X}} {{TOOL}}{{/ITEMS}}", values)

		assertEquals("1 respond", out)
	}

	@Test
	fun givenAList_whenRenderingASection_thenFirstAndLastMarkEachItemsPosition() {
		// What lets a layout separate groups without a trailing separator.
		val values = mapOf("L" to listOf(mapOf("X" to "a"), mapOf("X" to "b"), mapOf("X" to "c")))

		val out = PromptTemplateEngine.render("{{#L}}{{^FIRST}}, {{/FIRST}}{{X}}{{#LAST}}.{{/LAST}}{{/L}}", values)

		assertEquals("a, b, c.", out)
	}

	@Test
	fun givenFalsyValues_whenRenderingSections_thenEachDropsOut() {
		val values = mapOf("N" to null, "F" to false, "E" to "", "L" to emptyList<Any>())

		val out = PromptTemplateEngine.render("a{{#N}}n{{/N}}{{#F}}f{{/F}}{{#E}}e{{/E}}{{#L}}l{{/L}}b", values)

		assertEquals("ab", out)
	}

	@Test
	fun givenFalsyValues_whenRenderingInvertedSections_thenEachRendersOnce() {
		val values = mapOf("N" to null, "F" to false, "E" to "", "L" to emptyList<Any>())

		val out = PromptTemplateEngine.render("{{^N}}n{{/N}}{{^F}}f{{/F}}{{^E}}e{{/E}}{{^L}}l{{/L}}", values)

		assertEquals("nfel", out)
	}

	@Test
	fun givenTruthyValues_whenRenderingSections_thenEachRendersOnceAndTheInvertedOnesDropOut() {
		val values = mapOf("T" to true, "S" to "x", "P" to PromptText("p", "p"))

		val out = PromptTemplateEngine.render("{{#T}}t{{/T}}{{#S}}{{S}}{{/S}}{{#P}}p{{/P}}{{^T}}no{{/T}}", values)

		assertEquals("txp", out)
	}

	@Test
	fun givenSectionTagsOnTheirOwnLines_whenRendering_thenThoseLinesLeaveNoTrace() {
		// What lets a layout put one tag per line without blank lines in the prompt.
		val template = "Tools:\n{{#TOOLS}}\n- {{NAME}}\n{{/TOOLS}}\nend"
		val values = mapOf("TOOLS" to listOf(mapOf("NAME" to "a"), mapOf("NAME" to "b")))

		assertEquals("Tools:\n- a\n- b\nend", PromptTemplateEngine.render(template, values))
	}

	@Test
	fun givenAClosedSectionOnItsOwnLines_whenRendering_thenNoBlankLineIsLeft() {
		val template = "a\n  {{#X}}  \nx\n{{/X}}\nb"

		assertEquals("a\nb", PromptTemplateEngine.render(template, mapOf("X" to false)))
	}

	@Test
	fun givenASectionTagSharingItsLine_whenRendering_thenTheLineIsKept() {
		val template = "a {{#X}}x{{/X}}\nb"

		assertEquals("a x\nb", PromptTemplateEngine.render(template, mapOf("X" to true)))
	}

	@Test
	fun givenAnUnclosedOrMismatchedSection_whenRendering_thenItFails() {
		val values = mapOf("A" to true, "B" to true)

		assertThrows(IllegalArgumentException::class.java) {
			PromptTemplateEngine.render("{{#A}}x", values)
		}
		assertThrows(IllegalArgumentException::class.java) {
			PromptTemplateEngine.render("{{^A}}x{{/B}}", values)
		}
	}
}
