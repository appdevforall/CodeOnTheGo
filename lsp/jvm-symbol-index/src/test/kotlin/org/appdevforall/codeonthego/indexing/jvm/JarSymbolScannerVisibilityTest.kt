package org.appdevforall.codeonthego.indexing.jvm

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Opcodes
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

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
	): List<JvmSymbol> = scan(classBytes(internalName, access))

	private fun scan(bytes: ByteArray): List<JvmSymbol> = JarSymbolScanner.parseClassFile(bytes.inputStream(), "test.jar")

	private fun scanNested(simpleName: String): List<JvmSymbol> = scan(nestedClasses.getValue("com/example/Outer\$$simpleName"))

	private fun nestedClass(simpleName: String): JvmSymbol = scanNested(simpleName).single { it.kind.isJvmClass }

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
		/*
		 * JVMS 4.1 forbids ACC_PRIVATE in a class file header, so javac writes ACC_SUPER there and
		 * records `private` only in the InnerClasses attribute.
		 */
		val bytes =
			ClassWriter(0)
				.apply {
					visit(Opcodes.V1_8, Opcodes.ACC_SUPER, "com/example/Outer\$Hidden", null, "java/lang/Object", null)
					visitInnerClass(
						"com/example/Outer\$Hidden",
						"com/example/Outer",
						"Hidden",
						Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC,
					)
					visitEnd()
				}.toByteArray()

		assertThat(scan(bytes)).isEmpty()
	}

	@Test
	fun `a private nested class compiled by javac is not indexed`() {
		assertThat(scanNested("Hidden")).isEmpty()
	}

	@Test
	fun `a protected nested class compiled by javac is indexed as protected`() {
		// javac widens the header of a protected nested class to public.
		assertThat(nestedClass("Guarded").visibility).isEqualTo(JvmVisibility.PROTECTED)
	}

	@Test
	fun `a static nested class compiled by javac is static, not inner`() {
		val info = nestedClass("Open").data as JvmClassInfo

		assertThat(info.isStatic).isTrue()
		assertThat(info.isInner).isFalse()
	}

	@Test
	fun `an inner class compiled by javac keeps package-private visibility and is inner`() {
		val symbol = nestedClass("Pkg")
		val info = symbol.data as JvmClassInfo

		assertThat(symbol.visibility).isEqualTo(JvmVisibility.PACKAGE_PRIVATE)
		assertThat(info.isInner).isTrue()
		assertThat(info.isStatic).isFalse()
	}

	@Test
	fun `a nested class records its outer class`() {
		val nested = scan("com/example/Outer\$Inner", Opcodes.ACC_PUBLIC).single()

		assertThat(nested.shortName).isEqualTo("Inner")
		assertThat(nested.isTopLevel).isFalse()
		assertThat(nested.data.containingClassFqName).isEqualTo("com.example.Outer")
	}

	companion object {
		/**
		 * JUnit4 builds a fresh test instance per `@Test` method, so an instance-level `by lazy`
		 * would recompile this fixture once per test. A companion `by lazy` is shared by every
		 * instance in the class, so javac runs once for the whole class.
		 */
		private val nestedClasses: Map<String, ByteArray> by lazy {
			compileJava(
				"com.example.Outer",
				"""
				package com.example;

				public class Outer {
					private static class Hidden {}
					protected static class Guarded {}
					public static class Open {}
					class Pkg {}
				}
				""".trimIndent(),
			)
		}
	}
}

/**
 * Compiles one Java source file with the test JVM's own javac, returning each produced class file
 * by internal name.
 *
 * Reached through reflection because this Android module compiles against `android.jar`, which has
 * no `javax.tools`; the JDK running the tests does.
 */
private fun compileJava(
	className: String,
	source: String,
): Map<String, ByteArray> {
	val workDir = Files.createTempDirectory("javac-fixture")
	try {
		val sourceFile = workDir.resolve("${className.substringAfterLast('.')}.java")
		sourceFile.toFile().writeText(source)
		val outDir = Files.createDirectory(workDir.resolve("out"))

		val compiler = Class.forName("javax.tools.ToolProvider").getMethod("getSystemJavaCompiler").invoke(null)
		assertWithMessage("the test JVM has no system Java compiler").that(compiler).isNotNull()
		val run =
			Class
				.forName("javax.tools.Tool")
				.getMethod("run", InputStream::class.java, OutputStream::class.java, OutputStream::class.java, Array<String>::class.java)
		val args = arrayOf("--release", "11", "-d", outDir.toString(), sourceFile.toString())
		val exitCode = run.invoke(compiler, null, null, null, args) as Int
		assertWithMessage("javac exit code for $className").that(exitCode).isEqualTo(0)

		return Files.walk(outDir).use { paths ->
			paths
				.filter { it.toString().endsWith(".class") }
				.toList()
				.associate { outDir.relativize(it).toString().removeSuffix(".class") to Files.readAllBytes(it) }
		}
	} finally {
		workDir.toFile().deleteRecursively()
	}
}
