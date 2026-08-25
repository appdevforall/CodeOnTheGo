package com.itsaky.androidide.lsp.java.refactor

import com.itsaky.androidide.lsp.refactor.RewriteSpan
import jdkx.tools.JavaFileManager
import jdkx.tools.JavaFileObject
import jdkx.tools.SimpleJavaFileObject
import openjdk.source.tree.CompilationUnitTree
import openjdk.source.util.JavacTask
import openjdk.source.util.Trees
import openjdk.tools.javac.api.JavacTool
import java.net.URI

/**
 * One attributed compile of a source string, with no project model and no tooling API.
 *
 * The Robolectric `JavaLSPTest` harness cannot serve this layer: it boots the Gradle tooling API in a
 * separate process, which needs a resolved project and does not start at all in some environments.
 * Everything the extract-variable analysis needs is a `CompilationUnitTree` plus `Trees`, and
 * `JavacTool` supplies both directly, so these tests are hermetic and run in milliseconds.
 */
class JavacFixture(
	val text: String,
	fileName: String = "Fixture.java",
) : AutoCloseable {
	val task: JavacTask
	val root: CompilationUnitTree

	// The manager has to outlive the task -- javac reads through it lazily -- so it is held here and
	// closed with the fixture rather than around the compile.
	private val fileManager: JavaFileManager

	val trees: Trees get() = Trees.instance(task)

	init {
		val tool = JavacTool.create()
		fileManager = tool.getStandardFileManager(null, null, null)
		val source =
			object : SimpleJavaFileObject(URI.create("string:///$fileName"), JavaFileObject.Kind.SOURCE) {
				override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = text
			}

		task = tool.getTask(null, fileManager, null, listOf("-proc:none", "-g"), null, listOf(source))
		root = task.parse().first()
		// Attribution is what fills in types and elements; without it getTypeMirror answers nothing.
		task.analyze()
	}

	override fun close() = fileManager.close()

	/**
	 * The offset immediately after [prefix]'s first occurrence.
	 *
	 * Prefer this over `indexOf(x) + n`: one character into `a + b * c` sits inside `a` and resolves to
	 * that identifier, not to the binary expression, so a delta silently tests a different candidate
	 * than the case name claims.
	 */
	fun cursorAfter(prefix: String): Int {
		val index = text.indexOf(prefix)
		require(index >= 0) { "the fixture contains no '$prefix'" }
		return index + prefix.length
	}

	/** The plan for a cursor placed just after [prefix]. */
	fun planAfter(
		prefix: String,
		documentVersion: Int = 1,
	): ExtractionPlan {
		val cursor = cursorAfter(prefix)
		return buildExtractionPlan(task, root, text, cursor, cursor, documentVersion)
	}

	/**
	 * The file as it reads after extracting at [prefix] into [name], picking the rung labelled [scope].
	 *
	 * This is the assertion that actually matters for the rewrite bugs: a span or ordering error shows up
	 * as broken source here, where comparing a `RewriteSpan` in isolation hides it.
	 */
	fun applyAfter(
		prefix: String,
		name: String,
		scope: ScopeLabel? = null,
		replaceAll: Boolean = false,
	): String {
		val plan = planAfter(prefix)
		val candidate = plan.candidates.firstOrNull() ?: error("no candidate after '$prefix'")
		val option =
			if (scope == null) {
				candidate.scopes.first()
			} else {
				candidate.scopes.firstOrNull { it.label == scope }
					?: error("no scope $scope in ${candidate.scopes.map { it.label }}")
			}
		val rewrite =
			buildExtractVariableRewrite(
				fileText = plan.fileText,
				candidateSpan = candidate.span,
				declaredType = candidate.declaredType,
				scope = option,
				name = name,
				replaceAll = replaceAll,
			) ?: error("no rewrite for '$prefix' in scope '${option.label}'")
		return text.substring(0, rewrite.span.start) + rewrite.newText + text.substring(rewrite.span.end)
	}
}

/** Whether [source] compiles on its own, which is what most of these findings are really about. */
fun compiles(source: String): Boolean {
	val tool = JavacTool.create()
	val file =
		object : SimpleJavaFileObject(URI.create("string:///Probe.java"), JavaFileObject.Kind.SOURCE) {
			override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source
		}
	val diagnostics = mutableListOf<String>()
	// Nothing here outlives analyze(), so the manager is scoped to the probe rather than leaked per call.
	tool.getStandardFileManager(null, null, null).use { fileManager ->
		val task =
			tool.getTask(
				null,
				fileManager,
				{ d -> if (d.kind.name == "ERROR") diagnostics += d.getMessage(null) },
				listOf("-proc:none"),
				null,
				listOf(file),
			)
		task.analyze()
	}
	if (diagnostics.isNotEmpty()) println("  compile errors: $diagnostics")
	return diagnostics.isEmpty()
}
