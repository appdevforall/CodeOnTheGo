package org.appdevforall.codeonthego.indexing.jvm

import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.impl.base.util.LibraryUtils
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
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

	@OptIn(KaImplementationDetail::class)
	fun scan(
		rootVf: VirtualFile,
		sourceId: String = rootVf.path,
	): Sequence<JvmSymbol> =
		sequence {
			val allFiles = LibraryUtils.getAllVirtualFilesFromRoot(rootVf, includeRoot = true)
			for (vf in allFiles) {
				if (!vf.name.endsWith(".class")) continue
				if (vf.name == "module-info.class" || vf.name == "package-info.class") continue
				try {
					val bytes = vf.contentsToByteArray()
					val symbols =
						if (hasKotlinMetadata(bytes)) {
							KotlinMetadataScanner.parseKotlinClass(bytes.inputStream(), sourceId)
						} else {
							JarSymbolScanner.parseClassFile(bytes.inputStream(), sourceId)
						}
					symbols?.forEach { yield(it) }
				} catch (e: Exception) {
					log.debug("Failed to parse {}: {}", vf.path, e.message)
				}
			}
		}

	/**
	 * The returned sequence is [AutoCloseable]. Close it when iteration may stop early: an abandoned
	 * iterator never finishes its `use` block, which leaves the JAR to the GC cleaner.
	 */
	fun scan(
		jarPath: Path,
		sourceId: String = jarPath.pathString,
	): Sequence<JvmSymbol> = JarSymbols(jarPath, sourceId)

	private class JarSymbols(
		private val jarPath: Path,
		private val sourceId: String,
	) : Sequence<JvmSymbol>,
		AutoCloseable {
		@Volatile private var closed = false

		@Volatile private var jar: JarFile? = null

		override fun iterator(): Iterator<JvmSymbol> =
			sequence {
				if (closed) return@sequence
				val jar =
					try {
						JarFile(jarPath.toFile())
					} catch (e: Exception) {
						log.warn("Failed to open JAR: {}", jarPath, e)
						return@sequence
					}
				this@JarSymbols.jar = jar

				jar.use {
					val entries = jar.entries()
					while (entries.hasMoreElements()) {
						val entry = entries.nextElement()
						if (!entry.name.endsWith(".class")) continue
						if (entry.name == "module-info.class" || entry.name == "package-info.class") continue

						try {
							val bytes =
								jar.getInputStream(entry).use { input ->
									val buf = ByteArrayOutputStream(entry.size.toInt().coerceAtLeast(1024))
									input.copyTo(buf)
									buf.toByteArray()
								}

							val symbols =
								if (hasKotlinMetadata(bytes)) {
									KotlinMetadataScanner.parseKotlinClass(bytes.inputStream(), sourceId)
								} else {
									JarSymbolScanner.parseClassFile(bytes.inputStream(), sourceId)
								}

							symbols?.forEach { yield(it) }
						} catch (e: Exception) {
							log.debug("Failed to parse {}: {}", entry.name, e.message)
						}
					}
				}
				this@JarSymbols.jar = null
			}.iterator()

		override fun close() {
			closed = true
			jar?.close()
			jar = null
		}
	}

	private fun hasKotlinMetadata(classBytes: ByteArray): Boolean {
		var found = false
		try {
			ClassReader(classBytes).accept(
				object : ClassVisitor(Opcodes.ASM9) {
					override fun visitAnnotation(
						descriptor: String?,
						visible: Boolean,
					): AnnotationVisitor? {
						if (descriptor == "Lkotlin/Metadata;") found = true
						return null
					}
				},
				ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
			)
		} catch (_: Exception) {
		}
		return found
	}
}
