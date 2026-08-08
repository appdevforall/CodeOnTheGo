package org.appdevforall.cotg.quickbuild.domain.annotations

/**
 * Extracts [AnnotationFacts] from Kotlin/Java source text without a compiler front-end.
 *
 * Text rather than a parser because classification runs on every save, before any compile,
 * with no resolved PSI to consult. The scanner aims at one property - never miss a change that
 * could alter annotation-processor output - and pays for it with over-inclusiveness:
 * - String literals are kept verbatim (an `@Query("SELECT ...")` edit IS processor input);
 *   comments and whitespace are normalized away.
 * - Only function/initializer bodies are excluded from the declaration fingerprint, and only
 *   when the opening line is unambiguously a signature. Anything unrecognized stays in, so it
 *   makes the file look changed rather than unchanged.
 * - Structural surprises - unbalanced braces, an unterminated comment or raw string - return
 *   null, which the analyzer reads as "rebaseline".
 *
 * @see AnnotationImpactAnalyzer for how the facts turn into a routing decision.
 */
object SourceAnnotationScanner {
	/** Placeholder standing in for string/char-literal content while scanning structure. */
	private const val MASKED = '\u0001'

	/**
	 * Extracts one file's facts from its source text.
	 *
	 * @param text the whole file, Kotlin or Java; the language is inferred from what the text
	 *   contains rather than from any extension.
	 * @return null when the text could not be scanned confidently (unbalanced braces,
	 *   unterminated comment or raw string). Callers must read that as "assume processor
	 *   input changed".
	 */
	fun scan(text: String): AnnotationFacts? {
		val prepared = prepare(text) ?: return null
		val bodyMask = markFunctionBodies(prepared) ?: return null

		val fingerprint =
			prepared.codeLines
				.filterIndexed { index, _ -> !bodyMask[index] }
				.map { it.normalizeWhitespace() }
				.filter { it.isNotEmpty() }

		val annotations = extractAnnotations(prepared)
		val imports =
			prepared.codeLines.mapNotNull { line ->
				IMPORT.find(line.trim())?.groupValues?.get(1)
			}
		val packageName =
			prepared.codeLines.firstNotNullOfOrNull { line ->
				PACKAGE.find(line.trim())?.groupValues?.get(1)
			} ?: ""

		val declared = mutableSetOf<String>()
		for (line in prepared.codeLines) {
			TYPE_DECLARATION.findAll(line).forEach { declared += it.groupValues[2] }
		}

		val referenced = mutableSetOf<String>()
		fingerprint.forEach { line -> CAPITALIZED.findAll(line).forEach { referenced += it.value } }
		annotations.forEach { use ->
			CAPITALIZED.findAll(use.arguments).forEach { referenced += it.value }
		}

		return AnnotationFacts(
			packageName = packageName,
			imports = imports,
			annotations = annotations,
			declaredTypeNames = declared,
			declarationFingerprint = fingerprint,
			referencedTypeNames = referenced,
		)
	}

	/**
	 * Comment-stripped source plus a structure mask.
	 *
	 * @property codeLines source lines with comments removed and string literals intact.
	 * @property maskLines the same lines with every string/char-literal character replaced
	 *   by [MASKED], so brace counting and `@` detection never fire inside a literal.
	 */
	private class Prepared(
		val codeLines: List<String>,
		val maskLines: List<String>,
	)

