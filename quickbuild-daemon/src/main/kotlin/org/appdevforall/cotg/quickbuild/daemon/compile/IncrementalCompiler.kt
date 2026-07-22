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
 * the same output directory. This is Gradle's own two-pass shape, and it compiles genuine
 * Kotlin<->Java cycles - mutual calls, and a Java class whose supertype is a Kotlin source
 * in the same pass (corpus app `mixed-lang-cyclic`). javac's pass is not incremental: user
 * projects here are Kotlin-first and stray Java files are small.
 *
 * A `.java` edit's effect on the Kotlin side is decided by [JavaSourceAbi] - see
 * `compileKotlin` below for the rule and the conservatism it keeps.
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

	/**
	 * Java type names whose ABI moved in the last compile, forcing a full Kotlin recompile
	 * (see [kotlinFilesToCompile]). Empty when the Java side stayed ABI-stable - which is
	 * what tells a reader of a slow compile why it was slow.
	 */
	var lastJavaAbiChange: Set<String> = emptySet()
		private set

	/** Last SUCCESSFUL compile's `.java` ABI; null when unknown and Kotlin must be recompiled whole. */
	private var javaAbi: Map<File, JavaSourceAbi.FileAbi>? = null

	/** This compile's `.java` ABI, promoted to [javaAbi] only once the compile succeeds. */
	private var pendingJavaAbi: Map<File, JavaSourceAbi.FileAbi>? = null

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
		// Only a compile that fully succeeded may become the ABI baseline. A failed compile
		// leaves output the caller never deployed, so the NEXT compile has to keep seeing
		// the Java side as changed relative to the last good state - committing here rather
		// than where the snapshot is taken is what makes that true.
		javaAbi = pendingJavaAbi
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
		if (kotlinSources.isEmpty()) {
			// Nothing for a Java ABI change to invalidate; keep no baseline for it either.
			pendingJavaAbi = null
			return CompilationResult.COMPILATION_SUCCESS
		}

		// A Kotlin file calling a same-module Java class (not yet on any jar classpath) needs
		// kotlinc to see that .java source for symbol resolution - passing javaSources into
		// compileJvm's source list below (not the `-Xjava-source-roots` CLI flag, which this
		// BTA entry point silently ignores) makes that resolution work without asking kotlinc
		// to emit bytecode for them (JavaCompileStep does that afterward). But BTA's incremental
		// engine has no ABI/dependency tracking over those java sources (unlike the
		// jar-classpath-snapshot machinery it uses for external deps): telling the engine only
		// that a .java file changed says nothing, and it would skip recompiling Kotlin callers
		// entirely, leaving their .class files calling the OLD Java signature - a stale-bytecode
		// bug the NEVER-STALE invariant forbids.
		val kotlinChanged = kotlinFilesToCompile(kotlinSources, javaSources, changedFiles)

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

	/**
	 * Which Kotlin sources this compile must treat as changed, given the engine is blind to
	 * the `.java` sources it is resolving against.
	 *
	 * The rule has two cases and only two:
	 * - **No Java ABI moved** (every `.java` file's declarations hash the same as last
	 *   compile) - the Kotlin changed set is exactly the caller's Kotlin changes. This is
	 *   sound without any cross-language dependency analysis: Kotlin never inlines a Java
	 *   method body, and Java compile-time constants, which it does inline, are part of the
	 *   ABI hash. So no Kotlin bytecode can differ. This is the common edit - a Java method
	 *   body - and it now costs the same as a same-size Kotlin edit.
	 * - **Some Java ABI moved, or the ABI is unknown** (first compile of the session, no
	 *   system Java compiler, an unparseable source) - every Kotlin source is treated as
	 *   changed: a full, correct Kotlin recompile.
	 *
	 * The second case is deliberately blunt. Narrowing it needs the set of Kotlin files that
	 * depend on the changed Java types, and neither available signal is sound: BTA exposes no
	 * way to inject a non-classpath ABI change into its lookup caches (which is exactly the
	 * machinery that would answer this precisely), and seeding from Kotlin files that
	 * lexically name the changed type misses indirect dependents - a `typealias` to the Java
	 * type re-exports it under a name the dependent writes instead, and its own ABI does not
	 * move, so the engine has no reason to propagate. Both leave stale bytecode. Until one of
	 * those is closed, an ABI-changing Java edit pays a full Kotlin recompile.
	 */
	private fun kotlinFilesToCompile(
		kotlinSources: List<File>,
		javaSources: List<File>,
		changedFiles: List<File>,
	): List<File> {
		lastJavaAbiChange = emptySet()
		val kotlinChanged = changedFiles.filter { it.extension != "java" }
		val previous = javaAbi
		val current = JavaSourceAbi.snapshot(javaSources)
		pendingJavaAbi = current
		if (previous == null || current == null) return kotlinSources
		val changedTypes = JavaSourceAbi.changedTypeNames(previous, current)
		if (changedTypes.isEmpty()) return kotlinChanged
		lastJavaAbiChange = changedTypes
		return kotlinSources
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
