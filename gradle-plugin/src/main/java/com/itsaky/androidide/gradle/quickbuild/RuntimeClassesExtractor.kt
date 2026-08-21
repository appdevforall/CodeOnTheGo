package com.itsaky.androidide.gradle.quickbuild

import org.gradle.api.GradleException
import java.io.File
import java.io.IOException
import java.util.jar.JarFile

/**
 * Unpacks classes.jar from runtime AARs so javac and D8 can read them; neither accepts an
 * AAR on a classpath.
 */
internal object RuntimeClassesExtractor {
	/**
	 * Extracts each AAR's classes.jar into [outputDir], named `<aar-name>-classes.jar`.
	 * Non-AAR files and AARs without a classes.jar are skipped.
	 *
	 * @param aars candidate runtime artifacts; anything without an `.aar` extension is ignored
	 *   rather than rejected, so a mixed jar/aar classpath can be passed straight through.
	 * @param outputDir destination for the extracted jars; must already exist, and existing files
	 *   of the same name are overwritten.
	 * @return the extracted jars, in input order
	 * @throws org.gradle.api.GradleException if an AAR cannot be read as a zip.
	 */
	fun extract(
		aars: Collection<File>,
		outputDir: File,
	): List<File> = aars.filter { it.extension == "aar" }.mapNotNull { aar -> extractClassesJar(aar, outputDir) }

	/**
	 * Copies one AAR's classes.jar out to `<aar-name>-classes.jar`.
	 *
	 * @param aar the AAR to open as a zip; its name without the extension prefixes the copy.
	 * @param outputDir destination directory for the copy.
	 * @return the written jar, or null if the AAR holds no classes.jar (a resource-only library).
	 * @throws org.gradle.api.GradleException if the AAR cannot be read as a zip.
	 */
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
