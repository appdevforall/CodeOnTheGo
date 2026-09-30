package com.itsaky.androidide.plugins.ai.prompt

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Maps a merged prompt config onto a plugin's own config type; the only per-plugin part of
 * loading. Throw [PromptConfigException] for a missing, mistyped or unknown key.
 */
fun interface PromptConfigParser<out T> {
	/**
	 * @param document the merged top-level keys and the file each came from.
	 * @return the plugin's config.
	 */
	fun parse(document: PromptConfigDocument): T
}

/**
 * Reads `agent.yml` and the files its `include` lists, off the main thread, and parses them as one.
 * Each top-level key lives in exactly one file; which one is up to the files, not this code.
 */
object PromptConfigLoader {
	/** The entry file, relative to the source's root. */
	const val FILE = "agent.yml"

	/** The entry file's key listing the other files; read here, never by the parser. */
	private const val INCLUDE = "include"

	/**
	 * Reads and parses the config; a malformed or missing file throws [PromptConfigException].
	 *
	 * @param source where the config files live.
	 * @param parser maps the merged document onto the plugin's config type.
	 * @param dispatcher the dispatcher the blocking reads run on.
	 * @return the config.
	 */
	suspend fun <T> load(
		source: PromptConfigSource,
		parser: PromptConfigParser<T>,
		dispatcher: CoroutineDispatcher = Dispatchers.IO,
	): T =
		withContext(dispatcher) {
			val entry = read(source, FILE, "$FILE does not exist")
			val files =
				buildList {
					add(FILE to entry.filterKeys { it != INCLUDE })
					for (file in includes(entry)) {
						val included = read(source, file, "$FILE: include names $file, which does not exist")
						if (INCLUDE in included) throw PromptConfigException("$file: include is only read in $FILE")
						add(file to included)
					}
				}
			parser.parse(PromptConfigDocument.merge(FILE, files))
		}

	/**
	 * Validates `include`: each entry a `.yml` path under the root, listed once.
	 *
	 * @param entry the entry file's mapping.
	 * @return the files to read, in order; empty when the entry file holds everything itself.
	 */
	private fun includes(entry: Map<*, *>): List<String> {
		if (INCLUDE !in entry) return emptyList()
		val list =
			entry[INCLUDE] as? List<*>
				?: throw PromptConfigException("$FILE: include expected a list of file names")
		val files = list.mapIndexed(::includedFile)
		val seen = HashSet<String>()
		files.firstOrNull { !seen.add(it) }?.let { throw PromptConfigException("$FILE: include lists $it twice") }
		return files
	}

	/** One `include` entry, refused unless it names another `.yml` file inside the root. */
	private fun includedFile(
		index: Int,
		item: Any?,
	): String {
		val name = (item as? String)?.trim().orEmpty()
		// A backslash and ':' are refused outright: a directory source on a Windows host would read them
		// as a separator or a drive, and neither has a use in an asset path.
		val escapesRoot = name.startsWith("/") || '\\' in name || ':' in name || ".." in name.split('/')
		if (!name.endsWith(".yml") || escapesRoot) {
			throw PromptConfigException("$FILE: include[$index] expected a .yml file under prompts/")
		}
		if (name == FILE) throw PromptConfigException("$FILE: include[$index] names $FILE itself")
		return name
	}

	private fun read(
		source: PromptConfigSource,
		file: String,
		missing: String,
	): Map<*, *> {
		val text =
			try {
				source.read(file)
			} catch (e: FileNotFoundException) {
				throw PromptConfigException(missing, e)
			} catch (e: IOException) {
				throw PromptConfigException("$file: could not be read: ${e.message}", e)
			}
		return PromptYaml.read(text, file)
	}
}
