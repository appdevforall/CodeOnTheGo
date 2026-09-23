package org.appdevforall.codeonthego.indexing.jvm

import com.google.common.truth.Truth.assertThat
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Opcodes
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Which classes [JarSymbolScanner] admits, by visibility.
 *
 * The classpath trie this index replaces held every top-level class in a JAR regardless of
 * visibility, so dropping package-private ones would silently narrow completion.
 */
@RunWith(JUnit4::class)
class JarSymbolScannerVisibilityTest {
	private fun classBytes(
		internalName: String,
		access: Int,
	): ByteArray =
		ClassWriter(0)
			.apply {
				visit(Opcodes.V1_8, access, internalName, null, "java/lang/Object", null)
				visitEnd()
			}.toByteArray()

	private fun scan(
		internalName: String,
		access: Int,
	): List<JvmSymbol> = JarSymbolScanner.parseClassFile(classBytes(internalName, access).inputStream(), "test.jar")

	@Test
	fun `a public class is indexed`() {
		val symbols = scan("com/example/Pub", Opcodes.ACC_PUBLIC)

		assertThat(symbols.map { it.shortName }).containsExactly("Pub")
		assertThat(symbols.single().visibility).isEqualTo(JvmVisibility.PUBLIC)
	}

	@Test
	fun `a package-private class is indexed and keeps its visibility`() {
		val symbols = scan("com/example/Pkg", 0)

		assertThat(symbols.map { it.shortName }).containsExactly("Pkg")
		assertThat(symbols.single().visibility).isEqualTo(JvmVisibility.PACKAGE_PRIVATE)
	}

	@Test
	fun `a private nested class is not indexed`() {
		// Nothing outside the declaring class can name it, so it is never a completion candidate.
		assertThat(scan("com/example/Outer\$Hidden", Opcodes.ACC_PRIVATE)).isEmpty()
	}

	@Test
	fun `a nested class records its outer class`() {
		val nested = scan("com/example/Outer\$Inner", Opcodes.ACC_PUBLIC).single()

		assertThat(nested.shortName).isEqualTo("Inner")
		assertThat(nested.isTopLevel).isFalse()
		assertThat(nested.data.containingClassFqName).isEqualTo("com.example.Outer")
	}
}
