package com.itsaky.androidide.plugins.manager.services.sqltemplate

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.manager.services.SqlTemplateServiceImpl
import com.itsaky.androidide.templates.ProjectTemplatePebble
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.util.jar.JarFile

/**
 * The SQL template code calls no InputStream method that Android added in API 33 (ADFA-7302). minSdk is 28, desugaring
 * backports none of them for these packages (app/build.gradle.kts gives JavaIOReplacements org.eclipse.jgit only), lint
 * does not flag them here, and these tests run on a JDK that has them. The check reads each class file's method
 * references, so the older Files.readAllBytes and FileChannel.transferTo pass.
 */
class MinSdkCallsTest {
	@Test
	fun `the template code calls no InputStream method that needs API 33`() {
		val classes =
			classFiles(ProjectSqlFiles::class.java) { it.endsWith(".class") } +
				nested(SqlTemplateServiceImpl::class.java) +
				nested(ProjectTemplatePebble::class.java)
		assertThat(classes.keys).containsAtLeast("ProjectSqlFiles.class", "SqlTemplateServiceImpl.class", "ProjectTemplatePebble.class")
		// The reader finds a call ProjectSqlFiles makes, so an empty result below is not a parse that found nothing.
		assertThat(methodRefs(classes.getValue("ProjectSqlFiles.class"))).contains("java/nio/file/Files.newInputStream")

		val calls =
			classes.flatMap { (name, bytes) ->
				methodRefs(bytes)
					.filter { it.substringBefore('.').endsWith("InputStream") && it.substringAfter('.') in API_33 }
					.map { "$name: $it" }
			}
		assertThat(calls).isEmpty()
	}

	private companion object {
		val API_33 = setOf("readAllBytes", "readNBytes", "skipNBytes", "transferTo")
	}

	/** [type] and its nested and lambda classes. */
	private fun nested(type: Class<*>): Map<String, ByteArray> {
		val simple = type.simpleName
		return classFiles(type) { it == "$simple.class" || (it.startsWith("$simple$") && it.endsWith(".class")) }
	}

	/** The files in [type]'s package, not its subpackages, whose names [keep] accepts, by file name. */
	private fun classFiles(
		type: Class<*>,
		keep: (String) -> Boolean,
	): Map<String, ByteArray> {
		val pkg = type.name.substringBeforeLast('.').replace('.', '/') + "/"
		val source = type.protectionDomain.codeSource
		val location = File(source.location.toURI())
		// A module's classes are a jar on the unit test classpath, or a folder.
		if (location.isDirectory) {
			return File(location, pkg).listFiles { f -> f.isFile && keep(f.name) }!!.associate { it.name to it.readBytes() }
		}
		return JarFile(location).use { jar ->
			jar
				.entries()
				.toList()
				.filter { it.name.startsWith(pkg) && '/' !in it.name.substring(pkg.length) && keep(it.name.substring(pkg.length)) }
				.associate { it.name.substring(pkg.length) to jar.getInputStream(it).use { s -> s.readBytes() } }
		}
	}

	/** [classFile]'s method references, as `owner.name` with the owner in internal form, read from its constant pool. */
	private fun methodRefs(classFile: ByteArray): Set<String> {
		val input = DataInputStream(ByteArrayInputStream(classFile))
		check(input.readInt() == 0xCAFEBABE.toInt()) { "not a class file" }
		input.skipBytes(4) // minor and major version
		val count = input.readUnsignedShort()
		val utf8 = arrayOfNulls<String>(count)
		val className = IntArray(count)
		val nameOf = IntArray(count)
		val refs = mutableListOf<Pair<Int, Int>>()
		var i = 1
		while (i < count) {
			when (val tag = input.readUnsignedByte()) {
				1 -> {
					utf8[i] = input.readUTF()
				}

				3, 4, 9, 17, 18 -> {
					input.skipBytes(4)
				}

				5, 6 -> {
					// A long or a double takes two entries.
					input.skipBytes(8)
					i++
				}

				7 -> {
					className[i] = input.readUnsignedShort()
				}

				8, 16, 19, 20 -> {
					input.skipBytes(2)
				}

				10, 11 -> {
					refs += input.readUnsignedShort() to input.readUnsignedShort()
				}

				12 -> {
					nameOf[i] = input.readUnsignedShort()
					input.skipBytes(2)
				}

				15 -> {
					input.skipBytes(3)
				}

				else -> {
					error("unknown constant pool tag $tag")
				}
			}
			i++
		}
		return refs.mapTo(HashSet()) { (owner, nameAndType) -> "${utf8[className[owner]]}.${utf8[nameOf[nameAndType]]}" }
	}
}
