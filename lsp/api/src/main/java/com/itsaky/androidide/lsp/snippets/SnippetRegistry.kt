package com.itsaky.androidide.lsp.snippets

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

object SnippetRegistry {

	private val lock = ReentrantReadWriteLock()

	private val builtIn = mutableMapOf<String, MutableMap<String, MutableList<ISnippet>>>()
	private val user = mutableMapOf<String, MutableMap<String, MutableList<ISnippet>>>()
	private val plugin = mutableMapOf<String, MutableMap<String, MutableMap<String, MutableList<ISnippet>>>>()

	fun registerBuiltIn(language: String, scope: String, snippets: List<ISnippet>) {
		lock.write {
			builtIn.getOrPut(language) { mutableMapOf() }[scope] = snippets.toMutableList()
		}
	}

	fun registerUserSnippets(language: String, scope: String, snippets: List<ISnippet>) {
		lock.write {
			user.getOrPut(language) { mutableMapOf() }[scope] = snippets.toMutableList()
		}
	}

	fun registerPluginSnippets(
		pluginId: String,
		language: String,
		scope: String,
		snippets: List<ISnippet>,
	) {
		lock.write {
			plugin.getOrPut(pluginId) { mutableMapOf() }
				.getOrPut(language) { mutableMapOf() }[scope] = snippets.toMutableList()
		}
	}

	fun unregisterPluginSnippets(pluginId: String) {
		lock.write {
			plugin.remove(pluginId)
		}
	}

	fun clearUserSnippets(language: String) {
		lock.write {
			user.remove(language)
		}
	}

	fun getSnippets(language: String, scope: String): List<ISnippet> = lock.read {
		val builtInSnippets = builtIn[language]?.get(scope).orEmpty()
		val userSnippets = user[language]?.get(scope).orEmpty()

		val userPrefixes = userSnippets.map { it.prefix }.toSet()
		val merged = builtInSnippets.filter { it.prefix !in userPrefixes }.toMutableList()
		merged.addAll(userSnippets)

		plugin.values.forEach { langMap ->
			langMap[language]?.get(scope)?.let { merged.addAll(it) }
		}

		merged
	}

	fun getSnippets(language: String, scopes: List<String>): List<ISnippet> {
		return scopes.flatMap { getSnippets(language, it) }
	}

	fun <S : ISnippetScope> initBuiltIn(language: String, scopes: Array<S>) {
		val parsed = SnippetParser.parse(language, scopes)
		lock.write {
			parsed.forEach { (scope, snippets) ->
				builtIn.getOrPut(language) { mutableMapOf() }[scope.filename] = snippets as MutableList<ISnippet>
			}
		}
	}

	fun clear() {
		lock.write {
			builtIn.clear()
			user.clear()
			plugin.clear()
		}
	}
}