package com.itsaky.androidide.lsp

import android.content.res.AssetManager
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.extensions.LanguageDefinition
import com.itsaky.androidide.plugins.extensions.LanguageServerDefinition
import com.itsaky.androidide.plugins.extensions.TreeSitterGrammar
import com.itsaky.androidide.plugins.manager.language.PluginLanguageContribution
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** A plugin may claim the JavaScript types the IDE highlights only to supply a language server. */
@RunWith(RobolectricTestRunner::class)
class PluginLanguageSupportTest {
	@get:Rule
	val tmp = TemporaryFolder()

	private val server = LanguageServerDefinition(command = listOf("js-server", "--stdio"))

	private fun contribution(
		pluginId: String,
		extensions: Set<String>,
		withServer: Boolean,
	): PluginLanguageContribution {
		val libs = tmp.newFolder()
		// The factory does not load the library when built; it only needs the file to exist.
		File(libs, "libtree-sitter-javascript.so").createNewFile()
		return PluginLanguageContribution(
			pluginId = pluginId,
			definition =
				LanguageDefinition(
					languageId = "javascript",
					fileExtensions = extensions,
					grammar = TreeSitterGrammar("javascript", "treesitter/javascript"),
					server = if (withServer) server else null,
				),
			assets = mockk<AssetManager>(relaxed = true),
			nativeLibraryDir = libs,
		)
	}

	@Test
	fun `givenJsClaimWithServer_whenInstalling_thenServerIsInstalledAndGrammarIgnored`() {
		val installed = PluginLanguageSupport.install(contribution("p", setOf("js", "mjs", "cjs"), true), mutableSetOf())

		assertThat(installed).isNotNull()
		assertThat(installed!!.fileTypes).containsExactly("js", "mjs", "cjs")
		assertThat(installed.grammarFactories).isEmpty()
		assertThat(installed.serverId).isNotNull()
	}

	@Test
	fun `givenJsClaimWithoutServer_whenInstalling_thenItIsRefused`() {
		val installed = PluginLanguageSupport.install(contribution("p", setOf("js", "mjs", "cjs"), false), mutableSetOf())

		assertThat(installed).isNull()
	}

	@Test
	fun `givenJsAndTsClaim_whenInstalling_thenGrammarIsBuiltForTsOnly`() {
		val installed = PluginLanguageSupport.install(contribution("p", setOf("js", "ts"), true), mutableSetOf())

		assertThat(installed).isNotNull()
		assertThat(installed!!.fileTypes).containsExactly("js", "ts")
		assertThat(installed.grammarFactories.keys).containsExactly("ts")
	}

	@Test
	fun `givenClosedTypeClaimWithServer_whenInstalling_thenItIsRefused`() {
		for (type in listOf("java", "kt", "xml")) {
			val installed = PluginLanguageSupport.install(contribution("p", setOf(type), true), mutableSetOf())

			assertThat(installed).isNull()
		}
	}

	@Test
	fun `givenJsAlreadyClaimed_whenAnotherPluginClaimsIt_thenItIsRefused`() {
		val claimed = mutableSetOf<String>()
		assertThat(PluginLanguageSupport.install(contribution("first", setOf("js"), true), claimed)).isNotNull()

		val second = PluginLanguageSupport.install(contribution("second", setOf("js"), true), claimed)

		assertThat(second).isNull()
	}
}
