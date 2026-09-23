package com.itsaky.androidide.lsp.kotlin.completion

import com.google.common.truth.Truth.assertThat
import org.appdevforall.codeonthego.indexing.jvm.JvmClassInfo
import org.appdevforall.codeonthego.indexing.jvm.JvmEnumEntryInfo
import org.appdevforall.codeonthego.indexing.jvm.JvmFunctionInfo
import org.appdevforall.codeonthego.indexing.jvm.JvmSourceLanguage
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbol
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolInfo
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolKind
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Which index symbols unimported-symbol completion may offer.
 *
 * Accepting an item inserts its bare name, so only a symbol Kotlin can resolve after the
 * auto-import is worth offering.
 */
@RunWith(JUnit4::class)
class UnimportedSymbolCandidateTest {
	private fun symbol(
		shortName: String,
		kind: JvmSymbolKind,
		data: JvmSymbolInfo = JvmClassInfo(internalName = "kotlin/text/$shortName"),
	) = JvmSymbol(
		key = "kotlin/text/$shortName",
		sourceId = "stdlib.jar",
		name = "kotlin/text/$shortName",
		shortName = shortName,
		packageName = "kotlin.text",
		kind = kind,
		language = JvmSourceLanguage.KOTLIN,
		data = data,
	)

	@Test
	fun `a file facade is not offered`() {
		// Kotlin cannot name `StringsKt`, so inserting it would not resolve.
		assertThat(isUnimportedSymbolCandidate(symbol("StringsKt", JvmSymbolKind.FILE_FACADE))).isFalse()
	}

	@Test
	fun `a companion object is not offered by its bare name`() {
		val companion =
			symbol(
				"Companion",
				JvmSymbolKind.COMPANION_OBJECT,
				JvmClassInfo(internalName = "kotlin/text/Regex\$Companion", containingClassName = "kotlin/text/Regex"),
			)

		assertThat(isUnimportedSymbolCandidate(companion)).isFalse()
	}

	@Test
	fun `a class is offered`() {
		assertThat(isUnimportedSymbolCandidate(symbol("Regex", JvmSymbolKind.CLASS))).isTrue()
	}

	@Test
	fun `a top-level function is offered`() {
		assertThat(isUnimportedSymbolCandidate(symbol("trimIndent", JvmSymbolKind.FUNCTION, JvmFunctionInfo()))).isTrue()
	}

	@Test
	fun `a member function is not offered`() {
		val member =
			symbol("find", JvmSymbolKind.FUNCTION, JvmFunctionInfo(containingClassName = "kotlin/text/Regex"))

		assertThat(isUnimportedSymbolCandidate(member)).isFalse()
	}

	@Test
	fun `an enum entry is not offered`() {
		// Inserting a bare enum entry name with no import does not resolve; it must be qualified by
		// its enum class.
		val entry =
			symbol(
				"REGEX_OPTION",
				JvmSymbolKind.ENUM_ENTRY,
				JvmEnumEntryInfo(containingClassName = "kotlin/text/RegexOption"),
			)

		assertThat(isUnimportedSymbolCandidate(entry)).isFalse()
	}

	@Test
	fun `the query asks for exactly the kinds that can be offered`() {
		assertThat(UNIMPORTED_SYMBOL_KINDS)
			.containsExactlyElementsIn(JvmSymbolKind.CLASSIFIER_KINDS - JvmSymbolKind.COMPANION_OBJECT + JvmSymbolKind.CALLABLE_KINDS)
	}
}
