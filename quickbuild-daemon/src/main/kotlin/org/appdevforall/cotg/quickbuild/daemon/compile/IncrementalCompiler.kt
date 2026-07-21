package org.appdevforall.cotg.quickbuild.daemon.compile

import org.appdevforall.cotg.quickbuild.daemon.protocol.Diagnostic
import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.CompilationService
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinLogger
import org.jetbrains.kotlin.buildtools.api.ProjectId
import org.jetbrains.kotlin.buildtools.api.SourcesChanges
import org.jetbrains.kotlin.buildtools.api.jvm.ClassSnapshotGranularity
import org.jetbrains.kotlin.buildtools.api.jvm.ClasspathSnapshotBasedIncrementalCompilationApproachParameters
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Incremental Kotlin (+ plain Java) compilation via the Kotlin Build Tools API. A
 * one-line edit recompiles ~one file instead of the whole app, which is what keeps the
 * quick-build hot loop flat as the project grows (plan 2.3, decision D4).
 *
 * The BTA incremental engine has sharp edges, re-derived from the ADFA-4128 spike and
 * documented in quick-build/README.md - each is load-bearing here:
 * - Drive with [SourcesChanges.Known]; `ToBeCalculated` silently falls back to a full
 *   compile (UNKNOWN_CHANGES_IN_GRADLE_INPUTS).
 * - The shrunk snapshot MUST be exactly `<rootProjectDir>/shrunk-classpath-snapshot.bin`
 *   - the engine derives that path from `setRootProjectDir` and a mismatch means every
 *   build silently degrades to non-incremental (CLASSPATH_SNAPSHOT_NOT_FOUND) while
 *   still compiling correctly. We point rootProjectDir at the daemon work dir, not the
 *   user's project, so the engine's artifacts never pollute user sources.
 * - The caller passes ALL sources as changed on the first build to seed the IC caches.
 * - `assureNoClasspathSnapshotsChanges(true)` only once the shrunk snapshot exists: it
 *   skips per-build re-verification of every classpath entry (the dominant cost on a
 *   real resolved classpath), valid because the session classpath is fixed - but before
 *   the first build the engine needs the full comparison to seed.
 *
 * Java sources: kotlinc sees `.java` files for symbol resolution (so Kotlin may call a
 * same-module Java class), then the JDK's javac compiles them for real after Kotlin,
 * against the same classpath plus the Kotlin output (so Java may also call Kotlin), into
 * the same output directory. javac's pass is not incremental - user projects here are
 * Kotlin-first and stray Java files are small - and any `.java` file in `changedFiles`
 * forces a full (not incremental) Kotlin recompile too, since the incremental engine can't
 * see java-side ABI changes on its own (see `compileKotlin`'s doc below).
 *
 * Kotlin 2.3 deprecates this [CompilationService] entry point in favor of the new
 * `KotlinToolchains` API; it still works and matches the spike-proven recipe above, so
 * v1 stays on it - migrating is a contained follow-up inside this class.
 */
@OptIn(ExperimentalBuildToolsApi::class)
class IncrementalCompiler(
	classpathJars: List<File>,
	private val workDir: Path,
	compilerPluginJars: List<File> = emptyList(),
) {
	sealed interface Result {
		/**
		 * @property classesDir single merged output dir (Kotlin + Java classes).
		 * @property changedClassFiles the .class files this compile emitted or rewrote,
		 *   as '/'-separated paths relative to [classesDir]. Feeds the CoGo-side deploy
		 *   policy (restart vs recreate), so it must never under-report: computed by
		 *   diffing a pre/post snapshot of the output tree on (size, nanosecond mtime).
		 */
		data class Success(
			val classesDir: File,
			val warnings: List<Diagnostic>,
			val changedClassFiles: List<String>,
		) : Result

		data class Failed(
			val diagnostics: List<Diagnostic>,
		) : Result
	}

	private val service = CompilationService.loadImplementation(IncrementalCompiler::class.java.classLoader)
	private val projectId = ProjectId.ProjectUUID(UUID.randomUUID())
	private val icCachesDir = workDir.resolve("ic")
	private val classesDir = workDir.resolve("classes")
	private val shrunkSnapshot = workDir.resolve("shrunk-classpath-snapshot.bin").toFile()
	private val classpathSnapshots: List<File>
	private val classpathString = classpathJars.joinToString(File.pathSeparator) { it.absolutePath }
	private val classpathFiles = classpathJars

	// One -Xplugin per jar: the BTA hands free-form kotlinc args through to the
	// (incremental) compile, so compiler plugins ride the same route as on a CLI
	// invocation. Session-fixed, like the classpath.
	private val pluginArguments = compilerPluginJars.map { "-Xplugin=${it.absolutePath}" }

	/** Raw logger lines from the last compile, level-tagged; test/debug visibility only. */
	var lastCompileLog: List<String> = emptyList()
		private set

	init {
		Files.createDirectories(icCachesDir)
		Files.createDirectories(classesDir)
		val snapshotDir = workDir.resolve("cp-snap")
		Files.createDirectories(snapshotDir)
		// Snapshot the fixed session classpath once; a classpath change is a session
		// invalidation (new configure), never an in-place mutation.
		classpathSnapshots =
			classpathJars.map { jar ->
				val snapshot = snapshotDir.resolve(jar.name + ".snap").toFile()
				service
					.calculateClasspathSnapshot(jar, ClassSnapshotGranularity.CLASS_MEMBER_LEVEL)
					.saveSnapshot(snapshot)
				snapshot
			}
	}

	fun compile(
		allSources: List<File>,
		changedFiles: List<File>,
	): Result {
		val before = snapshotClassOutputs()
		val logger = CollectingLogger()
		val kotlinResult = compileKotlin(allSources, changedFiles, logger)
		lastCompileLog = logger.lines
		if (kotlinResult != CompilationResult.COMPILATION_SUCCESS) {
			val diagnostics = logger.errors.map { KotlincDiagnosticsParser.parse(it, Diagnostic.Severity.ERROR) }
			return Result.Failed(
				diagnostics.ifEmpty {
					listOf(Diagnostic(Diagnostic.Severity.ERROR, "Kotlin compilation failed: $kotlinResult"))
				},
			)
		}

		val javaSources = allSources.filter { it.extension == "java" }
		val javaDiagnostics =
			if (javaSources.isEmpty()) {
				JavaCompileStep.Result(success = true, diagnostics = emptyList())
			} else {
				JavaCompileStep.compile(
					javaSources = javaSources,
					classpath = classpathFiles + classesDir.toFile(),
					outputDir = classesDir.toFile(),
				)
			}
		val warnings = logger.warnings.map { KotlincDiagnosticsParser.parse(it, Diagnostic.Severity.WARNING) }
		if (!javaDiagnostics.success) {
			return Result.Failed(javaDiagnostics.diagnostics + warnings)
		}
		return Result.Success(
			classesDir = classesDir.toFile(),
			warnings = warnings + javaDiagnostics.diagnostics,
			changedClassFiles = changedClassOutputs(before),
		)
	}

	/**
	 * Snapshot of every .class under [classesDir]: relative path -> (size, mtime).
	 * Nanosecond [java.nio.file.attribute.FileTime] (not millis) so a rewrite within
	 * the same millisecond still diffs - an under-report here would let a changed
	 * component class skip the restart policy, a never-stale violation.
	 */
	private fun snapshotClassOutputs(): Map<String, Pair<Long, java.nio.file.attribute.FileTime>> {
		val root = classesDir
		if (!Files.isDirectory(root)) return emptyMap()
		val snapshot = HashMap<String, Pair<Long, java.nio.file.attribute.FileTime>>()
		Files.walk(root).use { paths ->
			paths.forEach { path ->
				if (Files.isRegularFile(path) && path.toString().endsWith(".class")) {
					val rel = root.relativize(path).toString().replace(java.io.File.separatorChar, '/')
					snapshot[rel] = Files.size(path) to Files.getLastModifiedTime(path)
				}
			}
		}
		return snapshot
	}

	private fun changedClassOutputs(before: Map<String, Pair<Long, java.nio.file.attribute.FileTime>>): List<String> =
		snapshotClassOutputs()
			.filter { (rel, stamp) -> before[rel] != stamp }
			.keys
			.sorted()

	private fun compileKotlin(
		allSources: List<File>,
		changedFiles: List<File>,
		logger: CollectingLogger,
	): CompilationResult {
		val kotlinSources = allSources.filter { it.extension != "java" }
		val javaSources = allSources.filter { it.extension == "java" }
		if (kotlinSources.isEmpty()) return CompilationResult.COMPILATION_SUCCESS

		// A Kotlin file calling a same-module Java class (not yet on any jar classpath) needs
		// kotlinc to see that .java source for symbol resolution - passing javaSources into
		// compileJvm's source list below (not the `-Xjava-source-roots` CLI flag, which this
		// BTA entry point silently ignores) makes that resolution work without asking kotlinc
		// to emit bytecode for them (JavaCompileStep does that afterward). But BTA's incremental
		// engine has no ABI/dependency tracking over those java sources (unlike the
		// jar-classpath-snapshot machinery it uses for external deps): if only a .java file is
		// in `changedFiles`, filtering it out of SourcesChanges.Known would tell the engine
		// "nothing changed" and it would skip recompiling Kotlin callers entirely, leaving their
		// .class files calling the OLD Java signature - a stale-bytecode bug the NEVER-STALE
		// invariant forbids. So: any .java file in the changed set forces every Kotlin source to
		// be treated as changed (a full, but correct, Kotlin recompile) rather than trusting the
		// (java-blind) incremental filter.
		val changedHasJava = changedFiles.any { it.extension == "java" }
		val kotlinChanged = if (changedHasJava) kotlinSources else changedFiles.filter { it.extension != "java" }

		val strategy = service.makeCompilerExecutionStrategyConfiguration().useInProcessStrategy()
		val config = service.makeJvmCompilationConfiguration().useLogger(logger)
		val icConfig = config.makeClasspathSnapshotBasedIncrementalCompilationConfiguration()
		icConfig.setRootProjectDir(workDir.toFile())
		icConfig.setBuildDir(classesDir.toFile())
		if (shrunkSnapshot.exists()) {
			icConfig.assureNoClasspathSnapshotsChanges(true)
		}
		val parameters =
			ClasspathSnapshotBasedIncrementalCompilationApproachParameters(classpathSnapshots, shrunkSnapshot)
		val changes = SourcesChanges.Known(kotlinChanged, emptyList())
		config.useIncrementalCompilation(icCachesDir.toFile(), changes, parameters, icConfig)

		val arguments =
			listOf(
				"-classpath",
				classpathString,
				"-d",
				classesDir.toString(),
				"-jvm-target",
				JVM_TARGET,
				"-module-name",
				"quickbuild-payload",
				"-no-stdlib",
				"-no-reflect",
				"-nowarn",
			) + pluginArguments
		return service.compileJvm(projectId, strategy, config, kotlinSources + javaSources, arguments)
	}

	/** Collects compiler output per channel; errors feed structured diagnostics. */
	private class CollectingLogger : KotlinLogger {
		val errors = mutableListOf<String>()
		val warnings = mutableListOf<String>()
		val lines = mutableListOf<String>()

		override val isDebugEnabled: Boolean = true

		override fun error(
			msg: String,
			throwable: Throwable?,
		) {
			errors += msg
			lines += "e: $msg"
		}

		override fun warn(
			msg: String,
			throwable: Throwable?,
		) {
			warnings += msg
			lines += "w: $msg"
		}

		override fun info(msg: String) {
			lines += "i: $msg"
		}

		override fun debug(msg: String) {
			lines += "d: $msg"
		}

		override fun lifecycle(msg: String) {
			lines += "l: $msg"
		}
	}

	companion object {
		// ART (via d8 desugaring) handles Java-17 bytecode; matches the bundled JDK.
		private const val JVM_TARGET = "17"
	}
}
