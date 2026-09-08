package com.itsaky.androidide.editor.language.outline

import android.content.Context
import com.itsaky.androidide.editor.language.treesitter.predicates.AnyOfPredicate
import com.itsaky.androidide.editor.language.treesitter.predicates.EqualPredicate
import com.itsaky.androidide.editor.language.treesitter.predicates.MatchPredicate
import com.itsaky.androidide.editor.language.treesitter.predicates.NotEqualPredicate
import com.itsaky.androidide.editor.language.treesitter.predicates.NotMatchPredicate
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.treesitter.TSLanguage
import com.itsaky.androidide.treesitter.TSNode
import com.itsaky.androidide.treesitter.TSParser
import com.itsaky.androidide.treesitter.TSQuery
import com.itsaky.androidide.treesitter.TSQueryCursor
import com.itsaky.androidide.treesitter.TSQueryError
import com.itsaky.androidide.treesitter.TSTree
import com.itsaky.androidide.treesitter.TreeSitter
import com.itsaky.androidide.treesitter.api.safeExecQueryCursor
import com.itsaky.androidide.treesitter.java.TSLanguageJava
import com.itsaky.androidide.treesitter.kotlin.TSLanguageKotlin
import com.itsaky.androidide.treesitter.xml.TSLanguageXml
import io.github.rosemoe.sora.editor.ts.predicate.Predicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class TreeSitterOutlineProvider(
	context: Context,
) : OutlineProvider {
	private val appContext = context.applicationContext

	private val queriesByType = ConcurrentHashMap<String, LanguageQueries>()

	private val predicates =
		listOf(
			MatchPredicate,
			NotMatchPredicate,
			EqualPredicate,
			NotEqualPredicate,
			AnyOfPredicate,
		)

	private class LanguageQueries(
		val language: TSLanguage,
		val query: TSQuery,
		val predicator: Predicator,
	)

	companion object {
		private const val BASE_PATH = "editor/treesitter"

		private val TYPE_BY_EXTENSION =
			mapOf(
				"java" to "java",
				"kt" to "kt",
				"kts" to "kt",
				"xml" to "xml",
			)

		private const val SYMBOL_CAPTURE_PREFIX = "symbol."
		private const val NAME_CAPTURE = "name"
		private const val DETAIL_CAPTURE = "detail"

		init {
			TreeSitter.loadLibrary()
		}
	}

	override fun supports(fileExtension: String): Boolean = fileExtension.lowercase() in TYPE_BY_EXTENSION

	override suspend fun outlineOf(
		fileExtension: String,
		text: CharSequence,
	): List<OutlineSymbol> =
		withContext(Dispatchers.Default) {
			val type =
				TYPE_BY_EXTENSION[fileExtension.lowercase()]
					?: throw IllegalArgumentException("No outline support for file extension '$fileExtension'")
			val queries = queriesByType.computeIfAbsent(type) { loadQueries(it) }
			val source = text.toString()
			TSParser.create().use { parser ->
				parser.language = queries.language
				parser.parseString(source).use { tree ->
					extract(queries, tree, source)
				}
			}
		}

	private fun loadQueries(type: String): LanguageQueries {
		val scm =
			appContext.assets
				.open("$BASE_PATH/$type/outline.scm")
				.reader()
				.use { it.readText() }
		val language = languageFor(type)
		val query = TSQuery.create(language, scm)
		if (query.errorType != TSQueryError.None) {
			throw IllegalArgumentException(
				"outline.scm for '$type' failed to parse: ${query.errorType.name} at byte offset ${query.errorOffset}",
			)
		}
		return LanguageQueries(language, query, Predicator(query))
	}

	private fun languageFor(type: String): TSLanguage =
		when (type) {
			"java" -> TSLanguageJava.getInstance()
			"kt" -> TSLanguageKotlin.getInstance()
			"xml" -> TSLanguageXml.getInstance()
			else -> throw IllegalArgumentException("Unknown outline language type '$type'")
		}

	private fun extract(
		queries: LanguageQueries,
		tree: TSTree,
		source: String,
	): List<OutlineSymbol> {
		val rawByName = LinkedHashMap<Pair<Int, Int>, RawOutlineSymbol>()
		val patternByName = HashMap<Pair<Int, Int>, Int>()
		TSQueryCursor.create().use { cursor ->
			cursor.safeExecQueryCursor(
				query = queries.query,
				tree = tree,
				recycleNodeAfterUse = true,
				debugName = "TreeSitterOutlineProvider.extract()",
			) { match ->
				if (!queries.predicator.doPredicate(predicates, source, match)) {
					return@safeExecQueryCursor
				}
				var kind: OutlineSymbolKind? = null
				var symbolStartByte = 0
				var symbolRange: Range? = null
				var name: String? = null
				var nameStartByte = -1
				var nameEndByte = -1
				var selectionRange: Range? = null
				var detail: String? = null
				match.captures.forEach { capture ->
					val captureName = queries.query.getCaptureNameForId(capture.index)
					val node = capture.node
					when {
						captureName.startsWith(SYMBOL_CAPTURE_PREFIX) -> {
							kind = kindOf(captureName.removePrefix(SYMBOL_CAPTURE_PREFIX))
							symbolStartByte = node.startByte
							symbolRange = rangeOf(node)
						}

						captureName == NAME_CAPTURE -> {
							name = textOf(node, source)
							nameStartByte = node.startByte
							nameEndByte = node.endByte
							selectionRange = rangeOf(node)
						}

						captureName == DETAIL_CAPTURE -> {
							detail = textOf(node, source).trim('"', '\'')
						}
					}
				}
				val resolvedKind = kind ?: return@safeExecQueryCursor
				val range = symbolRange ?: return@safeExecQueryCursor
				val resolvedName = name ?: defaultNameFor(resolvedKind) ?: return@safeExecQueryCursor
				val raw =
					RawOutlineSymbol(
						name = resolvedName,
						detail = detail,
						kind = resolvedKind,
						range = range,
						selectionRange = selectionRange ?: Range(range.start, range.start),
					)
				val key = if (nameStartByte >= 0) nameStartByte to nameEndByte else symbolStartByte to symbolStartByte
				val existing = rawByName[key]
				val existingPattern = patternByName[key]
				val preferred =
					when {
						existing == null -> true
						existing.detail == null && raw.detail != null -> true
						existing.detail != null && raw.detail == null -> false
						else -> match.patternIndex < existingPattern!!
					}
				if (preferred) {
					rawByName[key] = raw
					patternByName[key] = match.patternIndex
				}
			}
		}
		return OutlineTreeBuilder.build(rawByName.values.toList())
	}

	private fun kindOf(suffix: String): OutlineSymbolKind {
		val constantName = suffix.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()
		return OutlineSymbolKind.entries.find { it.name == constantName }
			?: throw IllegalArgumentException("Unknown outline symbol kind capture '@symbol.$suffix'")
	}

	private fun defaultNameFor(kind: OutlineSymbolKind): String? =
		when (kind) {
			OutlineSymbolKind.COMPANION -> "companion object"
			OutlineSymbolKind.CONSTRUCTOR -> "constructor"
			else -> null
		}

	private fun textOf(
		node: TSNode,
		source: String,
	): String = source.substring(node.startByte / 2, node.endByte / 2)

	private fun rangeOf(node: TSNode): Range {
		val start = node.startPoint
		val end = node.endPoint
		return Range(
			Position(start.row, start.column / 2, node.startByte / 2),
			Position(end.row, end.column / 2, node.endByte / 2),
		)
	}
}
