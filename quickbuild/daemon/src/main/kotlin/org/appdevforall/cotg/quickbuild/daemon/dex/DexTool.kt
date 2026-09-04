package org.appdevforall.cotg.quickbuild.daemon.dex

import org.appdevforall.cotg.quickbuild.protocol.DexStats
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/**
 * Runs D8 over compiled class files to produce `classes.dex`. The r8 jar comes from the
 * device's provisioned build-tools at configure time and is loaded through its own
 * [URLClassLoader], with every call made reflectively, so the daemon needs no AGP or r8 build
 * dependency and works against whatever build-tools version the device ships.
 *
 * @param d8Jar the device's `lib/d8.jar`; opened into a private class loader here and not
 *   retained, so the caller may not swap it without a new [DexTool].
 * @property androidJar the platform jar, passed to d8 as library (not program) input.
 * @property minApi the payload's `minSdkVersion`, which decides what d8 desugars.
 */
class DexTool(
	d8Jar: File,
	private val androidJar: File,
	private val minApi: Int,
) : AutoCloseable {
	/** Outcome of one dex run. */
	sealed interface Result {
		/**
		 * D8 produced a dex, with the timings and counts the run cost.
		 *
		 * @property dexFile the emitted `classes.dex`, verified to exist before this is built.
		 * @property stripMillis wall time of the ACC_FINAL-stripping mirror pass.
		 * @property d8Millis wall time of the d8 invocation itself.
		 * @property stats what the run processed; both steps cover the whole class tree every
		 *   build, so their cost scales with these counts rather than with the edit's size.
		 */
		data class Success(
			val dexFile: File,
			val stripMillis: Long = 0,
			val d8Millis: Long = 0,
			val stats: DexStats = DexStats(),
		) : Result

		/**
		 * The run produced no usable dex.
		 *
		 * @property message caller-facing reason - no input classes, a d8 error, a payload d8
		 *   had to split across several dex files, or an r8 jar whose layout does not match
		 *   what the reflective calls expect.
		 */
		data class Failed(
			val message: String,
		) : Result
	}

	private val loader = URLClassLoader(arrayOf(d8Jar.toURI().toURL()), DexTool::class.java.classLoader)

	/**
	 * Dexes every `.class` under [classesDirs] into `<outDir>/classes.dex`, first clearing
	 * ACC_FINAL from each class ([FinalStripper]) so the payload matches the gen-0 baseline's
	 * opened classes and the proxies' `extends` stays verifiable.
	 *
	 * @param classesDirs roots walked recursively; a non-directory entry is skipped, and a later
	 *   root overwrites an earlier one on the same relative path.
	 * @param outDir created if absent; receives `classes.dex` and the `opened-classes` mirror,
	 *   both wiped at the start of every run.
	 * @return [Result.Failed] when no `.class` was found, when d8 threw, when d8 exited clean
	 *   without writing a dex, or when d8 split the payload across more than one dex.
	 */
	fun dex(
		classesDirs: List<File>,
		outDir: File,
	): Result {
		outDir.mkdirs()
		// The dex count after the run is the only signal that d8 split the payload, so the dir
		// must hold nothing but this run's output. The r8 jar comes from whatever build-tools
		// the device provisioned, and while the ones measured here do clear stale dex files
		// themselves, that is not a documented guarantee to inherit a correctness check from.
		dexFilesIn(outDir).forEach { it.delete() }
		val stripStartedAt = System.currentTimeMillis()
		val opened = openClasses(classesDirs, File(outDir, "opened-classes"))
		val stripMillis = System.currentTimeMillis() - stripStartedAt
		val classFiles = opened.paths
		if (classFiles.isEmpty()) {
			return Result.Failed("no .class files found under: ${classesDirs.joinToString()}")
		}
		val diagnostics = D8DiagnosticsCollector()
		return try {
			val d8StartedAt = System.currentTimeMillis()
			runD8(classFiles, outDir.toPath(), diagnostics)
			val d8Millis = System.currentTimeMillis() - d8StartedAt
			val dexFiles = dexFilesIn(outDir)
			val failure = dexFailureReason(dexFiles, outDir)
			if (failure != null) {
				Result.Failed(failure)
			} else {
				Result.Success(
					dexFiles.single(),
					stripMillis = stripMillis,
					d8Millis = d8Millis,
					stats = DexStats(classFiles = classFiles.size, classBytes = opened.bytes),
				)
			}
		} catch (e: InvocationTargetException) {
			Result.Failed(d8FailureMessage(e, diagnostics.errors))
		} catch (e: ReflectiveOperationException) {
			Result.Failed("d8 jar is not usable (wrong build-tools layout?): ${e.message}")
		}
	}

	/**
	 * The caller-facing message for a d8 compilation failure. The exception's own message is
	 * near-useless (typically just "Compilation failed to complete"); the real reasons -
	 * duplicate class, unsupported class file version, malformed input - arrive as error
	 * diagnostics on the [D8DiagnosticsCollector], so they are appended, bounded so one
	 * pathological run cannot flood the response.
	 *
	 * @param e the reflective d8 failure; its cause's message leads.
	 * @param errors the run's collected error diagnostics, possibly empty.
	 * @return one message carrying the cause and every collected error, newline-separated.
	 */
	private fun d8FailureMessage(
		e: InvocationTargetException,
		errors: List<String>,
	): String {
		val cause = "d8 failed: ${e.cause?.message ?: e.cause?.javaClass?.name ?: e.message}"
		if (errors.isEmpty()) return cause
		return cause + "\n" + errors.joinToString("\n").take(MAX_DIAGNOSTIC_CHARS)
	}

	/**
	 * Builds and runs a D8 command reflectively against the device's r8 jar.
	 *
	 * @param classFiles the already-stripped `.class` copies, passed as d8 program inputs.
	 * @param outDir d8's output dir, written in `DexIndexed` mode.
	 * @param diagnostics receives the run's diagnostics; without it d8 prints its real failure
	 *   reasons to the default handler's stderr, which the client only ever logs.
	 * @throws java.lang.reflect.InvocationTargetException wrapping any d8 compilation error.
	 * @throws ReflectiveOperationException when the r8 jar does not expose the expected API.
	 */
	private fun runD8(
		classFiles: List<Path>,
		outDir: Path,
		diagnostics: D8DiagnosticsCollector,
	) {
		val commandClass = loader.loadClass("com.android.tools.r8.D8Command")
		val outputModeClass = loader.loadClass("com.android.tools.r8.OutputMode")
		val handlerClass = loader.loadClass("com.android.tools.r8.DiagnosticsHandler")
		// firstOrNull, not first: a NoSuchElementException here is neither of dex()'s catch arms,
		// so an r8 whose OutputMode lost the constant would surface as an internal error rather
		// than the dex failure Result.Failed's KDoc promises for a layout mismatch.
		val dexIndexed =
			outputModeClass.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == "DexIndexed" }
				?: throw ReflectiveOperationException("OutputMode has no DexIndexed constant")

		val handler = Proxy.newProxyInstance(loader, arrayOf(handlerClass), diagnostics)
		val builder = commandClass.getMethod("builder", handlerClass).invoke(null, handler)
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
	 *
	 * @param classesDirs roots to mirror, in precedence order; non-directories are skipped.
	 * @param openedRoot deleted recursively first, so it must not be a caller-owned dir.
	 * @return the stripped copies in first-seen path order, and the total bytes read.
	 */
	private fun openClasses(
		classesDirs: List<File>,
		openedRoot: File,
	): Opened {
		openedRoot.deleteRecursively()
		val opened = LinkedHashMap<Path, Path>()
		var bytes = 0L
		for (dir in classesDirs.filter { it.isDirectory }) {
			val base = dir.toPath()
			Files.walk(base).use { stream ->
				stream.filter { it.extension == "class" }.forEach { classFile ->
					val target = openedRoot.toPath().resolve(base.relativize(classFile))
					Files.createDirectories(target.parent)
					val original = Files.readAllBytes(classFile)
					bytes += original.size
					Files.write(target, FinalStripper.strip(original))
					opened[base.relativize(classFile)] = target
				}
			}
		}
		return Opened(opened.values.toList(), bytes)
	}

	/**
	 * What one [openClasses] pass produced: the stripped copies, and the bytes it read.
	 *
	 * @property paths absolute paths under the opened root, deduplicated by relative path.
	 * @property bytes size of the originals read, not of the rewritten copies.
	 */
	private data class Opened(
		val paths: List<Path>,
		val bytes: Long,
	)

	/** Closes the r8 class loader; the instance cannot dex afterwards. */
	override fun close() {
		loader.close()
	}

	/**
	 * Stands in for r8's `DiagnosticsHandler` behind a [Proxy], collecting the error messages
	 * of one d8 run - so a failed dex can surface WHY (duplicate class, unsupported class file
	 * version, malformed input) instead of the exception's generic "Compilation failed to
	 * complete". Reflective throughout: it may reference no r8 type, since r8 loads through
	 * [DexTool]'s private class loader.
	 *
	 * `internal` so the reflective message extraction and the pass-through arms are
	 * unit-testable against fake handler/diagnostic interfaces - real d8 needs a host
	 * toolchain.
	 */
	internal class D8DiagnosticsCollector : InvocationHandler {
		/** Messages of the run's error diagnostics, in report order. */
		val errors = mutableListOf<String>()

		override fun invoke(
			proxy: Any,
			method: Method,
			args: Array<out Any>?,
		): Any? {
			val argument = args?.firstOrNull()
			return when (method.name) {
				"error" -> {
					if (argument != null) errors += diagnosticMessage(method, argument)
					null
				}

				// Keep whatever level d8 proposed - returning null here would NPE inside d8.
				"modifyDiagnosticsLevel" -> {
					argument
				}

				// Object's methods reach the handler too on a Proxy.
				"hashCode" -> {
					System.identityHashCode(proxy)
				}

				"equals" -> {
					proxy === argument
				}

				"toString" -> {
					"D8DiagnosticsCollector"
				}

				// warning/info, and anything the interface grows later: droppable for a failure
				// report. Void methods take null; others echo a compatible argument so a future
				// pass-through default keeps working.
				else -> {
					if (method.returnType == Void.TYPE) {
						null
					} else {
						// Fails loudly rather than returning null, for the same reason the daemon
						// fails loudly on any input it cannot answer for: null is not a legal
						// answer for a primitive return type - isInstance is false for every
						// primitive, so int foo() would fall through here - and d8 would unbox it
						// into a NullPointerException raised inside its own call, blamed on d8
						// rather than on this arm. A guessed value would be worse still: it would
						// be a silent wrong answer to a question we do not understand.
						args?.firstOrNull { method.returnType.isInstance(it) }
							?: error(
								"r8 called ${method.name}, which this collector has no arm for and " +
									"cannot answer: it returns ${method.returnType.name} and none of " +
									"its arguments fit. Add an arm for it in D8DiagnosticsCollector.",
							)
					}
				}
			}
		}

		/**
		 * Reads `getDiagnosticMessage()` - and, best-effort, the origin - through the PUBLIC
		 * r8 `Diagnostic` interface, which is the handler method's parameter type. Never through
		 * the argument's own class: d8's diagnostic implementations are typically
		 * package-private, and invoking a public method through a non-public class throws
		 * `IllegalAccessException`.
		 *
		 * @param method the intercepted handler method, whose parameter type is the interface.
		 * @param diagnostic the reported diagnostic object.
		 * @return the diagnostic's message, origin-prefixed when one is available.
		 */
		private fun diagnosticMessage(
			method: Method,
			diagnostic: Any,
		): String {
			val diagnosticType = method.parameterTypes.firstOrNull() ?: return diagnostic.toString()
			val message =
				runCatching {
					diagnosticType.getMethod("getDiagnosticMessage").invoke(diagnostic) as? String
				}.getOrNull() ?: diagnostic.toString()
			val origin =
				runCatching {
					diagnosticType.getMethod("getOrigin").invoke(diagnostic)?.toString()
				}.getOrNull()
			return if (origin.isNullOrBlank() || origin == "unknown") message else "$origin: $message"
		}
	}

	companion object {
		/** Cap on the collected-diagnostics tail of a d8 failure message. */
		private const val MAX_DIAGNOSTIC_CHARS = 4000

		/** `classes.dex`, `classes2.dex`, ... - d8's DexIndexed output names, and nothing else. */
		private val DEX_FILE_NAME = Regex("""classes\d*\.dex""")

		/**
		 * The dex files d8 has written into [outDir], `classes.dex` first.
		 *
		 * @param outDir the run's output dir; a dir that does not exist yet reads as empty.
		 */
		private fun dexFilesIn(outDir: File): List<File> =
			outDir
				.listFiles { file -> file.isFile && DEX_FILE_NAME.matches(file.name) }
				?.sortedBy { it.name }
				.orEmpty()

		/**
		 * Why [dexFiles] is not a deployable result, or null when it is the one dex the deploy path
		 * can carry. `internal` so the split case is testable - real d8 needs 64K method refs to split.
		 *
		 * A split payload has to fail: d8 splits silently and exits clean past the per-dex method-ref
		 * limit, and the runtime only ever loads `classes.dex`, so shipping it would surface as
		 * `NoClassDefFoundError` against a green build.
		 *
		 * @param dexFiles what [dexFilesIn] found after the d8 run.
		 * @param outDir named in the message, since the caller sees only the message.
		 */
		internal fun dexFailureReason(
			dexFiles: List<File>,
			outDir: File,
		): String? =
			when {
				dexFiles.isEmpty() -> {
					"d8 reported success but produced no classes.dex in $outDir"
				}

				dexFiles.size > 1 -> {
					"payload too large for one dex: d8 split it into ${dexFiles.joinToString { it.name }}. " +
						"Quick Build deploys a single dex, so this payload needs a standard build."
				}

				else -> {
					null
				}
			}
	}
}
