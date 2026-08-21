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
 * Fingerprints the ABI - not the implementation - of the project's `.java` sources, so a
 * Java edit only costs a Kotlin recompile when it could change Kotlin bytecode.
 *
 * kotlinc reads same-module `.java` files as raw sources (see [IncrementalCompiler]) but the
 * incremental engine tracks no dependencies over them, so without a Java-side signal every
 * `.java` edit would have to recompile every Kotlin file.
 *
 * Two things stay in the fingerprint although they look like implementation: a compile-time
 * constant field's initializer, since Kotlin inlines Java constants into its callers' bytecode,
 * and annotations, since they reach Kotlin's resolution (nullability especially).
 *
 * Parsing uses javac's own parser via [JavacTask.parse] - syntax only, no symbol resolution and
 * no classpath - so it cannot fail over the unresolved cross-language references that make the
 * two-pass compile necessary. Anything unparseable yields null, which callers must read as
 * "assume the ABI changed".
 */
object JavaSourceAbi {
	/**
	 * One file's ABI.
	 *
	 * @property fingerprint hash over the file's imports and declarations, method bodies excluded.
	 * @property declaredTypeNames every type simple name the file declares, nested included -
	 *   the names a Kotlin source would have to write to reference it.
	 */
	data class FileAbi(
		val fingerprint: String,
		val declaredTypeNames: Set<String>,
	)

	/**
	 * Fingerprints each of [javaSources]; null if any file could not be parsed.
	 *
	 * @param javaSources every `.java` in the module; an empty list is a known-empty ABI, not
	 *   an unknown one.
	 * @return one entry per input file, or null - which callers must read as "assume the ABI
	 *   changed", never as "nothing changed".
	 */
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
	 * Simple names of every type whose ABI differs between [previous] and [current], covering
	 * added, removed and modified files. Takes the union of old and new names, so a renamed or
	 * deleted type is still named for Kotlin sources that may reference it.
	 *
	 * @param previous the last successful compile's snapshot; both maps are keyed by source file.
	 * @param current this compile's snapshot.
	 * @return simple names only, nested types included; empty means the Java side is ABI-stable
	 *   and no Kotlin bytecode can have moved because of it.
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
		// Imports are ABI. Signatures are fingerprinted as their written source text, so
		// swapping `import a.Widget` for `import b.Widget` changes the type a Kotlin caller
		// links against without moving one character of `Widget make()`. Sorted, so merely
		// reordering imports is not read as a change.
		for (import in imports.map { it.toString().trim() }.sorted()) {
			text.append(import).append('\n')
		}
		for (decl in typeDecls) {
			if (decl is ClassTree) decl.render(text, names, prefix = "")
		}
		return FileAbi(sha256(text.toString()), names)
	}

	/**
	 * Appends this type's declarations to the fingerprint text, recursing into nested types.
	 *
	 * @param out the fingerprint buffer; member order follows source order, so a pure reorder
	 *   does read as an ABI change.
	 * @param names collects every simple name declared, this type and its nested ones.
	 * @param prefix the enclosing type's dotted name, empty at the top level.
	 */
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

	/**
	 * Renders a method's signature, deliberately excluding its body.
	 *
	 * @param owner the enclosing type's dotted name, so two same-named methods do not collide.
	 * @return one line of fingerprint text; an annotation member's default value is included,
	 *   because that default is itself ABI.
	 */
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
	 * Renders a field's declaration, plus its initializer when the field is a compile-time
	 * constant. Kotlin bakes `static final` constant values into calling bytecode, so a changed
	 * value is an ABI change even though the signature did not move. An ordinary instance
	 * field's initializer is implementation and stays out.
	 *
	 * @param owner the enclosing type's dotted name.
	 * @param constantByDefault true for an interface, annotation or enum body, whose fields are
	 *   implicitly `static final` with no modifiers written.
	 * @return one line of fingerprint text, carrying the initializer only for a constant.
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
