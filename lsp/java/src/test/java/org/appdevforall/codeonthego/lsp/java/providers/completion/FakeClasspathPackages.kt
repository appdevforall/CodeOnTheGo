package org.appdevforall.codeonthego.lsp.java.providers.completion

import org.appdevforall.codeonthego.indexing.jvm.JvmSymbol
import org.appdevforall.codeonthego.indexing.jvm.ModuleClasspathLookup.Child
import org.appdevforall.codeonthego.lsp.java.compiler.ClasspathClassNames

/** A compile classpath holding the top-level [classes], answering package queries the way the class index does. */
internal class FakeClasspathPackages(
	private val classes: List<String>,
) : ClasspathClassNames {
	override fun isClass(qualifiedName: String) = qualifiedName in classes

	override fun children(packageName: String): List<Child> {
		val prefix = if (packageName.isEmpty()) "" else "$packageName."
		val below = classes.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
		val subpackages =
			below
				.filter { '.' in it }
				.map { it.substringBefore('.') }
				.distinct()
				.map { Child(it, prefix + it, isClass = false) }
		val topLevelClasses = below.filterNot { '.' in it }.map { Child(it, prefix + it, isClass = true) }
		return subpackages + topLevelClasses
	}

	override fun qualifiedNamesByPrefix(
		prefix: String,
		limit: Int,
	) = emptyList<String>()

	override fun classesNamed(simpleName: String) = emptyList<JvmSymbol>()
}
