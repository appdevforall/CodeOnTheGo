package com.itsaky.androidide.gradle.quickbuild

import com.android.build.api.variant.BuiltArtifactsLoader
import com.android.tools.r8.CompilationFailedException
import com.android.tools.r8.CompilationMode
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider

/**
 * Rewrites the merged manifest for the test app and generates the artifacts derived from
 * it: proxy activity sources, the proxy-to-user component map (an APK asset), and the
 * manifest-info intermediate consumed by [QuickBuildSetupReportTask].
 *
 * One task for all four outputs so the proxy numbering in the manifest and in the sources
 * can never drift apart.
 */
abstract class QuickBuildGenerateSourcesTask : DefaultTask() {
	@get:InputFile
	abstract val mergedManifest: RegularFileProperty

	/** Final (suffixed) application id of the test app. */
	@get:Input
	abstract val applicationId: Property<String>

	/** FQN of the quick-build runtime's AppComponentFactory. */
	@get:Input
	abstract val appComponentFactory: Property<String>

	@get:OutputFile
	abstract val updatedManifest: RegularFileProperty

	/** Generated proxy .java sources, compiled by [QuickBuildPayloadDexTask] (not the variant). */
	@get:OutputDirectory
	abstract val proxySources: DirectoryProperty

	/** Generated assets layer carrying quickbuild/components.json. */
	@get:OutputDirectory
	abstract val generatedAssets: DirectoryProperty

	@get:OutputFile
	abstract val manifestInfoFile: RegularFileProperty

	@TaskAction
	fun generate() {
		val appId = applicationId.get()
		val transformer =
			QuickBuildManifestTransformer(
				proxyPackage = "$appId.proxies",
				appComponentFactory = appComponentFactory.get(),
			)

		val result =
			try {
				mergedManifest
					.get()
					.asFile
					.inputStream()
					.use(transformer::transform)
			} catch (e: IllegalArgumentException) {
				throw GradleException("Quick Build cannot process the merged manifest: ${e.message}", e)
			}
		transformer.writeTo(result.document, updatedManifest.get().asFile)

		val sourcesRoot = proxySources.get().asFile.cleanDirectory()
		result.activities.forEach { activity ->
			val relativePath = activity.proxyClass.replace('.', '/') + ".java"
			File(sourcesRoot, relativePath)
				.apply { parentFile.mkdirs() }
				.writeText(ProxySourceGenerator.generateSource(activity.proxyClass, activity.userClass))
		}

		val assetsRoot = generatedAssets.get().asFile.cleanDirectory()
		File(assetsRoot, "quickbuild/components.json")
			.apply { parentFile.mkdirs() }
			.writeText(QuickBuildJson.componentsJson(result.activities))

		val info =
			ManifestInfo(
				testAppId = appId,
				entryActivity = result.entryActivity,
				activities = result.activities.map { it.userClass },
			)
		manifestInfoFile
			.get()
			.asFile
			.apply { parentFile.mkdirs() }
			.writeText(QuickBuildJson.manifestInfoJson(info))

		if (result.entryActivity == null) {
			logger.warn("Quick Build: no LAUNCHER activity found in the merged manifest")
		}
		logger.lifecycle(
			"Quick Build: generated {} proxy activities for '{}'",
			result.activities.size,
			appId,
		)
	}
}

/**
 * Diverts ALL project-scope classes out of the APK: the classes pipeline receives an
 * empty jar (so the installed test app carries no user code), while the real classes are
 * copied to [payloadClasses] for [QuickBuildPayloadDexTask] and, later, the on-device
 * compile daemon's baseline.
 */
abstract class QuickBuildPayloadTransformTask : DefaultTask() {
	@get:InputFiles
	abstract val allJars: ListProperty<RegularFile>

	@get:InputFiles
	abstract val allDirectories: ListProperty<Directory>

