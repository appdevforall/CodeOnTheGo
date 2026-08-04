package org.appdevforall.cotg.quickbuild.daemon.compile

import org.appdevforall.cotg.quickbuild.daemon.protocol.Diagnostic
import java.io.File
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider

/**
 * Compiles the project's `.java` sources with the JDK's in-process javac, after Kotlin.
 * javac's structured [javax.tools.Diagnostic]s map onto the protocol shape directly, so
 * this path needs no text parsing.
 */
object JavaCompileStep {
	/** Outcome of one javac run; [diagnostics] carries warnings even on success. */
	data class Result(
		val success: Boolean,
		val diagnostics: List<Diagnostic>,
	)

	/**
	 * Compiles [javaSources] into [outputDir].
	 *
	 * @param classpath the compile classpath; the caller adds the Kotlin output dir so Java
	 *   can reference Kotlin classes.
	 * @return a failed [Result] rather than an exception when the runtime has no javac.
	 */
	fun compile(
		javaSources: List<File>,
		classpath: List<File>,
		outputDir: File,
	): Result {
		val compiler =
			ToolProvider.getSystemJavaCompiler()
				?: return Result(
					success = false,
					diagnostics =
						listOf(
							Diagnostic(Diagnostic.Severity.ERROR, "no system Java compiler available (JRE-only runtime?)"),
						),
				)
		val collector = DiagnosticCollector<JavaFileObject>()
		val fileManager = compiler.getStandardFileManager(collector, Locale.ROOT, StandardCharsets.UTF_8)
		fileManager.use { manager ->
			val units = manager.getJavaFileObjectsFromFiles(javaSources)
			val options =
				listOf(
					"-classpath",
					classpath.joinToString(File.pathSeparator) { it.absolutePath },
					"-d",
					outputDir.absolutePath,
					// Annotation processing is a full-Gradle-build concern;
					// running processors here would silently diverge from the real build.
					"-proc:none",
					"-encoding",
					"UTF-8",
				)
			val task = compiler.getTask(StringWriter(), manager, collector, options, null, units)
			val success = task.call()
			return Result(success, collector.diagnostics.map { it.toProtocol() })
		}
	}

	private fun javax.tools.Diagnostic<out JavaFileObject>.toProtocol(): Diagnostic =
		Diagnostic(
			severity =
				when (kind) {
					javax.tools.Diagnostic.Kind.ERROR -> Diagnostic.Severity.ERROR
					else -> Diagnostic.Severity.WARNING
				},
			message = getMessage(Locale.ROOT),
			file = source?.name,
			line = lineNumber.takeIf { it != javax.tools.Diagnostic.NOPOS }?.toInt(),
			column = columnNumber.takeIf { it != javax.tools.Diagnostic.NOPOS }?.toInt(),
		)
}
