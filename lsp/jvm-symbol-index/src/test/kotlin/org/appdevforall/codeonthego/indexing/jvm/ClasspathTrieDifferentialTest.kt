package org.appdevforall.codeonthego.indexing.jvm

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.projects.classpath.JarFsClasspathReader
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File
import java.nio.file.Paths

/**
 * The index must offer exactly the classes the classpath trie held.
 *
 * The oracle is built from the **old** path: `JarFsClasspathReader` filtered to top-level classes is
 * literally what filled `ModuleProject.compileClasspathClasses`. Building it from the index instead
 * would let the new code agree with itself and pin nothing.
 *
 * It runs against a real JAR because the cases that matter -- Kotlin file facades, multi-file class
 * parts, package-private classes, anonymous and nested types, multi-release entries -- do not occur
 * in a hand-built fixture. Two defects were found this way: anonymous Kotlin classes surfacing as
 * top-level classes named `1`, and `module-info` from a multi-release JAR indexed as a class.
 */
@RunWith(JUnit4::class)
class ClasspathTrieDifferentialTest {
	private val jar: File? =
		runCatching {
			File(
				Unit::class.java.protectionDomain.codeSource.location
					.toURI(),
			)
		}.getOrNull()
			?.takeIf { it.isFile && it.name.endsWith(".jar") }

	/** What the trie held: every top-level class the classpath reader found. */
	private fun trieNames(jar: File): Set<String> =
		JarFsClasspathReader()
			.listClasses(listOf(jar))
			.asSequence()
			.filter { it.isTopLevel }
			.map { it.name }
			.toSet()

	/** What the index offers for the same JAR. */
	private fun indexNames(jar: File): Set<String> =
		CombinedJarScanner
			.scan(Paths.get(jar.absolutePath), "differential")
			.filter { it.kind.isJvmClass && it.isTopLevel }
			.map { it.fqName }
			.toSet()

	/**
	 * A fragment of a `@JvmMultifileClass` facade, e.g. `LazyKt__LazyJVMKt`.
	 *
	 * The only thing the index deliberately withholds: the declarations belong to the facade, which
	 * is indexed, and neither Java nor Kotlin names the part.
	 */
	private fun isMultiFilePart(qualifiedName: String) = qualifiedName.substringAfterLast('.').contains("__")

	private fun requireJar(): File {
		assumeTrue("kotlin-stdlib not resolvable from the test classpath", jar != null)
		return jar!!
	}

	@Test
	fun `every class the trie held is offered by the index, bar multi-file parts`() {
		val jar = requireJar()

		val missing = trieNames(jar) - indexNames(jar)

		assertThat(missing.filterNot(::isMultiFilePart)).isEmpty()
	}

	@Test
	fun `the index offers nothing the trie did not hold`() {
		val jar = requireJar()

		// Anonymous Kotlin classes and multi-release `module-info` entries both used to land here.
		assertThat(indexNames(jar) - trieNames(jar)).isEmpty()
	}

	@Test
	fun `the withheld classes really are multi-file parts, and there are some`() {
		val jar = requireJar()

		val missing = trieNames(jar) - indexNames(jar)

		// Guards the exception itself: if the corpus stopped containing parts, the first test would
		// start passing for the wrong reason.
		assertThat(missing).isNotEmpty()
		assertThat(missing.all(::isMultiFilePart)).isTrue()
	}

	@Test
	fun `the corpus is large enough for the comparison to mean something`() {
		val jar = requireJar()

		assertThat(trieNames(jar).size).isGreaterThan(500)
	}
}
