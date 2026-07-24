package com.itsaky.androidide.gradle.quickbuild

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class ClassOpenerTest {
	private fun classBytes(
		access: Int,
		name: String = "com/example/app/MainActivity",
	): ByteArray {
		val writer = ClassWriter(0)
		writer.visit(Opcodes.V11, access, name, null, "java/lang/Object", null)
		writer.visitEnd()
		return writer.toByteArray()
	}

	private fun accessOf(bytes: ByteArray): Int = ClassReader(bytes).access

	@Test
	fun `strips ACC_FINAL from a final class`() {
		val opened =
			ClassOpener.stripFinalModifier(
				classBytes(Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER or Opcodes.ACC_FINAL),
			)

		assertThat(accessOf(opened) and Opcodes.ACC_FINAL).isEqualTo(0)
		assertThat(accessOf(opened) and Opcodes.ACC_PUBLIC).isEqualTo(Opcodes.ACC_PUBLIC)
	}

	@Test
	fun `keeps a non-final class intact`() {
		val original = classBytes(Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER)

		val opened = ClassOpener.stripFinalModifier(original)

		assertThat(accessOf(opened)).isEqualTo(accessOf(original))
		assertThat(ClassReader(opened).className).isEqualTo("com/example/app/MainActivity")
		assertThat(ClassReader(opened).superName).isEqualTo("java/lang/Object")
	}
}
