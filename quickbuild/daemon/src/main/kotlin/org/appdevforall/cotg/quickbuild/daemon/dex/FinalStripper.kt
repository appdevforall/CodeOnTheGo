package org.appdevforall.cotg.quickbuild.daemon.dex

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/**
 * Clears ACC_FINAL from a class file, matching the proxy app build's ClassOpener in the
 * gradle-plugin. The generated Proxy*Activity classes extend the user's activities and the
 * dex verifier enforces superclass finality at load time, so every payload dex must carry the
 * recompiled user classes with finality stripped, exactly as the gen-0 baseline did. Kotlin
 * classes are final by default, so this runs on every hot recompile rather than once.
 */
object FinalStripper {
	/**
	 * Returns [classBytes] rewritten with ACC_FINAL cleared on the class and its inner classes.
	 *
	 * @param classBytes one whole `.class` file; read, never modified in place.
	 * @return a freshly allocated class file, semantically the input minus ACC_FINAL. Not
	 *   byte-comparable with the input: ASM rebuilds the constant pool on the way through.
	 */
	fun strip(classBytes: ByteArray): ByteArray {
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