	/**
	 * Strips comments and builds the literal mask.
	 *
	 * @param text one source file's whole contents, Kotlin or Java.
	 * @return the code/mask line pair, or null when a block comment or a literal never closes -
	 *   a half-typed file the caller must not draw conclusions from.
	 */
	private fun prepare(text: String): Prepared? {
		val code = StringBuilder()
		val mask = StringBuilder()
		var i = 0
		var state = State.CODE
		var quote = ' '
		val n = text.length
		while (i < n) {
			val c = text[i]
			val next = if (i + 1 < n) text[i + 1] else '\u0000'
			when (state) {
				State.CODE -> {
					when {
						c == '/' && next == '/' -> {
							while (i < n && text[i] != '\n') i++
							continue
						}

						c == '/' && next == '*' -> {
							state = State.BLOCK_COMMENT
							i += 2
							continue
						}

						c == '"' && next == '"' && i + 2 < n && text[i + 2] == '"' -> {
							state = State.RAW_STRING
							code.append("\"\"\"")
							mask.append("\"\"\"")
							i += 3
							continue
						}

						c == '"' || c == '\'' -> {
							state = State.STRING
							quote = c
							code.append(c)
							mask.append(c)
							i++
							continue
						}

						else -> {
							code.append(c)
							mask.append(c)
							i++
						}
					}
				}

				State.BLOCK_COMMENT -> {
					// Newlines survive so line numbering (and thus the fingerprint's line
					// structure) is not disturbed by a multi-line comment.
					if (c == '\n') {
						code.append('\n')
						mask.append('\n')
					}
					if (c == '*' && next == '/') {
						state = State.CODE
						i += 2
						continue
					}
					i++
				}

				State.STRING -> {
					code.append(c)
					mask.append(if (c == '\n') '\n' else MASKED)
					when {
						// A line break inside a single-quoted literal means the literal was
						// never closed: bail rather than guess where it ended.
						c == '\n' -> {
							return null
						}

						c == '\\' && i + 1 < n -> {
							code.append(text[i + 1])
							mask.append(MASKED)
							i += 2
							continue
						}

						c == quote -> {
							state = State.CODE
						}
					}
					i++
				}

				State.RAW_STRING -> {
					if (c == '"' && next == '"' && i + 2 < n && text[i + 2] == '"') {
						state = State.CODE
						code.append("\"\"\"")
						mask.append("\"\"\"")
						i += 3
						continue
					}
					code.append(c)
					mask.append(if (c == '\n') '\n' else MASKED)
					i++
				}
			}
		}
		if (state != State.CODE) return null
		return Prepared(code.toString().lines(), mask.toString().lines())
	}

	private enum class State { CODE, BLOCK_COMMENT, STRING, RAW_STRING }

	/**
	 * Flags each line that sits inside a function or initializer body.
	 *
	 * Conservative: a line counts as body only when its opening line matched
	 * [FUNCTION_SIGNATURE] and contributed exactly one net brace. Class bodies, `when` blocks,
	 * property-initializer lambdas and multi-line signatures all stay in the fingerprint.
	 *
	 * @param prepared the file to walk; brace counting runs over its mask lines, and the
	 *   signature match over the code lines at the same indexes.
	 * @return one flag per line, or null when brace nesting does not balance - the caller must
	 *   not trust the file.
	 */
	private fun markFunctionBodies(prepared: Prepared): BooleanArray? {
		val result = BooleanArray(prepared.maskLines.size)
		var depth = 0
		var bodyDepth = -1
		for ((index, masked) in prepared.maskLines.withIndex()) {
			val inBody = bodyDepth >= 0
			result[index] = inBody
			val opens = masked.count { it == '{' }
			val closes = masked.count { it == '}' }
			val opensFunction =
				!inBody && opens - closes == 1 && FUNCTION_SIGNATURE.containsMatchIn(prepared.codeLines[index])
			var seenOpen = 0
			for (c in masked) {
				when (c) {
					'{' -> {
						depth++
						seenOpen++
						if (opensFunction && seenOpen == opens) bodyDepth = depth
					}

					'}' -> {
						if (bodyDepth == depth) bodyDepth = -1
						depth--
						if (depth < 0) return null
					}
				}
			}
		}
		return if (depth == 0) result else null
	}

