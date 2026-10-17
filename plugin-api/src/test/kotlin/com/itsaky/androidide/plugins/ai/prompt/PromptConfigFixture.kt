package com.itsaky.androidide.plugins.ai.prompt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import java.io.FileNotFoundException

/** A small plugin's config type, standing in for each AI plugin's own. */
data class FixtureConfig(
	val identity: PromptText,
	val rules: List<Rule>,
	val systemPrompt: PromptText,
	val tools: Map<String, Tool>,
) {
	data class Rule(
		val heading: PromptText,
		val items: List<PromptText>,
	)

	data class Tool(
		val description: PromptText,
		val arguments: Map<String, PromptText>,
	)
}

/** A config shaped like the AI plugins' `assets/prompts/`, held in memory so a test can rewrite any file. */
object PromptConfigFixture {
	const val SCHEMA_VERSION = 1

	val parser =
		PromptConfigParser { document ->
			document.read {
				val version = int("schema_version")
				if (version != SCHEMA_VERSION) {
					throw invalid("schema_version", "is $version, but this plugin reads $SCHEMA_VERSION")
				}
				FixtureConfig(
					identity = text("identity"),
					rules = objects("rules").map { it.read { FixtureConfig.Rule(text("heading"), texts("items")) } },
					systemPrompt = obj("layout").read { text("system_prompt") },
					tools =
						objectEntries("tools").mapValues { (_, tool) ->
							tool.read { FixtureConfig.Tool(text("description"), optionalTextEntries("arguments")) }
						},
				)
			}
		}

	val files: Map<String, String> =
		mapOf(
			"agent.yml" to
				"""
				|schema_version: 1
				|identity: >
				|  You help with any question,
				|  not only Android questions.
				|include:
				|  - rules.yml
				|  - layout.yml
				|
				""".trimMargin(),
			"rules.yml" to
				"""
				|rules:
				|  - heading: IMPORTANT
				|    items:
				|      - Reply briefly.
				|      - Call {{TOOL}} to read a file.
				|  - heading: STYLE
				|    items:
				|      - Use plain words.
				|
				""".trimMargin(),
			"layout.yml" to
				"""
				|layout:
				|  system_prompt: |
				|    {{IDENTITY}}
				|    {{RULE}}
				|tools:
				|  read_file:
				|    description: Reads a file.
				|    arguments:
				|      path: The file to read.
				|  list_files:
				|    description: Lists a directory.
				|
				""".trimMargin(),
		)

	/**
	 * Loads config from in-memory files; a path not in [files] does not exist.
	 *
	 * @param files each file's path and text.
	 * @return the loaded config.
	 */
	fun load(files: Map<String, String> = this.files): FixtureConfig = runBlocking { PromptConfigLoader.load(sourceOf(files), parser) }

	/** Loads the fixture with [file] rewritten by [edit]. */
	fun loadWith(
		file: String,
		edit: (String) -> String,
	): FixtureConfig = load(files + (file to edit(files.getValue(file))))

	fun sourceOf(files: Map<String, String>) = PromptConfigSource { path -> files[path] ?: throw FileNotFoundException(path) }
}

/** Asserts [block] refuses the config, returning the refusal. */
fun refused(block: () -> Unit): PromptConfigException = assertThrows(PromptConfigException::class.java) { block() }

/** Asserts [block] refuses the config with exactly [message]. */
fun assertRefused(
	message: String,
	block: () -> Unit,
) = assertEquals(message, refused(block).message)

/** A source over the fixture whose every read blocks until [gate] completes. */
fun gatedSource(gate: CompletableDeferred<Unit>) =
	PromptConfigSource { path ->
		runBlocking { gate.await() }
		PromptConfigFixture.files.getValue(path)
	}
