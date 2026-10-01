package org.appdevforall.codeonthego.indexing.jvm

import com.google.common.truth.Truth.assertThat
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Opcodes
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.metadata.ClassKind
import kotlin.metadata.KmClass
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmType
import kotlin.metadata.Visibility
import kotlin.metadata.jvm.JvmMetadataVersion
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.kind
import kotlin.metadata.visibility

/**
 * A '$' in a Kotlin class's metadata name is part of a declared name, never nesting, yet the index
 * treats it as nesting so it holds the same top-level classes the classpath trie did.
 */
@RunWith(JUnit4::class)
class KotlinMetadataScannerDollarNameTest {
	/** A class file for [name] carrying genuine `@kotlin.Metadata` written by kotlin-metadata-jvm. */
	private fun kotlinClassFile(name: String): ByteArray {
		val kmClass =
			KmClass().apply {
				this.name = name
				kind = ClassKind.CLASS
				visibility = Visibility.PUBLIC
				supertypes += KmType().apply { classifier = KmClassifier.Class("kotlin/Any") }
			}
		val metadata = KotlinClassMetadata.Class(kmClass, JvmMetadataVersion.LATEST_STABLE_SUPPORTED, 0).write()

		return ClassWriter(0)
			.apply {
				visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, name, null, "java/lang/Object", null)
				visitAnnotation("Lkotlin/Metadata;", true).apply {
					visit("k", metadata.kind)
					visit("mv", metadata.metadataVersion)
					visit("xi", metadata.extraInt)
					visitArray("d1").apply {
						metadata.data1.forEach { visit(null, it) }
						visitEnd()
					}
					visitArray("d2").apply {
						metadata.data2.forEach { visit(null, it) }
						visitEnd()
					}
					visitEnd()
				}
				visitEnd()
			}.toByteArray()
	}

	private fun classSymbol(name: String): JvmSymbol =
		KotlinMetadataScanner
			.parseKotlinClass(kotlinClassFile(name).inputStream(), "test.jar")!!
			.single { it.kind.isClassifier }

	@Test
	fun `a dollar in a Kotlin class name is treated as nesting`() {
		val symbol = classSymbol("com/example/Odd\$Name")

		assertThat(symbol.shortName).isEqualTo("Name")
		assertThat(symbol.containingClassName).isEqualTo("com/example/Odd")
	}

	@Test
	fun `a Kotlin class without a dollar is top level`() {
		val symbol = classSymbol("com/example/Plain")

		assertThat(symbol.shortName).isEqualTo("Plain")
		assertThat(symbol.isTopLevel).isTrue()
	}
}
