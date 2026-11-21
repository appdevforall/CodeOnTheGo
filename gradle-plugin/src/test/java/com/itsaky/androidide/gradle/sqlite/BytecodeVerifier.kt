package com.itsaky.androidide.gradle.sqlite

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URLClassLoader

/**
 * Rewrites compiled samples the way the plugin does and verifies the result twice: with ASM's
 * CheckClassAdapter (types, through a loader that sees requery, android.jar, androidx core and
 * androidx sqlite) and by making the JVM link each class, which runs its verifier over the
 * StackMapTable. asm-util is on the test runtime classpath through AGP only, hence the reflection.
 */
internal object BytecodeVerifier {
	private val renamer =
		object : Remapper() {
			override fun map(internalName: String): String = SqliteRewriteRules.RENAMES[internalName] ?: internalName
		}

	private val jars by lazy {
		listOfNotNull(
			TestJars.requeryJar,
			TestJars.androidJar,
			TestJars.androidxCoreJar,
			TestJars.androidxSqliteJar,
			TestJars.kotlinStdlibJar,
		)
	}

	/** Original (Android-named) samples are added on top of the jars, renamed ones to [after]. */
	private class Universe(
		val before: ClassIndex,
		val after: ClassIndex,
	)

	private fun universe(originals: Map<String, ByteArray>): Universe {
		val before = ClassIndex(listOf(TestJars.androidJar, TestJars.androidxCoreJar))
		val after = ClassIndex(jars)
		for (bytes in originals.values) {
			before.addClass(bytes)
			val renamed = ClassWriter(0)
			ClassReader(bytes).accept(ClassRemapper(renamed, renamer), 0)
			after.addClass(renamed.toByteArray())
		}
		return Universe(before, after)
	}

	/** Rewrites every class, then verifies all of them. Returns the rewritten bytes by original name. */
	fun rewriteAndVerify(originals: Map<String, ByteArray>): Map<String, ByteArray> {
		val u = universe(originals)
		val hierarchy = ClassHierarchy { name -> if (u.before.knows(name)) u.before.superTypes(name) else null }
		val out = originals.mapValues { SqliteRewriter.rewrite(it.value, hierarchy) }
		verify(out.values.associateBy { ClassReader(it).className })
		return out
	}

	/** Fails with the verifier's output when any class in [classes] (by its final name) does not verify. */
	fun verify(classes: Map<String, ByteArray>) {
		val loader = SampleLoader(classes)
		val checkClass = Class.forName("org.objectweb.asm.util.CheckClassAdapter")
		val check =
			checkClass.getMethod(
				"verify",
				ClassReader::class.java,
				ClassLoader::class.java,
				Boolean::class.javaPrimitiveType,
				PrintWriter::class.java,
			)
		val problems = StringBuilder()
		for ((name, bytes) in classes) {
			val sw = StringWriter()
			check.invoke(null, ClassReader(bytes), loader, false, PrintWriter(sw))
			if (sw.toString().isNotBlank()) problems.append("CheckClassAdapter on $name:\n").append(sw).append('\n')
			try {
				// reflection links the class, which verifies it
				Class.forName(name.replace('/', '.'), false, loader).declaredMethods
			} catch (e: LinkageError) {
				problems.append("JVM verification of $name failed: $e\n")
			}
		}
		check(problems.isEmpty()) { "Rewritten bytecode does not verify:\n$problems" }
	}

	/** A loader over the same classpath as [verify], defining [classes] (by final name). */
	fun loader(classes: Map<String, ByteArray>): ClassLoader = SampleLoader(classes)

	private class SampleLoader(
		private val classes: Map<String, ByteArray>,
	) : URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader()) {
		override fun findClass(name: String): Class<*> {
			val bytes = classes[name.replace('.', '/')] ?: return super.findClass(name)
			return defineClass(name, bytes, 0, bytes.size)
		}
	}
}