	/**
	 * The jar handed back to the APK's classes pipeline. Carries the resource R classes
	 * (`R`, `R$*`) so they stay in the base APK: they are stable across hot edits and are
	 * referenced by base-APK library code (e.g. the injected LogSender service reads its
	 * own `R$string`), which loads on the APK classloader and can't see the payload dex.
	 * The R classes are ALSO diverted into the payload below - harmless duplication, since
	 * the payload's InMemoryDexClassLoader has the APK loader as parent, so user code
	 * resolves R from the APK and the payload copy is only there for the daemon's compile
	 * classpath.
	 */
	@get:OutputFile
	abstract val outputJar: RegularFileProperty

	/** Diverted classes: jars/N.jar for jar inputs, dirs/N/... for directory inputs. */
	@get:OutputDirectory
	abstract val payloadClasses: DirectoryProperty

	@TaskAction
	fun divert() {
		val root = payloadClasses.get().asFile.cleanDirectory()
		allJars.get().forEachIndexed { index, jar ->
			jar.asFile.copyTo(File(root, "jars/$index.jar"))
		}
		allDirectories.get().forEachIndexed { index, dir ->
			dir.asFile.copyRecursively(File(root, "dirs/$index"))
		}

		writeRetainedApkJar()
	}

	/**
	 * A `.class` entry is an R resource class when its file name is `R.class` or matches
	 * `R$<name>.class` (the nested `R.string`, `R.layout`, ... holders).
	 */
	private fun isResourceClass(entryName: String): Boolean {
		val name = entryName.substringAfterLast('/')
		return name == "R.class" || (name.startsWith("R$") && name.endsWith(".class"))
	}

	/** Collects every R class from the inputs into [outputJar] so the APK keeps them. */
	private fun writeRetainedApkJar() {
		val seen = HashSet<String>()
		JarOutputStream(outputJar.get().asFile.outputStream()).use { out ->
			// A zip must contain at least one entry even when no R classes exist.
			out.putNextEntry(JarEntry("META-INF/com.itsaky.androidide.quickbuild.diverted"))
			out.closeEntry()

			allDirectories.get().forEach { dir ->
				dir.asFile.walkTopDown().filter { it.isFile && isResourceClass(it.name) }.forEach { file ->
					val entry = file.relativeTo(dir.asFile).invariantSeparatorsPath
					if (seen.add(entry)) {
						out.putNextEntry(JarEntry(entry))
						file.inputStream().use { it.copyTo(out) }
						out.closeEntry()
					}
				}
			}
			allJars.get().forEach { jar ->
				JarFile(jar.asFile).use { jf ->
					jf.entries().asSequence().filter { !it.isDirectory && isResourceClass(it.name) }.forEach { entry ->
						if (seen.add(entry.name)) {
							out.putNextEntry(JarEntry(entry.name))
							jf.getInputStream(entry).use { it.copyTo(out) }
							out.closeEntry()
						}
					}
				}
			}
		}
	}
}

/**
 * Builds the baseline payload dex (assets/quickbuild/gen-0.dex) from the diverted project
 * classes plus the generated proxies:
 *
 * 1. strips ACC_FINAL from the diverted classes (Kotlin classes are final by default; the
 *    proxies must extend them, and the dex verifier enforces finality at runtime too);
 * 2. compiles the proxy sources against the opened classes + the variant compile classpath
 *    (a plain in-process javac - the variant's own javac would reject final superclasses,
 *    which is why the proxies are not registered as variant sources);
 * 3. runs D8 over opened classes + proxies + diverted jars into a single classes.dex.
 *
 * The compiled proxies are also persisted to [proxyClasses] so the on-device daemon can
 * reuse them in every later payload generation.
 */
abstract class QuickBuildPayloadDexTask : DefaultTask() {
	@get:InputDirectory
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val payloadClasses: DirectoryProperty

	@get:InputDirectory
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val proxySources: DirectoryProperty

	@get:Classpath
	abstract val compileClasspath: ConfigurableFileCollection

	@get:Classpath
	abstract val bootClasspath: ConfigurableFileCollection

	/**
	 * The quick-build runtime AAR. Its classes.jar goes on the proxy compile classpath:
	 * generated proxies call runtime API (QuickBuildGestures), which the variant compile
	 * classpath never carries - the AAR is injected into the RUNTIME configuration only.
	 */
	@get:Classpath
	abstract val runtimeAar: ConfigurableFileCollection

