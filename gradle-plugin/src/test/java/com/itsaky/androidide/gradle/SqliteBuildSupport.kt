package com.itsaky.androidide.gradle

import java.io.File
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Prints what each variant's runtime classpath declares, without resolving anything. */
internal fun printDepsInitScript(dir: File): File =
	File(dir, "print-deps.init.gradle").apply {
		writeText(
			"""
			allprojects { p ->
				p.afterEvaluate {
					if (p.plugins.hasPlugin('com.android.application')) {
						p.tasks.register('printRuntimeDeps') {
							doLast {
								['demoDebug', 'fullDebug', 'demoRelease', 'fullRelease'].each { v ->
									p.configurations.getByName(v + 'RuntimeClasspath').allDependencies.each { d ->
										if (d instanceof org.gradle.api.artifacts.FileCollectionDependency) {
											d.files.each { println "RTDEP ${'$'}v file ${'$'}{it.name}" }
										} else {
											println "RTDEP ${'$'}v ${'$'}{d.group}:${'$'}{d.name}:${'$'}{d.version}"
										}
									}
								}
							}
						}
					}
				}
			}
			""".trimIndent(),
		)
	}

/** A jar or AAR file with the given entries, for stand-ins whose class bytes nobody reads. */
internal fun standIn(
	file: File,
	vararg entries: Pair<String, ByteArray>,
): File {
	file.parentFile.mkdirs()
	ZipOutputStream(file.outputStream()).use { zip ->
		for ((name, bytes) in entries) {
			zip.putNextEntry(ZipEntry(name))
			zip.write(bytes)
			zip.closeEntry()
		}
	}
	return file
}

/** A copy of the sample project in [into], without build output, with [extraBuildScript] appended to the app's script. */
internal fun copySampleProject(
	into: File,
	extraBuildScript: String,
): Path {
	val source = openProject().toFile()
	val dest = File(into, "project")
	source
		.walkTopDown()
		.onEnter { it.name != "build" && it.name != ".gradle" }
		.forEach { file ->
			val target = File(dest, file.relativeTo(source).path)
			if (file.isDirectory) target.mkdirs() else file.copyTo(target, overwrite = true)
		}
	File(dest, "app/build.gradle.kts").appendText("\n" + extraBuildScript + "\n")
	return dest.toPath()
}
