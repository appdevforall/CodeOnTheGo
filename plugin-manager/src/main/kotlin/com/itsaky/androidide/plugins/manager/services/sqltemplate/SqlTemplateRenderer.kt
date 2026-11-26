package com.itsaky.androidide.plugins.manager.services.sqltemplate

import com.itsaky.androidide.plugins.services.RoomSchema
import com.itsaky.androidide.plugins.services.SqlTemplateException
import com.itsaky.androidide.plugins.services.SqlTemplateFunction
import com.itsaky.androidide.plugins.services.SqlTemplateService
import com.itsaky.androidide.templates.ProjectTemplatePebble
import io.pebbletemplates.pebble.error.PebbleException
import io.pebbletemplates.pebble.extension.AbstractExtension
import io.pebbletemplates.pebble.extension.Filter
import io.pebbletemplates.pebble.extension.Function
import io.pebbletemplates.pebble.template.EvaluationContext
import io.pebbletemplates.pebble.template.PebbleTemplate
import io.pebbletemplates.pebble.tokenParser.TokenParser
import java.io.StringWriter
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Supplier

/**
 * Renders a SQL template (ADFA-7302) with the project templates' Pebble settings ([ProjectTemplatePebble]), autoescaping
 * off, the two quoting filters, lazy variables and the caller's functions. Plain JVM, so it is unit-tested without Android.
 *
 * Pebble never looks at the interrupt flag, so two of its pieces are replaced in this engine only:
 * `for` checks for an interrupt on every pass, and `range` refuses more than [CappedRangeFunction.MAX_ELEMENTS] values.
 */
internal object SqlTemplateRenderer {
	/** The most characters a render writes, as the documentation pages' renderer allows. */
	const val MAX_RENDERED_CHARS = 4 * 1024 * 1024

	/** Names a caller cannot use: what every render adds. */
	val RESERVED_VARIABLES = setOf("project", "render_time")

	/** Pebble's own functions (`max`, `min`, `range`; `block` and `parent` are parsed specially, `i18n` is an extension). */
	private val PEBBLE_FUNCTIONS = setOf("max", "min", "range", "block", "parent", "i18n")

	const val IDENT_FILTER = "sql_ident"
	const val LITERAL_FILTER = "sql_literal"

	private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

	/**
	 * Renders [text] (the template [name], for messages). [variables] must already include the reserved ones.
	 *
	 * The render runs on a daemon thread, and the time limit interrupts it. `for` and `range` honour that, but a caller's function
	 * that ignores the interrupt keeps running on that thread after this throws (the approved behaviour: nothing can force a
	 * thread to stop), so the function's effects go on until it returns.
	 *
	 * @throws SqlTemplateException for anything the template or a function did wrong, or the time limit
	 * @throws IllegalArgumentException for a bad name, value type or time limit
	 */
	fun render(
		name: String,
		text: String,
		variables: Map<String, Any?>,
		functions: Map<String, SqlTemplateFunction>,
		timeoutMillis: Long,
	): String {
		require(timeoutMillis in 1..SqlTemplateService.MAX_TIMEOUT_MILLIS) {
			"timeoutMillis must be from 1 to ${SqlTemplateService.MAX_TIMEOUT_MILLIS}, not $timeoutMillis"
		}
		for (fn in functions.keys) {
			require(NAME.matches(fn)) { "\"$fn\" is not a valid function name: use letters, digits and _" }
			require(fn !in PEBBLE_FUNCTIONS && fn != IDENT_FILTER && fn != LITERAL_FILTER) { "\"$fn\" is a built-in name" }
		}
		for ((key, value) in variables) checkValue(value, key)

		val engine =
			ProjectTemplatePebble
				.engineBuilder()
				.autoEscaping(false)
				.cacheActive(false)
				.maxRenderedSize(MAX_RENDERED_CHARS)
				.extension(SqlExtension(functions))
				.build()
		val task =
			FutureTask {
				val template = engine.getTemplate(text)
				val out = StringWriter()
				template.evaluate(out, LazyMap(variables))
				out.toString()
			}
		val thread = Thread(task, "sql-template-render")
		thread.isDaemon = true
		thread.start()
		try {
			return task.get(timeoutMillis, TimeUnit.MILLISECONDS)
		} catch (e: TimeoutException) {
			task.cancel(true)
			throw SqlTemplateException("$name: the render did not finish within $timeoutMillis ms", null)
		} catch (e: InterruptedException) {
			task.cancel(true)
			throw e
		} catch (e: ExecutionException) {
			throw failure(name, e.cause ?: e)
		}
	}