	/** Effective dex min API; at least 30 because Quick Build is gated to API 30+ devices. */
	@get:Input
	abstract val minApiLevel: Property<Int>

	/** Generated assets layer carrying quickbuild/gen-0.dex. */
	@get:OutputDirectory
	abstract val generatedAssets: DirectoryProperty

	/** Compiled proxy classes, kept for the on-device daemon's incremental payloads. */
	@get:OutputDirectory
	abstract val proxyClasses: DirectoryProperty

	@TaskAction
	fun dex() {
		val assetsRoot = generatedAssets.get().asFile.cleanDirectory()
		val proxyClassesDir = proxyClasses.get().asFile.cleanDirectory()
		val openedDir = File(temporaryDir, "opened").cleanDirectory()
		val dexDir = File(temporaryDir, "dex").cleanDirectory()

		val payloadRoot = payloadClasses.get().asFile
		val payloadJars =
			File(payloadRoot, "jars")
				.listFiles { file -> file.extension == "jar" }
				.orEmpty()
				.sortedBy { it.name }
		val payloadDirs =
			File(payloadRoot, "dirs")
				.listFiles { file -> file.isDirectory }
				.orEmpty()
				.sortedBy { it.name }

		val openedRoots =
			payloadDirs.map { dir ->
				val opened = File(openedDir, dir.name)
				dir.walkTopDown().filter { it.isFile }.forEach { file ->
					val target = File(opened, file.relativeTo(dir).path)
					target.parentFile.mkdirs()
					if (file.extension == "class") {
						target.writeBytes(ClassOpener.stripFinalModifier(file.readBytes()))
					} else {
						file.copyTo(target)
					}
				}
				opened
			}

		val runtimeClassesJars = extractRuntimeClasses()
		val proxyJavaFiles =
			proxySources
				.get()
				.asFile
				.walkTopDown()
				.filter { it.isFile && it.extension == "java" }
				.toList()
		if (proxyJavaFiles.isNotEmpty()) {
			compileProxies(
				proxyJavaFiles,
				classpath =
					bootClasspath.files + openedRoots + payloadJars +
						runtimeClassesJars + compileClasspath.files,
				outputDir = proxyClassesDir,
			)
		}

		val programFiles =
			openedRoots.flatMap { root -> root.walkTopDown().filter { it.extension == "class" } } +
				proxyClassesDir.walkTopDown().filter { it.extension == "class" } +
				payloadJars
		if (programFiles.isEmpty()) {
			logger.warn("Quick Build: no project classes found; skipping baseline payload dex")
			return
		}

		val minApi = minApiLevel.get()
		val command =
			D8Command
				.builder()
				.apply {
					programFiles.forEach { addProgramFiles(it.toPath()) }
					bootClasspath.files.forEach { addLibraryFiles(it.toPath()) }
					(runtimeClassesJars + compileClasspath.files).forEach { addClasspathFiles(it.toPath()) }
					setMinApiLevel(minApi)
					setMode(CompilationMode.DEBUG)
					setOutput(dexDir.toPath(), OutputMode.DexIndexed)
				}.build()

		try {
			D8.run(command)
		} catch (e: CompilationFailedException) {
			throw GradleException("Quick Build: dexing the baseline payload failed", e)
		}

		val dexFiles = dexDir.listFiles { file -> file.extension == "dex" }.orEmpty().sortedBy { it.name }
		when {
			dexFiles.isEmpty() ->
				throw GradleException("Quick Build: d8 produced no dex for the baseline payload")

			dexFiles.size > 1 ->
				throw GradleException(
					"Quick Build: the baseline payload needs ${dexFiles.size} dex files, but v1 " +
						"supports a single gen-0.dex; the project's own classes exceed the method budget",
				)
		}
		dexFiles.single().copyTo(File(assetsRoot, "quickbuild/gen-0.dex").apply { parentFile.mkdirs() })
	}

