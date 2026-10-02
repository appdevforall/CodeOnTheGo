package com.itsaky.androidide.plugins.ai.prompt

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.exceptions.YamlEngineException

/**
 * The prompt config as one mapping, merged from `agent.yml` and the files it includes, remembering
 * which file each top-level key came from so an error names the file to fix.
 *
 * @property values the merged top-level keys, in the order the files were read.
 * @property entryFile the file an absent key is reported against.
 */
class PromptConfigDocument private constructor(
	val values: Map<String, Any?>,
	private val origins: Map<String, String>,
	val entryFile: String,
) {
	/**
	 * @param key a top-level key.
	 * @return the file that defines it, or [entryFile] when none does.
	 */
	fun fileOf(key: String): String = origins[key] ?: entryFile

	/**
	 * Reads the top-level mapping with [block], then refuses any top-level key it did not read.
	 *
	 * @param block maps the config onto the plugin's own type.
	 * @return what [block] returned.
	 */
	fun <T> read(block: PromptConfigObject.() -> T): T = PromptConfigObject(values, "", ::fileOf).read(block)

	companion object {
		/**
		 * Merges files' top-level keys; a key defined in two files throws, since neither may win silently.
		 *
		 * @param entryFile the file an absent key is reported against.
		 * @param files each file's name and its parsed mapping, in read order.
		 * @return the merged document.
		 */
		fun merge(
			entryFile: String,
			files: List<Pair<String, Map<*, *>>>,
		): PromptConfigDocument {
			val values = LinkedHashMap<String, Any?>()
			val origins = HashMap<String, String>()
			for ((file, map) in files) {
				for ((rawKey, value) in map) {
					val key = rawKey.toString()
					origins.put(key, file)?.let { first ->
						throw PromptConfigException("$file: $key is also defined in $first; a key belongs to one file")
					}
					values[key] = value
				}
			}
			return PromptConfigDocument(values, origins, entryFile)
		}
	}
}

/**
 * Reads one prompt config file into plain maps and lists, with no class binding, so no reflection.
 * Host-side, so plugins do not bundle a YAML library of their own. The library itself drops a
 * leading BOM and folds CRLF line ends, which the loader tests pin.
 */
internal object PromptYaml {
	/**
	 * Parses one file; duplicate keys are refused, since an edit to the first would do nothing.
	 *
	 * @param text the file's raw text.
	 * @param fileName the file's name, for error messages.
	 * @return the file's top-level mapping.
	 */
	fun read(
		text: String,
		fileName: String,
	): Map<*, *> {
		val document =
			try {
				Load(
					LoadSettings
						.builder()
						.setLabel(fileName)
						// Already the default; stated so a library upgrade cannot flip it unnoticed.
						.setAllowDuplicateKeys(false)
						.build(),
				).loadFromString(text)
			} catch (e: YamlEngineException) {
				throw PromptConfigException("$fileName: ${e.message}", e)
			}
		return document as? Map<*, *> ?: throw PromptConfigException("$fileName: expected a mapping at the top")
	}
}
