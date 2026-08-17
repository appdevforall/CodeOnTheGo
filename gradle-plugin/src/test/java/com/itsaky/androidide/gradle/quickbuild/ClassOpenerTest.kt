package com.itsaky.androidide.gradle.quickbuild

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class ClassOpenerTest {
	/** One entry of a class file's InnerClasses attribute, as ASM reports it. */
	private data class InnerClassEntry(
		val name: String?,
		val outerName: String?,
		val innerName: String?,
		val access: Int,
	)

	private fun classBytes(
		access: Int,
		name: String = "com/example/app/MainActivity",
	): ByteArray {
		val writer = ClassWriter(0)
		writer.visit(Opcodes.V11, access, name, null, "java/lang/Object", null)
		writer.visitEnd()
		return writer.toByteArray()
	}

	/** An outer class whose InnerClasses attribute declares one final, public, static nested class. */
	private fun classWithFinalInnerClass(): ByteArray {
		val writer = ClassWriter(0)
		writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, "com/example/app/Outer", null, "java/lang/Object", null)
		writer.visitInnerClass(
			"com/example/app/Outer\$Inner",
			"com/example/app/Outer",
			"Inner",
			Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
		)
		writer.visitEnd()
		return writer.toByteArray()
	}

	private fun innerClassEntries(bytes: ByteArray): List<InnerClassEntry> {
		val entries = mutableListOf<InnerClassEntry>()
		ClassReader(bytes).accept(
			object : ClassVisitor(Opcodes.ASM9) {
				override fun visitInnerClass(
					name: String?,
					outerName: String?,
					innerName: String?,
					access: Int,
				) {
					entries.add(InnerClassEntry(name, outerName, innerName, access))
				}
			},
			0,
		)
		return entries
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

	@Test
	fun `isFinal is true for a final class`() {
		val bytes = classBytes(Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER or Opcodes.ACC_FINAL)

		assertThat(ClassOpener.isFinal(bytes)).isTrue()
	}

	@Test
	fun `isFinal is false for a non-final class`() {
		val bytes = classBytes(Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER)

		assertThat(ClassOpener.isFinal(bytes)).isFalse()
	}

	@Test
	fun `strips ACC_FINAL from inner class entries, keeping their other flags and names`() {
		// A nested user component (WorkManager's ConstraintProxy$BatteryChargingProxy and kin)
		// is proxied by its canonical name, and the dex verifier reads finality from the
		// declaring class's InnerClasses entry as well as the class's own access flags.
		val opened = ClassOpener.stripFinalModifier(classWithFinalInnerClass())

		assertThat(innerClassEntries(opened))
			.containsExactly(
				InnerClassEntry(
					name = "com/example/app/Outer\$Inner",
					outerName = "com/example/app/Outer",
					innerName = "Inner",
					access = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
				),
			)
	}
}
