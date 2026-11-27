package com.itsaky.androidide.plugins.manager.services.sqltemplate

import io.pebbletemplates.pebble.error.ParserException
import io.pebbletemplates.pebble.error.PebbleException
import io.pebbletemplates.pebble.extension.AbstractNodeVisitor
import io.pebbletemplates.pebble.extension.Function
import io.pebbletemplates.pebble.extension.NodeVisitor
import io.pebbletemplates.pebble.extension.NodeVisitorFactory
import io.pebbletemplates.pebble.lexer.Token
import io.pebbletemplates.pebble.node.AbstractRenderableNode
import io.pebbletemplates.pebble.node.BodyNode
import io.pebbletemplates.pebble.node.ForNode
import io.pebbletemplates.pebble.node.RenderableNode
import io.pebbletemplates.pebble.node.expression.Expression
import io.pebbletemplates.pebble.parser.Parser
import io.pebbletemplates.pebble.template.EvaluationContext
import io.pebbletemplates.pebble.template.EvaluationContextImpl
import io.pebbletemplates.pebble.template.PebbleTemplate
import io.pebbletemplates.pebble.template.PebbleTemplateImpl
import io.pebbletemplates.pebble.tokenParser.TokenParser
import java.io.Writer
import java.math.BigInteger

/** Thrown inside a render whose thread was interrupted (the time limit passed, or the caller was). */
internal class RenderStopped : RuntimeException("the render was stopped")

/**
 * Puts an interrupt check at the start of every body of every template the engine compiles, included ones too. Besides
 * `for`, a template repeats work only by rendering a body again: a macro that calls itself, a template that includes
 * itself (a template is its own text, so it can) or `block()`. A recursive macro can make 2^40 calls that write nothing,
 * so neither the output cap nor the stack ends it, and Pebble never looks at the interrupt flag.
 */
internal object InterruptCheckVisitorFactory : NodeVisitorFactory {
	override fun createVisitor(template: PebbleTemplate): NodeVisitor = Visitor(template as PebbleTemplateImpl)

	private class Visitor(
		template: PebbleTemplateImpl,
	) : AbstractNodeVisitor(template) {
		override fun visit(node: BodyNode) {
			// The parser's bodies hold an ArrayList; the only fixed-size one, embed's, is made while rendering and never visited.
			node.children.add(0, InterruptCheck)
			super.visit(node)
		}
	}

	private object InterruptCheck : AbstractRenderableNode() {
		override fun render(
			self: PebbleTemplateImpl?,
			writer: Writer?,
			context: EvaluationContextImpl?,
		) {
			if (Thread.currentThread().isInterrupted) throw RenderStopped()
		}

		override fun accept(visitor: NodeVisitor) {
			visitor.visit(this)
		}
	}
}

/**
 * Pebble's `for` tag, except that the loop checks for an interrupt on every element, so a render past its time limit ends
 * instead of looping on (Pebble itself never looks at the interrupt flag). Only the SQL engine registers it: the project
 * templates keep Pebble's own tag.
 */
internal class InterruptibleForTokenParser : TokenParser {
	override fun getTag(): String = "for"

	override fun parse(
		token: Token,
		parser: Parser,
	): RenderableNode {
		val stream = parser.stream
		val lineNumber = token.lineNumber
		stream.next()
		val variable = parser.expressionParser.parseNewVariableName()
		stream.expect(Token.Type.NAME, "in")
		val iterable = parser.expressionParser.parseExpression()
		stream.expect(Token.Type.EXECUTE_END)
		val body = parser.subparse { it.test(Token.Type.NAME, "else", "endfor") }
		var elseBody: io.pebbletemplates.pebble.node.BodyNode? = null
		if (stream.current().test(Token.Type.NAME, "else")) {
			stream.next()
			stream.expect(Token.Type.EXECUTE_END)
			elseBody = parser.subparse { it.test(Token.Type.NAME, "endfor") }
		}
		if (stream.current().value == null) {
			throw ParserException(
				null,
				"Unexpected end of template. Pebble was looking for the \"endfor\" tag",
				stream.current().lineNumber,
				stream.filename,
			)
		}
		stream.next()
		stream.expect(Token.Type.EXECUTE_END)
		return ForNode(lineNumber, variable, InterruptibleIterable(iterable), body, elseBody)
	}