	private fun failure(
		name: String,
		cause: Throwable,
	): SqlTemplateException {
		var t: Throwable? = cause
		while (t != null) {
			if (t is FunctionFailure) return SqlTemplateException("$name: ${t.message}", t.cause)
			if (t is QuoteFailure) return SqlTemplateException("$name: ${t.message}", t)
			t = t.cause
		}
		// PebbleException's message names the template's line; a template is its own name here (StringLoader), so leave
		// the template text out of it.
		val message =
			if (cause is PebbleException) {
				buildString {
					append(cause.pebbleMessage ?: cause.javaClass.simpleName)
					cause.lineNumber?.let { append(" (line ").append(it).append(')') }
				}
			} else {
				cause.message ?: cause.javaClass.name
			}
		return SqlTemplateException("$name: $message", cause)
	}

	/** Checks the documented types now, so a wrong one fails the call, not the render. Suppliers are checked when read. */
	private fun checkValue(
		value: Any?,
		where: String,
	) {
		when (value) {
			null, is String, is Number, is Boolean, is RoomSchema, is Supplier<*> -> {
				Unit
			}

			is Map<*, *> -> {
				for ((k, v) in value) {
					require(k is String) { "$where: a map key must be a String, not ${k?.javaClass?.name}" }
					checkValue(v, "$where.$k")
				}
			}

			is Iterable<*> -> {
				value.forEachIndexed { i, v -> checkValue(v, "$where[$i]") }
			}

			else -> {
				throw IllegalArgumentException("$where: a ${value.javaClass.name} cannot be a template value")
			}
		}
	}

	/** A value as the template sees it: a supplier is read (once, by its holder), maps and lists are wrapped in turn. */
	private fun wrap(value: Any?): Any? =
		when (value) {
			is Map<*, *> -> {
				@Suppress("UNCHECKED_CAST")
				LazyMap(value as Map<String, Any?>)
			}

			is Iterable<*> -> {
				LazyList(value.toList())
			}

			else -> {
				value
			}
		}

	/** Resolves a supplier the first time it is read and keeps the value. Used on the render thread only. */
	private class Lazy(
		private var value: Any?,
		private val where: String,
	) {
		private var read = false

		fun get(): Any? {
			if (!read) {
				val v = value
				if (v is Supplier<*>) {
					val got =
						try {
							v.get()
						} catch (e: Exception) {
							throw FunctionFailure("the value of $where failed: ${e.message ?: e.javaClass.name}", e)
						}
					try {
						checkValue(got, where)
					} catch (e: IllegalArgumentException) {
						throw FunctionFailure(e.message ?: "bad value", e)
					}
					value = wrap(got)
				} else {
					value = wrap(v)
				}
				read = true
			}
			return value
		}
	}

	/** A map whose values are read lazily; Pebble reaches a variable through [get] and [containsKey]. */
	private class LazyMap(
		source: Map<String, Any?>,
	) : AbstractMap<String, Any?>() {
		private val lazies = LinkedHashMap<String, Lazy>().apply { for ((k, v) in source) put(k, Lazy(v, k)) }

		override fun containsKey(key: String): Boolean = lazies.containsKey(key)

		override fun get(key: String): Any? = lazies[key]?.get()

		override val entries: Set<Map.Entry<String, Any?>>
			get() =
				lazies.entries
					.map { e ->
						object : Map.Entry<String, Any?> {
							override val key = e.key
							override val value get() = e.value.get()
						}
					}.toSet()
	}

	private class LazyList(
		source: List<Any?>,
	) : AbstractList<Any?>() {
		private val lazies = source.mapIndexed { i, v -> Lazy(v, "[$i]") }

		override val size get() = lazies.size

		override fun get(index: Int): Any? = lazies[index].get()
	}

	/** Carries a function's failure, with its name, past Pebble's own wrapping. */
	private class FunctionFailure(
		message: String,
		cause: Throwable?,
	) : RuntimeException(message, cause)

	/** A value a quoting filter refused. */
	private class QuoteFailure(
		message: String,
	) : RuntimeException(message)

