package com.itsaky.androidide.gradle.quickbuild

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/**
 * Clears ACC_FINAL from a class file. Kotlin classes are final by default, but the
 * generated proxies must extend the user's activities - and the dex verifier enforces
 * finality at runtime, so the opened classes are also what ships in the payload dex.
 */
object ClassOpener {
	/**
	 * True if the class file declares ACC_FINAL. Reads only the class header (ASM's
	 * [ClassReader.access] convenience accessor) - never loads the class - so this is safe
	 * to call on an arbitrary library class file from a compile classpath jar.
	 */
	fun isFinal(classBytes: ByteArray): Boolean = ClassReader(classBytes).access and Opcodes.ACC_FINAL != 0

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
