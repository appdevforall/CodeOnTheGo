package com.itsaky.androidide.lsp.snippets

import com.google.gson.JsonParseException
import com.itsaky.androidide.utils.Environment
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException

object UserSnippetLoader {

	private val log = LoggerFactory.getLogger(UserSnippetLoader::class.java)

	fun <S : ISnippetScope> loadUserSnippets(
		language: String,
		scopes: Iterable<S>,
	): Map<String, List<ISnippet>> {
		val langDir = getUserSnippetsDir(language)
		if (!langDir.isDirectory) return emptyMap()

		return scopes.associate { scope ->
			val file = File(langDir, "snippets.${scope.filename}.json")
			val snippets = if (file.isFile) {
				try {
					SnippetParser.parseFromReader(file.reader())
				} catch (e: IOException) {
					log.error("Failed to read user snippets from {}", file.absolutePath, e)
					emptyList()
				} catch (e: JsonParseException) {
					log.error("Failed to parse user snippets from {}", file.absolutePath, e)
					emptyList()
				}
			} else {
				emptyList()
			}
			scope.filename to snippets
		}
	}

	fun getUserSnippetsDir(language: String): File {
		return File(Environment.SNIPPETS_DIR, language)
	}
}
