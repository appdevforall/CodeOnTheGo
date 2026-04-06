package org.appdevforall.codeonthego.indexing.jvm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.jetbrains.org.objectweb.asm.AnnotationVisitor
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.pathString

/**
 * Scans a JAR and routes each class to the appropriate scanner:
 * [KotlinMetadataScanner] for Kotlin classes, [JarSymbolScanner] for Java.
 */
object CombinedJarScanner {

	private val log = LoggerFactory.getLogger(CombinedJarScanner::class.java)

	fun scan(jarPath: Path, sourceId: String = jarPath.pathString): Flow<JvmSymbol> = flow {
		val jar = try {
			JarFile(jarPath.toFile())
		} catch (e: Exception) {
			log.warn("Failed to open JAR: {}", jarPath, e)
			return@flow
		}

		jar.use {
			val entries = jar.entries()
			while (entries.hasMoreElements()) {
				val entry = entries.nextElement()
				if (!entry.name.endsWith(".class")) continue
				if (entry.name == "module-info.class" || entry.name == "package-info.class") continue

				try {
					val bytes = jar.getInputStream(entry).use { input ->
						val buf = ByteArrayOutputStream(entry.size.toInt().coerceAtLeast(1024))
						input.copyTo(buf)
						buf.toByteArray()
					}

					val symbols = if (hasKotlinMetadata(bytes)) {
						KotlinMetadataScanner.parseKotlinClass(bytes.inputStream(), sourceId)
					} else {
						JarSymbolScanner.parseClassFile(bytes.inputStream(), sourceId)
					}

					symbols?.forEach { emit(it) }
				} catch (e: Exception) {
					log.debug("Failed to parse {}: {}", entry.name, e.message)
				}
			}
		}
	}
		.flowOn(Dispatchers.IO)

	private fun hasKotlinMetadata(classBytes: ByteArray): Boolean {
		var found = false
		try {
			ClassReader(classBytes).accept(object : ClassVisitor(Opcodes.ASM9) {
				override fun visitAnnotation(
					descriptor: String?,
					visible: Boolean
				): AnnotationVisitor? {
					if (descriptor == "Lkotlin/Metadata;") found = true
					return null
				}
			}, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
		} catch (_: Exception) {
		}
		return found
	}
}
