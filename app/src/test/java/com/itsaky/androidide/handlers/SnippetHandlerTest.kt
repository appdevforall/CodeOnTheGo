package com.itsaky.androidide.handlers

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.snippets.SnippetRegistry
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
		SnippetRegistry.clear()
		Environment.SNIPPETS_DIR = originalSnippetsDir
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
}
