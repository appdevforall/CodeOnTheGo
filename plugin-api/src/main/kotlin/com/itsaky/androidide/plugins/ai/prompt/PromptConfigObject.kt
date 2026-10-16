package com.itsaky.androidide.plugins.ai.prompt

/**
 * One YAML mapping of a prompt config, read key by key; [read] then refuses any key nothing asked
 * for, so a typo fails on activation rather than silently dropping wording. Obtain the root from
 * [PromptConfigDocument.read]. Not thread-safe: read one object from one thread.
 *
 * @param map the parsed mapping.
 * @param path where it sits in the config, e.g. `rules[0]`; empty for the root.
 * @param fileOf the file a key of this mapping is defined in, for error messages and labels.
 */
class PromptConfigObject internal constructor(
	private val map: Map<*, *>,
	private val path: String,
	private val fileOf: (String) -> String,
) {
	private val consumed = HashSet<String>()

	/** Runs [block] against this mapping, then fails on any key it did not read. */
	fun <T> read(block: PromptConfigObject.() -> T): T {
		val result = block()
		val unknown = map.keys.map { it.toString() }.filterNot { it in consumed }
		if (unknown.isNotEmpty()) {
			// Reported per file, so the message points at the one file holding the stray key.
			val (file, keys) = unknown.groupBy(fileOf).entries.first()
			val where = if (path.isEmpty()) "" else "$path: "
			val expected = consumed.joinToString()
			throw PromptConfigException("$file: ${where}unknown key ${keys.joinToString()}; expected $expected")
		}
		return result
	}

	/** A non-blank string, as config text, exactly as YAML parsed it; a `|` block keeps its final newline, `|-` drops it. */
	fun text(key: String): PromptText = asText(value(key), key, at(key))

	/** A non-empty list of non-blank strings, as config text. */
	fun texts(key: String): List<PromptText> = nonEmptyList(key).mapIndexed { index, item -> asText(item, key, "${at(key)}[$index]") }

	fun int(key: String): Int = typed(key, "expected a whole number")

	fun obj(key: String): PromptConfigObject = asObject(value(key), key, at(key))

	fun objects(key: String): List<PromptConfigObject> =
		nonEmptyList(key).mapIndexed { index, item -> asObject(item, key, "${at(key)}[$index]") }

	/** A non-empty mapping whose keys are data, such as tool names, each value itself a mapping. */
	fun objectEntries(key: String): Map<String, PromptConfigObject> =
		nonEmptyEntries(key).mapValues { (name, item) -> asObject(item, key, "${at(key)}.$name") }

	/** A mapping of names to non-blank text, such as argument descriptions; absent means none. */
	fun optionalTextEntries(key: String): Map<String, PromptText> {
		if (key !in map) {
			consumed += key
			return emptyMap()
		}
		return nonEmptyEntries(key).mapValues { (name, item) -> asText(item, key, "${at(key)}.$name") }
	}

	/**
	 * @param key the key at fault.
	 * @param problem what is wrong with it, e.g. `is 2, but this plugin reads 1`.
	 * @return the exception to throw, naming the file and the key's path.
	 */
	fun invalid(
		key: String,
		problem: String,
	) = fail(key, at(key), problem)

	/** Where [key] is declared, as config text labels it, e.g. `tools.yml: tools.heading`. */
	fun labelOf(key: String) = "${fileOf(key)}: ${at(key)}"

	private fun nonEmptyList(key: String): List<*> = typed<List<*>>(key, "expected a list").ifEmpty { throw invalid(key, "is empty") }

	private fun nonEmptyEntries(key: String): Map<String, Any?> =
		typed<Map<*, *>>(key, "expected a mapping")
			.ifEmpty { throw invalid(key, "is empty") }
			.entries
			.associate { (name, item) -> name.toString() to item }

	/** [key]'s value as a [T], or a refusal saying what was [expected]. */
	private inline fun <reified T> typed(
		key: String,
		expected: String,
	): T = value(key) as? T ?: throw invalid(key, expected)

	private fun value(key: String): Any? {
		consumed += key
		if (key !in map) throw invalid(key, "is missing")
		return map[key]
	}

	/** A nested mapping lives wholly in the file that defines [key]. */
	private fun asObject(
		value: Any?,
		key: String,
		path: String,
	): PromptConfigObject {
		val nested = value as? Map<*, *> ?: throw fail(key, path, "expected a mapping")
		val file = fileOf(key)
		return PromptConfigObject(nested, path) { file }
	}

	private fun asText(
		value: Any?,
		key: String,
		path: String,
	): PromptText {
		// A number or true/false here is almost always an unquoted value YAML retyped.
		val text = value as? String ?: throw fail(key, path, "expected text; quote it")
		if (text.isBlank()) throw fail(key, path, "is blank")
		return PromptText(text, "${fileOf(key)}: $path")
	}

	private fun at(key: String) = if (path.isEmpty()) key else "$path.$key"

	private fun fail(
		key: String,
		path: String,
		problem: String,
	) = PromptConfigException("${fileOf(key)}: $path $problem")
}