	/** Evaluates [inner], handing a collection or map to the loop as a collection that checks for an interrupt as it is walked. */
	private class InterruptibleIterable(
		private val inner: Expression<*>,
	) : Expression<Any?> {
		override fun evaluate(
			self: PebbleTemplateImpl?,
			context: EvaluationContextImpl?,
		): Any? =
			when (val value = inner.evaluate(self, context)) {
				is Collection<*> -> Checked(value)
				is Map<*, *> -> Checked(value.entries)
				else -> value
			}

		override fun getLineNumber(): Int = inner.lineNumber

		override fun accept(visitor: NodeVisitor?) {
			inner.accept(visitor)
		}
	}

	private class Checked(
		private val source: Collection<*>,
	) : AbstractCollection<Any?>() {
		override val size get() = source.size

		override fun iterator(): Iterator<Any?> {
			val it = source.iterator()
			return object : Iterator<Any?> {
				override fun hasNext() = it.hasNext()

				override fun next(): Any? {
					if (Thread.currentThread().isInterrupted) throw RenderStopped()
					return it.next()
				}
			}
		}
	}
}

/**
 * Pebble's `range`, but it refuses to build more than [MAX_ELEMENTS] values: Pebble's builds the whole list, boxed, before the
 * template can look at it, so `range(1, 9999999999)` would exhaust the app's memory. Same arguments (`start`, `end`,
 * `increment`), steps, negative steps and one-character strings as Pebble's.
 */
internal object CappedRangeFunction : Function {
	/** The most values one `range` call returns. */
	const val MAX_ELEMENTS = 100_000

	private val NAMES = listOf("start", "end", "increment")

	override fun getArgumentNames(): List<String> = NAMES

	override fun execute(
		args: Map<String, Any?>,
		self: PebbleTemplate?,
		context: EvaluationContext?,
		lineNumber: Int,
	): Any {
		val start = args["start"]
		val end = args["end"]
		val increment = args["increment"] ?: 1L

		fun fail(message: String): Nothing = throw PebbleException(null, message, lineNumber, self?.name)
		if (increment !is Number) fail("The increment of the range function must be a number $increment")
		val step = increment.toLong()
		if (step == 0L) fail("The increment of the range function must be different than 0")

		if (start is Number && end is Number) {
			val from = start.toLong()
			val to = end.toLong()
			val (lo, hi) = if (step > 0) from to to else to to from
			val span = BigInteger.valueOf(hi).subtract(BigInteger.valueOf(lo))
			if (span.signum() < 0) return emptyList<Long>()
			val count = span.divide(BigInteger.valueOf(step).abs()).add(BigInteger.ONE)
			if (count > BigInteger.valueOf(MAX_ELEMENTS.toLong())) {
				fail("range($from, $to, $step) would have $count values; the most is $MAX_ELEMENTS")
			}
			val out = ArrayList<Long>(count.toInt())
			var i = from
			repeat(count.toInt()) {
				out.add(i)
				i += step
			}
			return out
		}
		if (start is String && end is String) {
			if (start.length != 1 || end.length != 1) {
				fail("Arguments of range function must be of type Number or String with a length of 1")
			}
			val out = ArrayList<Char>()
			// A step beyond the char range ends the loop after one value either way; the clamp only keeps the Int from wrapping.
			val charStep = step.coerceIn(-0x10000L, 0x10000L).toInt()
			var i = start[0].code
			val last = end[0].code
			while (if (charStep > 0) i <= last else i >= last) {
				out.add(i.toChar())
				i += charStep
			}
			return out
		}
		fail("Arguments of range function must be of type Number or String with a length of 1")
	}
}
