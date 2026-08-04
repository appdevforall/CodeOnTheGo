package com.itsaky.androidide.gradle.quickbuild

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/**
 * Clears ACC_FINAL from class files so generated proxies can extend the user's activities.
 * Kotlin classes are final by default, and the dex verifier enforces finality at runtime, so
 * the opened bytes are what ships in the payload dex.
 */
object ClassOpener {
	/**
	 * Reports whether a class declares ACC_FINAL. Reads only the class header and never loads
	 * the class, so it is safe on arbitrary library classes from a compile classpath jar.
	 *
	 * @param classBytes a whole, well-formed `.class` file; ASM throws on anything else.
	 * @return true if the class itself is final, ignoring the finality of its inner classes.
	 */
	fun isFinal(classBytes: ByteArray): Boolean = ClassReader(classBytes).access and Opcodes.ACC_FINAL != 0

	/**
	 * Rewrites a class with ACC_FINAL cleared on the class itself and on its inner classes.
	 *
	 * @param classBytes a whole, well-formed `.class` file; not modified in place.
	 * @return the rewritten bytes. Constant pool and frames are copied through unchanged, so the
	 *   result differs from the input only in the two access flags.
	 */
	fun stripFinalModifier(classBytes: ByteArray): ByteArray {
		val reader = ClassReader(classBytes)
		val writer = ClassWriter(0)
		reader.accept(
			object : ClassVisitor(Opcodes.ASM9, writer) {
				override fun visit(
					version: Int,
					access: Int,
					name: String?,
					signature: String?,
					superName: String?,
					interfaces: Array<out String>?,
				) {
					super.visit(version, access and Opcodes.ACC_FINAL.inv(), name, signature, superName, interfaces)
				}

				override fun visitInnerClass(
					name: String?,
					outerName: String?,
					innerName: String?,
					access: Int,
				) {
					super.visitInnerClass(name, outerName, innerName, access and Opcodes.ACC_FINAL.inv())
				}
			},
			0,
		)
		return writer.toByteArray()
	}
}
