package com.itsaky.androidide.plugins.ai.prompt

/**
 * Renders a prompt template: `{{NAME}}` values, `{{#NAME}}...{{/NAME}}` and `{{^NAME}}...{{/NAME}}`
 * sections; a name is a letter then letters, digits, `_` or `.`, in any case, e.g. `{{FILE_NAME}}`,
 * `{{fileName}}` or `{{item.name}}`, looked up as the whole key. Strict, so a typo throws; a String value is never rescanned, a [PromptText] always is.
 * Stateless and free of reflection, so it is safe to call from any thread.
 */
object PromptTemplateEngine {
	/** Set in each list item's scope: whether it is the list's first item. */
	const val FIRST = "FIRST"

	/** Set in each list item's scope: whether it is the list's last item. */
	const val LAST = "LAST"

	private val TAG = Regex("""\{\{(?:([#^/])([a-zA-Z][a-zA-Z0-9_.]*)|([a-zA-Z][a-zA-Z0-9_.]*))\}\}""")

	/** Deep enough for any sane nesting of [PromptText]; stops one that names itself. */
	private const val MAX_DEPTH = 8

	/** What may share a line with a standalone section tag. */
	private const val INDENT = " \t"

	/**
	 * Renders [template] against [values].
	 *
	 * @param template the template text.
	 * @param values each name's value: a String, a [PromptText], a Boolean, null, or a list of maps.
	 * @return the rendered text.
	 */
	fun render(
		template: String,
		values: Map<String, Any?>,
	): String = buildString { render(parse(template), listOf(values), 0, this) }

	/**
	 * Renders config text against [values]; a failure names [PromptText.label].
	 *
	 * @param text the config text.
	 * @param values each name's value, as for the other overload.
	 * @return the rendered text.
	 */
	fun render(
		text: PromptText,
		values: Map<String, Any?>,
	): String = buildString { renderText(text, listOf(values), 0, this) }

	/** A render failure already located in one [PromptText], so enclosing ones leave it as is. */
	class RenderException(
		message: String,
		cause: Throwable? = null,
	) : IllegalArgumentException(message, cause)

	private sealed interface Node {
		data class Text(
			val text: String,
		) : Node

		data class Value(
			val name: String,
		) : Node

		data class Section(
			val name: String,
			val inverted: Boolean,
			val children: List<Node>,
		) : Node
	}

	private fun renderText(
		text: PromptText,
		scopes: List<Map<String, Any?>>,
		depth: Int,
		out: StringBuilder,
	) {
		try {
			require(depth < MAX_DEPTH) { "nests too deep" }
			render(parse(text.template), scopes, depth + 1, out)
		} catch (e: IllegalArgumentException) {
			throw e as? RenderException ?: RenderException("${text.label}: ${e.message}", e)
		}
	}

	private fun render(
		nodes: List<Node>,
		scopes: List<Map<String, Any?>>,
		depth: Int,
		out: StringBuilder,
	) {
		for (node in nodes) {
			when (node) {
				is Node.Text -> {
					out.append(node.text)
				}

				is Node.Value -> {
					when (val value = lookup(node.name, scopes)) {
						is String -> out.append(value)
						is PromptText -> renderText(value, scopes, depth, out)
						null -> throw IllegalArgumentException("no value for {{${node.name}}}")
						else -> throw IllegalArgumentException("{{${node.name}}} is not text")
					}
				}

				is Node.Section -> {
					section(node, scopes, depth, out)
				}
			}
		}
	}

