package org.appdevforall.codeonthego.indexing.jvm

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsaky.androidide.projects.classpath.JarFsClasspathReader
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.file.Paths

/**
 * The index must offer exactly the classes the classpath trie held.
 *
 * The oracle is built from the **old** path: `JarFsClasspathReader` filtered to top-level classes is
 * literally what filled `ModuleProject.compileClasspathClasses`. Building it from the index instead
 * would let the new code agree with itself and pin nothing.
 *
 * It runs against real JARs because the cases that matter do not occur in a hand-built fixture:
 * Kotlin file facades, multi-file class parts and anonymous Kotlin classes in kotlin-stdlib, and
 * package-private, anonymous and nested Java classes in JUnit.
 */
@RunWith(Parameterized::class)
class ClasspathTrieDifferentialTest(
	private val corpus: String,
	private val anchor: Class<*>,
) {
	companion object {
		@JvmStatic
		@Parameterized.Parameters(name = "{0}")
		fun corpora(): List<Array<Any>> =
			listOf(
				arrayOf("kotlin-stdlib", Unit::class.java),
				arrayOf("junit", Test::class.java),
			)
	}

	private val jar: File by lazy {
		val location =
			File(
				anchor.protectionDomain.codeSource.location
					.toURI(),
			)
		assertWithMessage("$corpus must resolve to a JAR on the test classpath, got $location")
			.that(location.isFile && location.name.endsWith(".jar"))
			.isTrue()
		location
	}

	private val isKotlinCorpus get() = anchor == Unit::class.java

	/** What the trie held: every top-level class the classpath reader found. */
	private fun trieNames(): Set<String> =
		JarFsClasspathReader()
			.listClasses(listOf(jar))
			.asSequence()
			.filter { it.isTopLevel }
			.map { it.name }
			.toSet()

	private fun indexClasses(): List<JvmSymbol> =
		CombinedJarScanner
			.scan(Paths.get(jar.absolutePath), "differential")
			.filter { it.kind.isJvmClass && it.isTopLevel }
			.toList()

	/** What the index offers for the same JAR. */
	private fun indexNames(): Set<String> = indexClasses().map { it.fqName }.toSet()

	/**
	 * A fragment of a `@JvmMultifileClass` facade, e.g. `LazyKt__LazyJVMKt`.
	 *
	 * The only thing the index deliberately withholds: the declarations belong to the facade, which
	 * is indexed, and neither Java nor Kotlin names the part.
	 */
	private fun isMultiFilePart(qualifiedName: String) = qualifiedName.substringAfterLast('.').contains("__")

	@Test
	fun `every class the trie held is offered by the index, bar multi-file parts`() {
		val missing = trieNames() - indexNames()

		assertThat(missing.filterNot(::isMultiFilePart)).isEmpty()
	}

	@Test
	fun `the index offers nothing the trie did not hold`() {
		assertThat(indexNames() - trieNames()).isEmpty()
	}

	@Test
	fun `the withheld classes really are multi-file parts, and a Kotlin corpus has some`() {
		val missing = trieNames() - indexNames()

		// Guards the exception itself: if the corpus stopped containing parts, the first test would
		// start passing for the wrong reason.
		assertThat(missing.all(::isMultiFilePart)).isTrue()
		if (isKotlinCorpus) assertThat(missing).isNotEmpty()
	}

	@Test
	fun `the corpus is large enough for the comparison to mean something`() {
		assertThat(trieNames().size).isGreaterThan(if (isKotlinCorpus) 500 else 150)
	}

	@Test
	fun `a Java corpus holds the package-private, anonymous and nested classes it is here for`() {
		assumeFalse("only the Java corpus is chosen for these shapes", isKotlinCorpus)
		val classes = JarFsClasspathReader().listClasses(listOf(jar))

		assertThat(indexClasses().any { it.visibility == JvmVisibility.PACKAGE_PRIVATE }).isTrue()
		assertThat(classes.any { it.isAnonymous }).isTrue()
		assertThat(classes.any { it.isInner }).isTrue()
	}
}