	private class SqlExtension(
		private val callerFunctions: Map<String, SqlTemplateFunction>,
	) : AbstractExtension() {
		override fun getFilters(): Map<String, Filter> = mapOf(IDENT_FILTER to IdentFilter, LITERAL_FILTER to LiteralFilter)

		// An extension's tag and function replace Pebble's core ones of the same name (ExtensionRegistry.put).
		override fun getTokenParsers(): List<TokenParser> = listOf(InterruptibleForTokenParser())

		override fun getFunctions(): Map<String, Function> =
			callerFunctions.mapValues { (name, fn) -> CallerFunction(name, fn) as Function } + ("range" to CappedRangeFunction)
	}

	private class CallerFunction(
		private val name: String,
		private val fn: SqlTemplateFunction,
	) : Function {
		// null: Pebble passes the arguments by position, keyed "0", "1"...
		override fun getArgumentNames(): List<String>? = null

		override fun execute(
			args: Map<String, Any?>,
			self: PebbleTemplate?,
			context: EvaluationContext?,
			lineNumber: Int,
		): Any? {
			if (Thread.currentThread().isInterrupted) {
				throw FunctionFailure("$name was not called: the render was stopped", InterruptedException())
			}
			val named = args.keys.filterNot { key -> key.all { it.isDigit() } }
			if (named.isNotEmpty()) {
				throw FunctionFailure("$name(...) at line $lineNumber: named arguments (${named.joinToString()}) are not supported", null)
			}
			val positional = (0 until args.size).map { args[it.toString()] }
			val result =
				try {
					fn.call(positional)
				} catch (e: Exception) {
					throw FunctionFailure("$name(...) at line $lineNumber failed: ${e.message ?: e.javaClass.name}", e)
				}
			try {
				checkValue(result, "$name(...)")
			} catch (e: IllegalArgumentException) {
				throw FunctionFailure(e.message ?: "bad value", e)
			}
			// A supplier is read here as a variable's is: once, with the value checked.
			return Lazy(result, "$name(...)").get()
		}
	}

	private object IdentFilter : Filter {
		override fun getArgumentNames(): List<String>? = null

		override fun apply(
			input: Any?,
			args: Map<String, Any?>?,
			self: PebbleTemplate?,
			context: EvaluationContext?,
			lineNumber: Int,
		): Any = quoteIdentifier(input, lineNumber)
	}

	private object LiteralFilter : Filter {
		override fun getArgumentNames(): List<String>? = null

		override fun apply(
			input: Any?,
			args: Map<String, Any?>?,
			self: PebbleTemplate?,
			context: EvaluationContext?,
			lineNumber: Int,
		): Any = quoteLiteral(input, lineNumber)
	}

	/** `"name"`, with each `"` doubled. Only a string: a number or null as a name is a template mistake. */
	fun quoteIdentifier(
		input: Any?,
		lineNumber: Int = 0,
	): String {
		if (input !is String) throw QuoteFailure("$IDENT_FILTER at line $lineNumber needs a string, not ${describe(input)}")
		// SQLite reads SQL text only up to a NUL, so one would cut the statement short.
		if ('\u0000' in input) throw QuoteFailure("$IDENT_FILTER at line $lineNumber: the name holds a NUL character")
		return "\"" + input.replace("\"", "\"\"") + "\""
	}

	/** A SQL literal that reads back as [input]. */
	fun quoteLiteral(
		input: Any?,
		lineNumber: Int = 0,
	): String =
		when (input) {
			null -> {
				"NULL"
			}

			is String -> {
				if ('\u0000' in input) {
					throw QuoteFailure("$LITERAL_FILTER at line $lineNumber: the string holds a NUL character")
				}
				"'" + input.replace("'", "''") + "'"
			}

			is Boolean -> {
				if (input) "1" else "0"
			}

			is Long, is Int, is Short, is Byte, is BigInteger -> {
				signed(input.toString())
			}

			is Double, is Float -> {
				val d = (input as Number).toDouble()
				if (d.isNaN() || d.isInfinite()) throw QuoteFailure("$LITERAL_FILTER at line $lineNumber: $d has no SQL literal")
				// Kotlin writes 1.0E10, which SQLite reads as a REAL; a whole number keeps its ".0", so it stays a REAL.
				signed(d.toString())
			}

			is BigDecimal -> {
				signed(input.toString())
			}

			else -> {
				throw QuoteFailure("$LITERAL_FILTER at line $lineNumber cannot quote ${describe(input)}")
			}
		}

	/** A negative number in parentheses: spliced after a minus, a bare `-5` would read as the comment `--`. */
	private fun signed(number: String) = if (number.startsWith("-")) "($number)" else number

	private fun describe(value: Any?) = if (value == null) "null" else "a ${value.javaClass.simpleName}"
}
