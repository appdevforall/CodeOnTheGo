package org.appdevforall.codeonthego.indexing.jvm

import com.google.common.truth.Truth.assertWithMessage
import java.io.File

/**
 * Returns the JAR on the test classpath that [anchor] was loaded from, failing the test when it
 * does not come from a JAR.
 */
fun testClasspathJar(anchor: Class<*>): File {
	val location =
		File(
			anchor.protectionDomain.codeSource.location
				.toURI(),
		)
	assertWithMessage("${anchor.name} must be loaded from a JAR on the test classpath, got $location")
		.that(location.isFile && location.name.endsWith(".jar"))
		.isTrue()
	return location
}

/**
 * Returns whether [qualifiedName] is a fragment of a `@JvmMultifileClass` facade, e.g.
 * `LazyKt__LazyJVMKt`.
 *
 * The trie held these and the index deliberately withholds them: the declarations belong to the
 * facade, which is indexed, and neither Java nor Kotlin names the part.
 */
fun isMultiFilePart(qualifiedName: String) = qualifiedName.substringAfterLast('.').contains("__")
