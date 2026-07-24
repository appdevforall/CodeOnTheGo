package org.appdevforall.cotg.quickbuild.daemon.compile

import com.sun.source.tree.ClassTree
import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.MethodTree
import com.sun.source.tree.Tree
import com.sun.source.tree.VariableTree
import com.sun.source.util.JavacTask
import java.io.File
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import javax.lang.model.element.Modifier
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider

/**
 * The ABI (not the implementation) of the project's `.java` sources, so a Java edit only
 * costs a Kotlin recompile when it could actually change Kotlin bytecode.
 *
 * kotlinc reads same-module `.java` files as raw sources (see [IncrementalCompiler]), but
 * the Build Tools API's incremental engine has no dependency tracking over them - it only
 * snapshots jar classpath entries. Without a Java-side signal the only sound choice is to
 * recompile every Kotlin file whenever any `.java` file changes. That is correct but
 * costly, and the great majority of Java edits are method-body edits that no Kotlin class
 * can observe.
 *
 * So: fingerprint each `.java` file's declarations, ignoring method bodies. Two things are
 * deliberately IN the fingerprint even though they look like implementation:
 * - the initializer of a compile-time constant field, because Kotlin inlines Java
 *   constants into its callers' bytecode - a changed value is an ABI change;
 * - annotations, because they reach Kotlin's resolution (nullability in particular).
 *
 * Parsing is javac's own parser via [JavacTask.parse] - syntax only, no symbol resolution
 * and no classpath, so it costs single-digit milliseconds on a project-sized source set
 * and cannot fail over an unresolved cross-language reference (the very thing that makes
 * the two-pass necessary). Anything unparseable yields `null`, which callers must treat as
 * "assume the ABI changed".
 */
object JavaSourceAbi {
	/**
	 * @property fingerprint hash over the file's declarations, method bodies excluded.
	 * @property declaredTypeNames every type simple name the file declares, nested
	 *   included - the names a Kotlin source would have to write to reference it.
	 */
	data class FileAbi(
		val fingerprint: String,
		val declaredTypeNames: Set<String>,
	)

	/** Per-file ABI, or null if any file could not be parsed (callers must then stay conservative). */
	fun snapshot(javaSources: List<File>): Map<File, FileAbi>? {
		if (javaSources.isEmpty()) return emptyMap()
		val compiler = ToolProvider.getSystemJavaCompiler() ?: return null
		val collector = DiagnosticCollector<JavaFileObject>()
		return try {
			compiler.getStandardFileManager(collector, Locale.ROOT, StandardCharsets.UTF_8).use { manager ->
				val units = manager.getJavaFileObjectsFromFiles(javaSources)
				val task =
					compiler.getTask(StringWriter(), manager, collector, listOf("-proc:none"), null, units)
						as? JavacTask ?: return null
				val byPath = javaSources.associateBy { it.absolutePath }
				val result = HashMap<File, FileAbi>()
				for (unit in task.parse()) {
					val file = byPath[File(unit.sourceFile.toUri()).absolutePath] ?: continue
					result[file] = unit.toAbi()
				}
				// A file javac declined to hand back was not parsed; do not claim to know its ABI.
				if (result.size != javaSources.size) null else result
			}
		} catch (e: Exception) {
			null
		}
	}

	/**
	 * Simple names of every type whose ABI differs between [previous] and [current] -
	 * added, removed, and modified files alike, taking the union of the old and new names
	 * so a rename or deletion still names the type Kotlin sources may still reference.
	 */
	fun changedTypeNames(
		previous: Map<File, FileAbi>,
		current: Map<File, FileAbi>,
	): Set<String> {
		val changed = HashSet<String>()
		for ((file, abi) in current) {
			val before = previous[file]
			if (before == null || before.fingerprint != abi.fingerprint) {
				changed += abi.declaredTypeNames
				before?.let { changed += it.declaredTypeNames }
			}
		}
		for ((file, abi) in previous) {
			if (file !in current) changed += abi.declaredTypeNames
		}
		return changed
	}

	private fun CompilationUnitTree.toAbi(): FileAbi {
		val text = StringBuilder()
		val names = HashSet<String>()
		text.append("package ").append(packageName?.toString() ?: "").append('\n')
		for (decl in typeDecls) {
			if (decl is ClassTree) decl.render(text, names, prefix = "")
		}
		return FileAbi(sha256(text.toString()), names)
	}

	private fun ClassTree.render(
		out: StringBuilder,
		names: MutableSet<String>,
		prefix: String,
	) {
		val name = simpleName.toString()
		names += name
		val qualified = if (prefix.isEmpty()) name else "$prefix.$name"
		out
			.append("type ")
			.append(qualified)
			.append(' ')
			.append(modifiers.toString().trim())
			.append(" typeparams=")
			.append(typeParameters.joinToString(",") { it.toString() })
			.append(" extends=")
			.append(extendsClause?.toString() ?: "")
			.append(" implements=")
			.append(implementsClause.joinToString(",") { it.toString() })
			.append('\n')
		// Interface, annotation and enum members are implicitly constant even with no
		// modifiers written, so whether an initializer is ABI depends on the owner.
		val constantByDefault = kind != Tree.Kind.CLASS
		for (member in members) {
			when (member) {
				is ClassTree -> member.render(out, names, qualified)
				is MethodTree -> out.append(member.renderSignature(qualified)).append('\n')
				is VariableTree -> out.append(member.renderSignature(qualified, constantByDefault)).append('\n')
				// Initializer blocks and empty declarations carry no ABI.
				else -> Unit
			}
		}
	}

	/** Everything about a method except its body - that is the whole point. */
	private fun MethodTree.renderSignature(owner: String): String =
		buildString {
			append("method ").append(owner).append('#').append(name)
			append(' ').append(modifiers.toString().trim())
			append(" typeparams=").append(typeParameters.joinToString(",") { it.toString() })
			append(" returns=").append(returnType?.toString() ?: "")
			append(" params=").append(parameters.joinToString(",") { it.type.toString() + " " + it.name })
			append(" throws=").append(throws.joinToString(",") { it.toString() })
			// An annotation member's default IS its ABI.
			append(" default=").append(defaultValue?.toString() ?: "")
		}

	/**
	 * A field's declaration, plus its initializer when the field is a compile-time
	 * constant: Kotlin bakes `static final` constant values into calling bytecode, so a
	 * changed value is an ABI change even though nothing about the signature moved. An
	 * ordinary instance field's initializer is implementation, and is left out.
	 */
	private fun VariableTree.renderSignature(
		owner: String,
		constantByDefault: Boolean,
	): String =
		buildString {
			append("field ").append(owner).append('#').append(name)
			append(' ').append(modifiers.toString().trim())
			append(" type=").append(type?.toString() ?: "")
			val declaredConstant =
				modifiers.flags.contains(Modifier.STATIC) && modifiers.flags.contains(Modifier.FINAL)
			if (declaredConstant || constantByDefault) append(" const=").append(initializer?.toString() ?: "")
		}

	private fun sha256(value: String): String {
		val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
		return digest.joinToString("") { "%02x".format(it) }
	}
}
