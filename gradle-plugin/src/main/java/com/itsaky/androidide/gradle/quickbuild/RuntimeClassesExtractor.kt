package com.itsaky.androidide.gradle.quickbuild

import org.gradle.api.GradleException
import java.io.File
import java.io.IOException
import java.util.jar.JarFile

/**
 * Pulls classes.jar out of runtime AARs so javac and D8 can read them (neither accepts
 * an AAR on a classpath). Pure logic so it is unit-testable; the dex task supplies its
 * own temp dir as [extract]'s output.
 */
internal object RuntimeClassesExtractor {
	/**
	 * The classes.jar of each AAR in [aars], extracted into [outputDir] as
	 * `<aar-name>-classes.jar`. Non-AAR files and AARs without a classes.jar are skipped.
	 */
	fun extract(
		aars: Collection<File>,
		outputDir: File,
	): List<File> = aars.filter { it.extension == "aar" }.mapNotNull { aar -> extractClassesJar(aar, outputDir) }

	private fun extractClassesJar(
		aar: File,
		outputDir: File,
	): File? {
		try {
			JarFile(aar).use { jar ->
				val entry = jar.getEntry("classes.jar") ?: return null
				val out = File(outputDir, "${aar.nameWithoutExtension}-classes.jar")
				jar.getInputStream(entry).use { input ->
					out.outputStream().use { input.copyTo(it) }
				}
				return out
			}
		} catch (e: IOException) {
			throw GradleException(
				"Quick Build: cannot read the runtime AAR at '${aar.absolutePath}' (corrupt or truncated?)",
				e,
			)
		}
	}
}