	/**
	 * Collects every `@Name(...)` in the file, in source order.
	 *
	 * A Kotlin use-site target is split into [AnnotationUse.useSiteTarget] so imports still
	 * resolve the bare name while `@get:Json` and `@field:Json` stay distinct. Argument text
	 * keeps literals verbatim and collapses whitespace: reformatting is a no-op, a value edit
	 * is not.
	 *
	 * @param prepared the file to walk; `@` and parens are found on the mask lines while the
	 *   recorded name and argument text are cut from the code lines at the same offsets.
	 * @return the uses in source order, truncated at the first unbalanced argument list rather
	 *   than dropped, so a half-typed annotation does not hide the ones above it.
	 */
	private fun extractAnnotations(prepared: Prepared): List<AnnotationUse> {
		val mask = prepared.maskLines.joinToString("\n")
		val code = prepared.codeLines.joinToString("\n")
		val result = mutableListOf<AnnotationUse>()
		var i = 0
		while (i < mask.length) {
			if (mask[i] != '@') {
				i++
				continue
			}
			// `@` inside an identifier is not an annotation (Kotlin `a@b` labels, emails
			// inside masked strings can't reach here).
			if (i > 0 && (mask[i - 1].isLetterOrDigit() || mask[i - 1] == '_' || mask[i - 1] == '@')) {
				i++
				continue
			}
			var j = i + 1
			// Optional Kotlin use-site target, e.g. `@field:Json`.
			var target = ""
			val targetEnd = readIdentifierPath(mask, j)
			if (targetEnd > j && targetEnd < mask.length && mask[targetEnd] == ':') {
				target = code.substring(j, targetEnd)
				j = targetEnd + 1
			}
			val nameEnd = readIdentifierPath(mask, j)
			if (nameEnd == j) {
				i++
				continue
			}
			val name = code.substring(j, nameEnd)
			var k = nameEnd
			while (k < mask.length && (mask[k] == ' ' || mask[k] == '\t')) k++
			var arguments = ""
			if (k < mask.length && mask[k] == '(') {
				val close = matchParen(mask, k) ?: return result
				arguments = code.substring(k, close + 1).normalizeWhitespace()
				k = close + 1
			}
			result += AnnotationUse(name = name, arguments = arguments, useSiteTarget = target)
			i = k
		}
		return result
	}

	/**
	 * End index (exclusive) of a dotted identifier starting at [start].
	 *
	 * @param text masked source, so characters inside a literal cannot pass as an identifier.
	 * @param start index the identifier must begin at; no leading whitespace is skipped.
	 * @return one past the identifier's last character, or [start] when none begins there; a
	 *   trailing dot is left to the next token.
	 */
	private fun readIdentifierPath(
		text: String,
		start: Int,
	): Int {
		var i = start
		if (i >= text.length || !(text[i].isLetter() || text[i] == '_')) return start
		while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '.')) i++
		// A trailing dot belongs to the next token, not the name.
		while (i > start && text[i - 1] == '.') i--
		return i
	}

	/**
	 * Index of the `)` closing the `(` at [open].
	 *
	 * @param text masked source, so parentheses inside a string literal are not counted.
	 * @param open index of the opening `(`; depth is counted from that character onward.
	 * @return the matching `)` index, or null when the parentheses never balance before the
	 *   end of the file.
	 */
	private fun matchParen(
		text: String,
		open: Int,
	): Int? {
		var depth = 0
		var i = open
		while (i < text.length) {
			when (text[i]) {
				'(' -> {
					depth++
				}

				')' -> {
					depth--
					if (depth == 0) return i
				}
			}
			i++
		}
		return null
	}

	private fun String.normalizeWhitespace(): String = trim().replace(WHITESPACE, " ")

	private val WHITESPACE = Regex("\\s+")
	private val IMPORT = Regex("^import\\s+(?:static\\s+)?([\\w.]+(?:\\.\\*)?)")
	private val PACKAGE = Regex("^package\\s+([\\w.]+)")
	private val TYPE_DECLARATION =
		Regex("\\b(class|interface|object|enum|record|@interface)\\s+([A-Za-z_][A-Za-z0-9_]*)")
	private val CAPITALIZED = Regex("\\b[A-Z][A-Za-z0-9_]*\\b")

	/**
	 * Lines that unambiguously open a function/initializer body. Kotlin: `fun`, `init`,
	 * a property accessor, a secondary `constructor`. Java: a method signature - a name +
	 * parameter list + `{` with no statement/type keyword in front of it (which would make
	 * it an `if`/`for`/`class`/... block instead).
	 */
	private val FUNCTION_SIGNATURE =
		Regex(
			"(\\bfun\\s)" +
				"|(\\binit\\s*\\{)" +
				"|(\\b(get|set)\\s*\\()" +
				"|(\\bconstructor\\s*\\()" +
				"|(^\\s*(?!.*\\b(class|interface|enum|record|new|if|for|while|when|switch|catch|do|else|try|synchronized)\\b)" +
				"[\\w<>\\[\\],.\\s@]*\\b\\w+\\s*\\([^;]*\\)\\s*(throws[\\w.,\\s]+)?\\{\\s*$)",
		)
}
