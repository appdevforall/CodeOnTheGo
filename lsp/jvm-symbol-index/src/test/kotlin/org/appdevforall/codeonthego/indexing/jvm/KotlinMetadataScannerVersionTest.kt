package org.appdevforall.codeonthego.indexing.jvm

import com.google.common.truth.Truth.assertThat
import org.jetbrains.org.objectweb.asm.AnnotationVisitor
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Opcodes
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File
import java.util.jar.JarFile

/** A class compiled by a newer Kotlin than the bundled metadata reader supports is still indexed. */
@RunWith(JUnit4::class)
class KotlinMetadataScannerVersionTest {
	private fun stdlibClass(entryName: String): ByteArray {
		val stdlib =
			File(
				Unit::class.java.protectionDomain.codeSource.location
					.toURI(),
			)
		return JarFile(stdlib).use { jar -> jar.getInputStream(jar.getJarEntry(entryName)).use { it.readBytes() } }
	}

	/** Rewrites the `mv` (metadata version) of [classBytes]'s `@kotlin.Metadata` to [version]. */
	private fun withMetadataVersion(
		classBytes: ByteArray,
		version: IntArray,
	): ByteArray {
		val writer = ClassWriter(0)
		ClassReader(classBytes).accept(
			object : ClassVisitor(Opcodes.ASM9, writer) {
				override fun visitAnnotation(
					descriptor: String?,
					visible: Boolean,
				): AnnotationVisitor? {
					val delegate = super.visitAnnotation(descriptor, visible)
					if (descriptor != "Lkotlin/Metadata;") return delegate
					return object : AnnotationVisitor(Opcodes.ASM9, delegate) {
						override fun visit(
							name: String?,
							value: Any?,
						) = super.visit(name, if (name == "mv") version else value)
					}
				}
			},
			0,
		)
		return writer.toByteArray()
	}

	@Test
	fun `a class with metadata newer than the reader supports is still indexed`() {
		val newer = withMetadataVersion(stdlibClass("kotlin/Pair.class"), intArrayOf(99, 0, 0))

		val symbols = KotlinMetadataScanner.parseKotlinClass(newer.inputStream(), "stdlib").orEmpty()

		assertThat(symbols.filter { it.kind.isClassifier }.map { it.key }).containsExactly("kotlin/Pair")
	}
}