	/** Extracts classes.jar from each [runtimeAar] into the task temp dir (javac and D8 cannot read AARs). */
	private fun extractRuntimeClasses(): List<File> = RuntimeClassesExtractor.extract(runtimeAar.files, temporaryDir)

	private fun compileProxies(
		sources: List<File>,
		classpath: Collection<File>,
		outputDir: File,
	) {
		val compiler =
			ToolProvider.getSystemJavaCompiler()
				?: throw GradleException("Quick Build: no system Java compiler available (JRE-only JVM?)")
		val diagnostics = DiagnosticCollector<JavaFileObject>()
		compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8).use { fileManager ->
			val units = fileManager.getJavaFileObjectsFromFiles(sources)
			val args =
				listOf(
					"-proc:none",
					"-nowarn",
					"-classpath",
					classpath.joinToString(File.pathSeparator) { it.absolutePath },
					"-d",
					outputDir.absolutePath,
				)
			val ok = compiler.getTask(null, fileManager, diagnostics, args, null, units).call()
			if (!ok) {
				val details = diagnostics.diagnostics.joinToString("\n") { it.toString() }
				throw GradleException("Quick Build: compiling generated proxy activities failed:\n$details")
			}
		}
	}
}

/**
 * Writes build/quickbuild/setup.json - the setup-build handshake CoGo reads to learn the
 * test app id, entry activity, declared activities and the APK to install.
 */
abstract class QuickBuildSetupReportTask : DefaultTask() {
	@get:InputFile
	abstract val manifestInfoFile: RegularFileProperty

	@get:InputFiles
	abstract val apkDirectory: DirectoryProperty

	@get:Internal
	abstract val builtArtifactsLoader: Property<BuiltArtifactsLoader>

	/** Absolute jar/dir paths of the variant compile classpath, for the daemon. */
	@get:Input
	abstract val compileClasspathPaths: ListProperty<String>

	/** Compiled proxy classes dir (daemon bundles them into every payload dex). */
	@get:Input
	abstract val proxyClassesPath: Property<String>

	/** The transformed (test-app) manifest; resource relinks must link against it. */
	@get:Input
	abstract val transformedManifestPath: Property<String>

	/** The divert task's payload-classes dir; its jars/ carry R.jar and kin. */
	@get:Input
	abstract val payloadClassesPath: Property<String>

	/** True when the project uses Compose; the daemon then adds its compiler plugin. */
	@get:Input
	abstract val composeEnabled: Property<Boolean>

	@get:OutputFile
	abstract val setupReport: RegularFileProperty

	@TaskAction
	fun report() {
		val info =
			try {
				QuickBuildJson.parseManifestInfo(manifestInfoFile.get().asFile.readText())
			} catch (e: IllegalArgumentException) {
				throw GradleException("Quick Build: unreadable manifest info: ${e.message}", e)
			}

		val apkPath =
			builtArtifactsLoader
				.get()
				.load(apkDirectory.get())
				?.elements
				?.firstOrNull()
				?.outputFile
				?: apkDirectory
					.get()
					.asFile
					.walkTopDown()
					.firstOrNull { it.extension == "apk" }
					?.absolutePath
				?: throw GradleException(
					"Quick Build: no APK found under '${apkDirectory.get().asFile}'",
				)

		val reportFile = setupReport.get().asFile.apply { parentFile.mkdirs() }
		val payloadJars =
			File(payloadClassesPath.get(), "jars")
				.listFiles { file -> file.extension == "jar" }
				.orEmpty()
				.sortedBy { it.name }
				.map { it.absolutePath }
		reportFile.writeText(
			QuickBuildJson.setupJson(
				info,
				File(apkPath).absolutePath,
				classpath = compileClasspathPaths.get(),
				proxyClassesDir = proxyClassesPath.get(),
				manifestPath = transformedManifestPath.get(),
				payloadJars = payloadJars,
				composeEnabled = composeEnabled.getOrElse(false),
			),
		)
		logger.lifecycle("Quick Build: setup report written to {}", reportFile)
	}
}

private fun File.cleanDirectory(): File {
	deleteRecursively()
	mkdirs()
	return this
}