	private fun section(
		node: Node.Section,
		scopes: List<Map<String, Any?>>,
		depth: Int,
		out: StringBuilder,
	) {
		val value = lookup(node.name, scopes)
		when {
			node.inverted -> {
				if (!isTruthy(value)) render(node.children, scopes, depth, out)
			}

			value is List<*> -> {
				value.forEachIndexed { index, item ->
					@Suppress("UNCHECKED_CAST")
					val scope =
						requireNotNull(item as? Map<String, Any?>) {
							"{{#${node.name}}} items must be maps"
						}
					val position = mapOf(FIRST to (index == 0), LAST to (index == value.lastIndex))
					render(node.children, scopes + position + scope, depth, out)
				}
			}

			isTruthy(value) -> {
				render(node.children, scopes, depth, out)
			}
		}
	}

	/** False for null, `false`, empty text and an empty list; true for anything else. */
	private fun isTruthy(value: Any?): Boolean =
		when (value) {
			null, false -> false
			is String -> value.isNotEmpty()
			is PromptText -> value.template.isNotEmpty()
			is List<*> -> value.isNotEmpty()
			else -> true
		}

	/** Finds [name] in the innermost scope that has it; a name no scope has is a typo. */
	private fun lookup(
		name: String,
		scopes: List<Map<String, Any?>>,
	): Any? {
		val scope = requireNotNull(scopes.lastOrNull { name in it }) { "unknown name {{$name}}" }
		return scope[name]
	}

	/**
	 * Parses [template] into nodes, removing the lines that hold only a section tag.
	 *
	 * @param template the template text.
	 * @return the top-level nodes.
	 */
	private fun parse(template: String): List<Node> {
		val root = mutableListOf<Node>()
		// Each open section: its opening tag and the nodes collected for it so far.
		val open = ArrayDeque<Pair<Node.Section, MutableList<Node>>>()

		fun current() = open.lastOrNull()?.second ?: root

		var position = 0
		for (match in TAG.findAll(template)) {
			if (match.range.first < position) continue
			val (marker, sectionName, valueName) = match.destructured
			var textEnd = match.range.first
			var next = match.range.last + 1
			if (marker.isNotEmpty()) {
				standaloneLine(template, match.range, position)?.let { (start, end) ->
					textEnd = start
					next = end
				}
			}
			if (textEnd > position) current().add(Node.Text(template.substring(position, textEnd)))
			position = next

			when (marker) {
				"#", "^" -> {
					open.addLast(Node.Section(sectionName, marker == "^", emptyList()) to mutableListOf())
				}

				"/" -> {
					val (tag, children) =
						requireNotNull(open.removeLastOrNull()) {
							"{{/$sectionName}} closes no section"
						}
					require(tag.name == sectionName) { "{{/$sectionName}} closes {{#${tag.name}}}" }
					current().add(tag.copy(children = children))
				}

				else -> {
					current().add(Node.Value(valueName))
				}
			}
		}
		require(open.isEmpty()) { "{{#${open.last().first.name}}} is never closed" }
		if (position < template.length) root.add(Node.Text(template.substring(position)))
		return root
	}

	/**
	 * Finds the span to remove when a tag is alone on its line: the line's indent up to its newline.
	 *
	 * @param template the template text.
	 * @param tag where the tag is.
	 * @param notBefore where the text not yet consumed starts.
	 * @return the span's start and end, or null when the line holds anything besides the tag.
	 */
	private fun standaloneLine(
		template: String,
		tag: IntRange,
		notBefore: Int,
	): Pair<Int, Int>? {
		val lineStart = maxOf(template.lastIndexOf('\n', tag.first - 1) + 1, notBefore)
		if (!template.onlyHas(INDENT, lineStart, tag.first)) return null
		if (lineStart == notBefore && lineStart > 0 && template[lineStart - 1] != '\n') return null
		val newline = template.indexOf('\n', tag.last + 1)
		val lineEnd = if (newline < 0) template.length else newline
		if (!template.onlyHas("$INDENT\r", tag.last + 1, lineEnd)) return null
		return lineStart to if (newline < 0) lineEnd else newline + 1
	}

	private fun String.onlyHas(
		chars: String,
		start: Int,
		end: Int,
	): Boolean = (start until end).all { this[it] in chars }
}
