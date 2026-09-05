package com.itsaky.androidide.lsp.java.refactor

import com.itsaky.androidide.lsp.refactor.TextSpan
import openjdk.source.tree.BlockTree
import openjdk.source.tree.CompilationUnitTree
import openjdk.source.tree.ExpressionStatementTree
import openjdk.source.tree.ExpressionTree
import openjdk.source.tree.MethodInvocationTree
import openjdk.source.tree.StatementTree
import openjdk.source.tree.Tree
import openjdk.source.util.JavacTask
import openjdk.source.util.SourcePositions
import openjdk.source.util.TreePath
import openjdk.source.util.TreePathScanner

/**
 * What a selection resolved to. Exactly two kinds, which is the whole reason the hard cases never
 * arise: a selection covering half an `if` and half its `else`, or straddling a lambda boundary, is
 * neither, and is declined by construction rather than filtered out later.
 */
sealed interface ExtractionRegion {
	/** The region's covering span in the file's text. */
	val span: TextSpan

	/** The path the analysis walks up from to find the anchor member. */
	val path: TreePath

	/**
	 * One expression at the cursor. A cursor resolves to several nested ones, innermost first, each a
	 * region in its own right, and the user picks between them in the sheet.
	 */
	data class Expression(
		override val path: TreePath,
		override val span: TextSpan,
	) : ExtractionRegion

	/** One or more sibling statements in a single [BlockTree]. */
	data class Statements(
		val statements: List<StatementTree>,
		override val path: TreePath,
		override val span: TextSpan,
	) : ExtractionRegion
}

/** Every region a selection offers: 1..MAX_CANDIDATES expressions, or exactly one statement range. */
fun resolveExtractionRegions(
	task: JavacTask,
	root: CompilationUnitTree,
	positions: SourcePositions,
	fileText: String,
	selectionStart: Int,
	selectionEnd: Int,
): List<ExtractionRegion> {
	val (start, end) = trimToCode(fileText, selectionStart, selectionEnd) ?: return emptyList()
	if (start == end) return expressionRegions(task, root, positions, fileText, selectionStart, selectionEnd)

	val statements =
		snapToStatements(root, positions, fileText, start, end)
			?: return expressionRegions(task, root, positions, fileText, selectionStart, selectionEnd)

	// A selection sitting strictly inside one statement points at something narrower than the statement,
	// so the expression path answers what the user actually selected. A near-miss drag that finds no
	// legal expression there still gets the statement, rather than being refused for landing short.
	val only = statements.statements.singleOrNull()
	if (only != null) {
		val statementSpan = spanOf(root, positions, only)
		if (statementSpan != null && (start > statementSpan.start || end < statementSpan.end)) {
			expressionRegions(task, root, positions, fileText, selectionStart, selectionEnd)
				.takeIf { it.isNotEmpty() }
				?.let { return it }
		}
	}

	return listOf(statements)
}

private fun expressionRegions(
	task: JavacTask,
	root: CompilationUnitTree,
	positions: SourcePositions,
	fileText: String,
	selectionStart: Int,
	selectionEnd: Int,
): List<ExtractionRegion> =
	candidateExpressionsAt(task, root, fileText, selectionStart, selectionEnd, hoisted = false)
		.paths
		.mapNotNull { path ->
			spanOf(root, positions, path.leaf)?.let { ExtractionRegion.Expression(path, it) }
		}

/**
 * The whole statements `[start, end)` touches, when they are siblings in one [BlockTree].
 *
 * Null when the two ends land in different blocks, which is what rejects a selection spanning an `if`
 * body and the code after it without needing to reason about the constructs involved.
 */
private fun snapToStatements(
	root: CompilationUnitTree,
	positions: SourcePositions,
	fileText: String,
	start: Int,
	end: Int,
): ExtractionRegion.Statements? {
	// end > start is guaranteed by the start == end early-return in resolveExtractionRegions.
	val first = statementContaining(root, positions, start) ?: return null
	val last = statementContaining(root, positions, end - 1) ?: return null

	val block = first.parentPath?.leaf as? BlockTree ?: return null
	if (last.parentPath?.leaf !== block) return null
	if (!isExtractionPosition(first, hoisted = false)) return null

	val statements = block.statements
	val from = statements.indexOfFirst { it === first.leaf }
	val to = statements.indexOfFirst { it === last.leaf }
	if (from < 0 || to < from) return null

	val selected = statements.subList(from, to + 1).toList()
	// `this(...)` / `super(...)` cannot move into a method: nothing may precede a delegation, and a
	// constructor call is legal only in a constructor. The expression path refuses these through
	// isExtractionPosition, which walks *ancestors* and so never sees a delegation that is the statement.
	if (selected.any { it is ExpressionStatementTree && it.expression.isConstructorDelegation() }) return null
	val firstSpan = spanOf(root, positions, selected.first()) ?: return null
	val lastSpan = spanOf(root, positions, selected.last()) ?: return null

	return ExtractionRegion.Statements(
		statements = selected,
		path = first,
		span = TextSpan(firstSpan.start, absorbTrailingSemicolon(fileText, lastSpan.end)),
	)
}

private fun ExpressionTree.isConstructorDelegation(): Boolean = this is MethodInvocationTree && isConstructorDelegation(this)

/**
 * javac's end position for a statement does not reliably reach past its own `;`, and a region that
 * stops short of one leaves a stray `;` behind at the call site. Absorbing a `;` that is already inside
 * the span is impossible, so this is a no-op wherever it is not needed.
 */
internal fun absorbTrailingSemicolon(
	fileText: String,
	end: Int,
): Int = if (end < fileText.length && fileText[end] == ';') end + 1 else end

/**
 * The narrowest statement containing [offset] that is a direct statement child of a block. Null for a
 * position that is not inside one, such as a comment or a class body.
 *
 * Deliberately *not* a walk up from [deepestPathAt]: `analyze()` synthesises a default constructor for
 * a class that declares none, and its generated `super()` carries the class declaration's own start
 * position with **zero width**. That synthetic node is narrower than everything real at that offset, so
 * the walk up from it lands inside the synthetic constructor's block -- and a local class declaration
 * then reported a different block from its own closing brace, which read as a cross-block selection.
 * Requiring positive width excludes every synthetic node without needing to recognise one.
 */
private fun statementContaining(
	root: CompilationUnitTree,
	positions: SourcePositions,
	offset: Int,
): TreePath? {
	var best: TreePath? = null
	var bestWidth = Int.MAX_VALUE

	val scanner =
		object : TreePathScanner<Unit, Unit>() {
			override fun scan(
				tree: Tree?,
				p: Unit?,
			): Unit? {
				if (tree == null) return null
				// currentPath still holds the parent here, which is exactly the block being asked about.
				if (tree is StatementTree && currentPath?.leaf is BlockTree) {
					val span = spanOf(root, positions, tree)
					if (span != null && span.length > 0 && offset >= span.start && offset < span.end && span.length < bestWidth) {
						best = TreePath(currentPath, tree)
						bestWidth = span.length
					}
				}
				return super.scan(tree, p)
			}
		}
	scanner.scan(TreePath(root), null)
	return best
}
