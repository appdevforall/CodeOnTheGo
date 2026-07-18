package org.appdevforall.cotg.quickbuild.daemon.dex

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/**
 * Drives D8 over compiled class files to produce `classes.dex`. The r8 jar is supplied
 * by the device (CoGo's provisioned build-tools) at configure time and loaded through
 * its own [URLClassLoader]; everything is invoked reflectively so the daemon carries no
 * AGP/r8 build dependency and works against whatever build-tools version the device
 * ships.
 */
class DexTool(
	d8Jar: File,
	private val androidJar: File,
	private val minApi: Int,
) : AutoCloseable {
	sealed interface Result {
		data class Success(
			val dexFile: File,
		) : Result

		data class Failed(
			val message: String,
		) : Result
	}

	private val loader = URLClassLoader(arrayOf(d8Jar.toURI().toURL()), DexTool::class.java.classLoader)

	/**
	 * Dexes every `.class` under [classesDirs] into `<outDir>/classes.dex`, first
	 * clearing ACC_FINAL from each class ([FinalStripper]) so the payload matches the
	 * gen-0 baseline's opened classes and the proxies' `extends` stays verifiable.
	 */
	fun dex(
		classesDirs: List<File>,
		outDir: File,
	): Result {
		outDir.mkdirs()
		val classFiles = openClasses(classesDirs, File(outDir, "opened-classes"))
		if (classFiles.isEmpty()) {
			return Result.Failed("no .class files found under: ${classesDirs.joinToString()}")
		}
		return try {
			runD8(classFiles, outDir.toPath())
			val dexFile = File(outDir, "classes.dex")
			if (dexFile.isFile) {
				Result.Success(dexFile)
			} else {
				Result.Failed("d8 reported success but produced no classes.dex in $outDir")
			}
		} catch (e: InvocationTargetException) {
			Result.Failed("d8 failed: ${e.cause?.message ?: e.cause?.javaClass?.name ?: e.message}")
		} catch (e: ReflectiveOperationException) {
			Result.Failed("d8 jar is not usable (wrong build-tools layout?): ${e.message}")
		}
	}

	private fun runD8(
		classFiles: List<Path>,
		outDir: Path,
	) {
		val commandClass = loader.loadClass("com.android.tools.r8.D8Command")
		val outputModeClass = loader.loadClass("com.android.tools.r8.OutputMode")
		val dexIndexed = outputModeClass.enumConstants.first { (it as Enum<*>).name == "DexIndexed" }

		val builder = commandClass.getMethod("builder").invoke(null)
		val builderClass = builder.javaClass
		builderClass
			.getMethod("addProgramFiles", Collection::class.java)
			.invoke(builder, classFiles)
		builderClass
			.getMethod("addLibraryFiles", Collection::class.java)
			.invoke(builder, listOf(androidJar.toPath()))
		builderClass
			.getMethod("setMinApiLevel", Int::class.javaPrimitiveType)
			.invoke(builder, minApi)
		builderClass
			.getMethod("setOutput", Path::class.java, outputModeClass)
			.invoke(builder, outDir, dexIndexed)
		val command = builderClass.getMethod("build").invoke(builder)

		loader
			.loadClass("com.android.tools.r8.D8")
			.getMethod("run", commandClass)
			.invoke(null, command)
	}

	/**
	 * Mirrors every `.class` under [classesDirs] into [openedRoot] with ACC_FINAL
	 * cleared. Later roots overwrite earlier ones on a path collision (compile output
	 * first, proxy classes second - no overlap in practice).
	 */
	private fun openClasses(
		classesDirs: List<File>,
		openedRoot: File,
	): List<Path> {
		openedRoot.deleteRecursively()
		val opened = LinkedHashMap<Path, Path>()
		for (dir in classesDirs.filter { it.isDirectory }) {
			val base = dir.toPath()
			Files.walk(base).use { stream ->
				stream.filter { it.extension == "class" }.forEach { classFile ->
					val target = openedRoot.toPath().resolve(base.relativize(classFile))
					Files.createDirectories(target.parent)
					Files.write(target, FinalStripper.strip(Files.readAllBytes(classFile)))
					opened[base.relativize(classFile)] = target
				}
			}
		}
		return opened.values.toList()
	}

	override fun close() {
		loader.close()
	}
}
