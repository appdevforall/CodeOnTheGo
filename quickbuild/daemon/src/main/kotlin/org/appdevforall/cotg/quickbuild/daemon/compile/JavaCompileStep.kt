package org.appdevforall.cotg.quickbuild.daemon.compile

import org.appdevforall.cotg.quickbuild.protocol.Diagnostic
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
	/**
	 * Outcome of one javac run; [diagnostics] carries warnings even on success.
	 *
	 * @property success javac's own verdict; false also covers a runtime with no compiler.
	 * @property diagnostics every message javac produced, errors and warnings alike, so the
	 *   caller must filter by severity rather than assume a non-empty list means failure.
	 */
	data class Result(
		val success: Boolean,
		val diagnostics: List<Diagnostic>,
	)

	/**
	 * Compiles [javaSources] into [outputDir].
	 *
	 * @param javaSources every `.java` in the module, not just the edited ones - this pass is
	 *   not incremental.
	 * @param classpath the compile classpath; the caller adds the Kotlin output dir so Java
	 *   can reference Kotlin classes.
	 * @param outputDir the same dir the Kotlin pass wrote to, so one tree holds both languages.
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
			val options = javacOptions(classpath, outputDir)
			val task = compiler.getTask(StringWriter(), manager, collector, options, null, units)
			val success = task.call()
			return Result(success, collector.diagnostics.map { it.toProtocol() })
		}
	}

	/**
	 * The javac options for one compile.
	 *
	 * `internal` so the flags are testable: the host JDK compiles this code to the same class
	 * file version with or without `--release`, so nothing else can tell whether it was passed.
	 *
	 * @param classpath the compile classpath, joined with the platform separator.
	 * @param outputDir the shared Kotlin/Java output tree.
	 * @return the option list handed to [javax.tools.JavaCompiler.getTask].
	 */
	internal fun javacOptions(
		classpath: List<File>,
		outputDir: File,
	): List<String> =
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
			// Pins the BYTECODE level to what kotlinc targets (-jvm-target): without it a
			// daemon running on JDK 21 emits major-65 classes next to Kotlin's major-61 in
			// one tree. It does NOT pin the platform API surface to the project's: under
			// --release, java.* resolves against the JDK's own release-17 ct.sym signatures,
			// and android.jar reaches this compile only through -classpath, which the
			// platform shadows. So a .java calling a JVM-only API (ProcessHandle,
			// Collectors.teeing) compiles green here.
			//
			// Every documented way to narrow it back was tried on javac 17 against
			// android-36's android.jar [measured on this Mac, 2026-09-03]. -bootclasspath is
			// refused above target 8 ("option --boot-class-path not allowed with target 11"
			// and the same at 17); --system none cannot find java.lang, because android.jar
			// is a jar and not a system image; --release with --patch-module java.base still
			// compiles ProcessHandle green, so ct.sym wins. Only -source 8 -target 8 with
			// -bootclasspath narrows the surface, and that is the bytecode level this flag
			// exists to prevent.
			//
			// The standard build IS stricter. AGP 8.11's JavaCompile on this IDE's Java-17
			// template project rejects `ProcessHandle.current()` with "cannot find symbol"
			// while `Collectors.teeing` passes, because android-36's android.jar has the latter
			// and not the former [measured on this Mac, 2026-09-04, Gradle 8.14.3]. It gets
			// there with `-source 17 -target 17 --system <image>`, where the image is a jlink'd
			// module image AGP builds (JdkImageTransform) from the platform's
			// core-for-system-modules.jar. So a .java calling a JVM-only java.* API compiles
			// green here and fails in the standard build. Closing that gap means shipping or
			// building such an image on the device; tracked as ADFA-5502.
			"--release",
			IncrementalCompiler.JVM_TARGET,
		)

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
