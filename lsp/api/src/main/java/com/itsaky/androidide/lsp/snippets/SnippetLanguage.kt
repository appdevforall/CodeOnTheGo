package com.itsaky.androidide.lsp.snippets

enum class SnippetLanguage(
	val id: String,
	private val aliases: Set<String> = emptySet(),
) {
	JAVA("java"),
	KOTLIN("kt", setOf("kotlin")),
	XML("xml"),
	;

	companion object {
		private val byId =
			entries
				.flatMap { language -> (language.aliases + language.id).map { it to language } }
				.toMap()

		fun fromId(value: String): SnippetLanguage? = byId[value.lowercase()]
	}
}
