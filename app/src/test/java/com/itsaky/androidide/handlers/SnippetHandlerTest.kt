package com.itsaky.androidide.handlers

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.snippets.SnippetRegistry
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.SnippetContribution
import com.itsaky.androidide.plugins.extensions.SnippetExtension
import com.itsaky.androidide.plugins.manager.snippets.PluginSnippetManager
import com.itsaky.androidide.utils.Environment
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SnippetHandlerTest {
	@get:Rule
	val tempFolder = TemporaryFolder()

	private var originalSnippetsDir: File? = null

	@Before
	fun setUp() {
		originalSnippetsDir = Environment.SNIPPETS_DIR
		Environment.SNIPPETS_DIR = tempFolder.newFolder("snippets")
		SnippetRegistry.clear()
	}

	@After
	fun tearDown() {
		PluginSnippetManager.getInstance().cleanupPlugin(PLUGIN_ID)
		SnippetRegistry.clear()
		Environment.SNIPPETS_DIR = originalSnippetsDir
	}

	@Test
	fun `loadPluginSnippets maps the kotlin language id onto the kt registry key`() {
		registerPlugin(
			SnippetContribution(
				language = "kotlin",
				scope = "local",
				prefix = "ktplugin",
				description = "Contributed by a plugin",
				body = listOf("println(\"plugin\")"),
			),
		)

		SnippetHandler.loadPluginSnippets()

		assertThat(SnippetRegistry.getSnippets("kt", "local").map { it.prefix })
			.containsExactly("ktplugin")
		assertThat(SnippetRegistry.getSnippets("kotlin", "local")).isEmpty()
	}

	@Test
	fun `loadPluginSnippets leaves other language ids untouched`() {
		registerPlugin(
			SnippetContribution(
				language = "java",
				scope = "local",
				prefix = "javaplugin",
				description = "Contributed by a plugin",
				body = listOf("System.out.println();"),
			),
		)

		SnippetHandler.loadPluginSnippets()

		assertThat(SnippetRegistry.getSnippets("java", "local").map { it.prefix })
			.containsExactly("javaplugin")
		assertThat(SnippetRegistry.getSnippets("kt", "local")).isEmpty()
	}

	private fun registerPlugin(vararg contributions: SnippetContribution) {
		PluginSnippetManager.getInstance().registerPlugin(
			PLUGIN_ID,
			object : SnippetExtension {
				override fun getSnippetContributions() = contributions.toList()

				override fun initialize(context: PluginContext) = true

				override fun activate() = true

				override fun deactivate() = true

				override fun dispose() = Unit
			},
		)
	}

	@Test
	fun `loadUserSnippets loads Kotlin local snippet with content intact`() {
		writeKotlinSnippet(
			scope = "local",
			prefix = "ktlog",
			description = "Log a Kotlin value",
			body = listOf("println(\${1:value})", "\${0}"),
		)

		SnippetHandler.loadUserSnippets()

		val snippets = SnippetRegistry.getSnippets("kt", "local")
		assertThat(snippets).hasSize(1)
		assertThat(snippets.single().prefix).isEqualTo("ktlog")
		assertThat(snippets.single().description).isEqualTo("Log a Kotlin value")
		assertThat(snippets.single().body.asList())
			.containsExactly("println(\${1:value})", "\${0}")
			.inOrder()
	}

	@Test
	fun `loadUserSnippets keeps Kotlin snippets in their declared scope`() {
		writeKotlinSnippet(
			scope = "global",
			prefix = "ktglobal",
			description = "Available in every Kotlin scope",
			body = listOf("println(\"global\")"),
		)

		SnippetHandler.loadUserSnippets()

		assertThat(SnippetRegistry.getSnippets("kt", "global").map { it.prefix })
			.containsExactly("ktglobal")
		assertThat(SnippetRegistry.getSnippets("kt", "local")).isEmpty()
	}

	@Test
	fun `loadUserSnippets leaves Kotlin scopes empty when directory is absent`() {
		SnippetHandler.loadUserSnippets()

		assertThat(SnippetRegistry.getSnippets("kt", "local")).isEmpty()
		assertThat(SnippetRegistry.getSnippets("kt", "global")).isEmpty()
	}

	private fun writeKotlinSnippet(
		scope: String,
		prefix: String,
		description: String,
		body: List<String>,
	) {
		val bodyJson = body.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }
		val languageDir = File(Environment.SNIPPETS_DIR, "kt").apply { mkdirs() }
		File(languageDir, "snippets.$scope.json").writeText(
			"""{"$prefix":{"desc":"$description","body":[$bodyJson]}}""",
		)
	}

	private companion object {
		const val PLUGIN_ID = "test.snippets.plugin"
	}
}
