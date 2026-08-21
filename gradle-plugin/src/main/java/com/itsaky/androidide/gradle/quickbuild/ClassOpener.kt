package com.itsaky.androidide.gradle.quickbuild

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

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
	 * @return the rewritten bytes, differing from the input only in the class and inner-class
	 *   ACC_FINAL flags, since the constant pool and frames are copied through unchanged.
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

	/**
	 * Copies a jar with ACC_FINAL cleared on every class entry it carries.
	 *
	 * The diverted class *directories* are opened entry by entry above, but a diverted jar
	 * reached the proxy compile classpath and the D8 program inputs unopened - so a user class
	 * that lands in a jar rather than a directory keeps its final flag, and the generated proxy
	 * that extends it fails to compile or fails the dex verifier at load.
	 *
	 * @param source a readable jar; not modified.
	 * @param destination written fresh, parents created; any existing file is replaced.
	 * @return [destination], so call sites can map straight onto the opened jar.
	 */
	fun openJar(
		source: File,
		destination: File,
	): File {
		destination.parentFile?.mkdirs()
		JarFile(source).use { jar ->
			JarOutputStream(destination.outputStream().buffered()).use { out ->
				jar.entries().asSequence().sortedBy { it.name }.forEach { entry ->
					val bytes = jar.getInputStream(entry).use { it.readBytes() }
					// A fresh entry, because copying the source entry carries its compressed
					// size and CRC over onto bytes we may have just rewritten.
					out.putNextEntry(JarEntry(entry.name))
					if (!entry.isDirectory && entry.name.endsWith(".class")) {
						out.write(stripFinalModifier(bytes))
					} else {
						out.write(bytes)
					}
					out.closeEntry()
				}
			}
		}
		return destination
	}
}
