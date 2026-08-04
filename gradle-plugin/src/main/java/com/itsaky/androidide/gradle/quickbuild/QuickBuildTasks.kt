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
import org.gradle.api.tasks.Optional
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
 * Rewrites the merged manifest for the proxy app and generates everything derived from it: the
 * proxy component sources, the proxy-to-user component map asset, and the manifest-info
 * intermediate [QuickBuildProxyAppReportTask] reads.
 *
 * One task for all four outputs so the proxy numbering in the manifest and in the sources cannot
 * drift apart.
 */
abstract class QuickBuildGenerateSourcesTask : DefaultTask() {
	/** AGP's merged manifest for the variant, the sole input every output here derives from. */
	@get:InputFile
	abstract val mergedManifest: RegularFileProperty

	/** The proxy app's application id - the project's real applicationId (no suffix). */
	@get:Input
	abstract val applicationId: Property<String>

	/** FQN of the quick-build runtime's AppComponentFactory. */
	@get:Input
	abstract val appComponentFactory: Property<String>

	/**
	 * The variant's dependency class artifacts, searched by [ComponentProxiabilityResolver] to
	 * skip a library component that cannot be proxied.
	 *
	 * Dependency artifacts, not `variant.compileClasspath`: this task produces the merged
	 * manifest, which AGP processes before compilation, and `compileClasspath` carries the
	 * project's own compile outputs - wiring that here is a circular task dependency. The cost is
	 * that a class absent from this narrower view could be either project-owned or runtime-only,
	 * which is why the resolver treats absence as proxiable.
	 */
	@get:Classpath
	abstract val dependencyClasspath: ConfigurableFileCollection

	/** The rewritten proxy-app manifest, which AGP packages in place of the merged one. */
	@get:OutputFile
	abstract val updatedManifest: RegularFileProperty

	/** Generated proxy .java sources, compiled by [QuickBuildPayloadDexTask] (not the variant). */
	@get:OutputDirectory
	abstract val proxySources: DirectoryProperty

	/** Generated assets layer carrying quickbuild/components.json. */
	@get:OutputDirectory
	abstract val generatedAssets: DirectoryProperty

	/** Manifest facts for the later tasks; not shipped in the APK. */
	@get:OutputFile
	abstract val manifestInfoFile: RegularFileProperty

	/** Transforms the manifest, then writes the proxy sources, components asset and manifest info. */
	@TaskAction
	fun generate() {
		val appId = applicationId.get()
		val transformer =
			QuickBuildManifestTransformer(
				proxyPackage = "$appId.proxies",
				appComponentFactory = appComponentFactory.get(),
				proxiability = ComponentProxiabilityResolver.searchingClasspath(dependencyClasspath.files.toList()),
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
		val proxied = result.components.filter { it.proxyClass != null }
		proxied.forEach { component ->
			val relativePath = component.proxyClass!!.replace('.', '/') + ".java"
			File(sourcesRoot, relativePath)
				.apply { parentFile.mkdirs() }
				.writeText(ProxySourceGenerator.generateSource(component))
		}

		val assetsRoot = generatedAssets.get().asFile.cleanDirectory()
		File(assetsRoot, "quickbuild/components.json")
			.apply { parentFile.mkdirs() }
			.writeText(QuickBuildJson.componentsJson(result.components))

		val info =
			ManifestInfo(
				proxyAppId = appId,
				entryActivity = result.entryActivity,
				activities = result.activities.map { it.userClass },
				components = result.components,
			)
		manifestInfoFile
			.get()
			.asFile
			.apply { parentFile.mkdirs() }
			.writeText(QuickBuildJson.manifestInfoJson(info))

		if (result.entryActivity == null) {
			logger.warn("Quick Build: no LAUNCHER activity found in the merged manifest")
		}
		result.unproxied.forEach { skipped ->
			// Lifecycle, not info: someone debugging a stale-code report needs to see a
			// component losing its proxy without re-running the build.
			logger.lifecycle(
				"Quick Build: '{}' keeps its real manifest name, unproxied ({})",
				skipped.userClass,
				skipped.reason,
			)
		}
		logger.lifecycle(
			"Quick Build: generated {} proxy components for '{}'",
			proxied.size,
			appId,
		)
	}
}

/**
 * Diverts every project-scope class out of the APK, so the installed proxy app carries no user
 * code: the classes pipeline gets an all-but-empty jar, and the real classes are copied to
 * [payloadClasses] for [QuickBuildPayloadDexTask] and the on-device compile daemon's baseline.
 */
abstract class QuickBuildPayloadTransformTask : DefaultTask() {
	/** Jar inputs of the APK's classes pipeline, as AGP's artifact transform hands them over. */
	@get:InputFiles
	abstract val allJars: ListProperty<RegularFile>

	/** Directory inputs of the same pipeline: the project's own compiled classes. */
	@get:InputFiles
	abstract val allDirectories: ListProperty<Directory>

	/**
	 * The jar handed back to the APK's classes pipeline, carrying only the resource R classes.
	 *
	 * R stays in the base APK because base-APK library code references it (the injected LogSender
	 * service reads its own `R$string`) and that code loads on the APK classloader, which cannot
	 * see the payload dex. R is also diverted into the payload, for the daemon's compile
	 * classpath; the duplication is harmless because the payload loader's parent is the APK
	 * loader, so user code resolves R from the APK.
	 */
	@get:OutputFile
	abstract val outputJar: RegularFileProperty

	/** Diverted classes: jars/N.jar for jar inputs, dirs/N/... for directory inputs. */
	@get:OutputDirectory
	abstract val payloadClasses: DirectoryProperty

	/** Copies the inputs into [payloadClasses], then writes the R-only jar for the APK. */
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
	 * True for `R.class` and the nested `R$string`, `R$layout`, ... holders.
	 *
	 * @param entryName a jar entry name or file name; only the segment after the last `/` is
	 *   examined, so the package is irrelevant.
	 * @return true if the entry is an R class of any package.
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
 * Builds the baseline payload dex (assets/quickbuild/gen-0.dex) from the diverted project classes
 * plus the generated proxies:
 *
 * 1. strips ACC_FINAL from the diverted classes, so the proxies can extend them;
 * 2. compiles the proxy sources with an in-process javac - the variant's own javac would reject
 *    the still-final superclasses, which is why the proxies are not variant sources;
 * 3. runs D8 over opened classes, proxies and diverted jars into a single classes.dex.
 *
 * The compiled proxies also land in [proxyClasses], for the on-device daemon to reuse in every
 * later payload.
 */
abstract class QuickBuildPayloadDexTask : DefaultTask() {
	/** The divert task's output: the project classes this task opens and dexes. */
	@get:InputDirectory
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val payloadClasses: DirectoryProperty

	/** The generate task's proxy `.java` sources, compiled here rather than by the variant. */
	@get:InputDirectory
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val proxySources: DirectoryProperty

	/**
	 * [QuickBuildGenerateSourcesTask]'s manifest-info intermediate, read here only to map a proxy
	 * source file back to its target userClass for [checkProxiability].
	 */
	@get:InputFile
	abstract val manifestInfoFile: RegularFileProperty

	/** The variant compile classpath, for javac and as D8's classpath (never program input). */
	@get:Classpath
	abstract val compileClasspath: ConfigurableFileCollection

	/** The android.jar boot classpath, for javac and as D8's library input. */
	@get:Classpath
	abstract val bootClasspath: ConfigurableFileCollection

	/**
	 * The quick-build runtime AAR. Its classes.jar goes on the proxy compile classpath because
	 * generated proxies call runtime API, and the AAR is injected into the runtime configuration
	 * only, so the variant compile classpath never carries it.
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

	/** Opens the diverted classes, compiles the proxies against them, and dexes the lot. */
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
			checkProxiability(proxyJavaFiles, payloadRoot, runtimeClassesJars)
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
			dexFiles.isEmpty() -> {
				throw GradleException("Quick Build: d8 produced no dex for the baseline payload")
			}

			dexFiles.size > 1 -> {
				throw GradleException(
					"Quick Build: the baseline payload needs ${dexFiles.size} dex files, but v1 " +
						"supports a single gen-0.dex; the project's own classes exceed the method budget",
				)
			}
		}
		dexFiles.single().copyTo(File(assetsRoot, "quickbuild/gen-0.dex").apply { parentFile.mkdirs() })
	}

	/**
	 * Extracts classes.jar from each [runtimeAar]; javac and D8 cannot read an AAR.
	 *
	 * @return the extracted jars, written under the task's temporary directory so they are
	 *   rewritten on every run rather than declared as an output.
	 */
	private fun extractRuntimeClasses(): List<File> = RuntimeClassesExtractor.extract(runtimeAar.files, temporaryDir)

	/**
	 * Fails the build with one clear line if any proxy targets a class that cannot be extended,
	 * rather than letting javac dump a "cannot inherit from final ..." diagnostic.
	 *
	 * A backstop for [QuickBuildGenerateSourcesTask], which could only search dependency
	 * artifacts; this sees the real proxy compile classpath. Classes the project itself compiled
	 * ([payloadRoot]) are exempted first: a mixed Kotlin/Java classpath can expose a raw copy.
	 *
	 * @param proxyJavaFiles the generated proxy sources, whose paths under [proxySources] give
	 *   back the proxy class names the manifest info is keyed by.
	 * @param payloadRoot the divert task's output, read for the set of project-compiled classes.
	 * @param runtimeClassesJars the runtime AAR's extracted classes.jars, searched ahead of
	 *   [compileClasspath].
	 * @throws org.gradle.api.GradleException naming the first unproxiable component, with the
	 *   resolver's reason and the action the user has.
	 */
	private fun checkProxiability(
		proxyJavaFiles: List<File>,
		payloadRoot: File,
		runtimeClassesJars: List<File>,
	) {
		val manifestInfo = QuickBuildJson.parseManifestInfo(manifestInfoFile.get().asFile.readText())
		val userClassByProxyClass =
			manifestInfo.components.mapNotNull { component -> component.proxyClass?.let { it to component.userClass } }.toMap()
		val projectClasses = SupertypeResolver.supertypeIndex(payloadRoot).keys
		val resolver = ComponentProxiabilityResolver.searchingClasspath(runtimeClassesJars + compileClasspath.files)
		val proxySourcesRoot = proxySources.get().asFile
		for (proxyFile in proxyJavaFiles) {
			val proxyClassName =
				proxyFile
					.relativeTo(proxySourcesRoot)
					.path
					.removeSuffix(".java")
					.replace(File.separatorChar, '.')
			val userClass = userClassByProxyClass[proxyClassName] ?: continue
			val resolution = resolver.resolveWithProjectOverride(userClass, projectClasses)
			if (resolution is ComponentProxiabilityResolver.Resolution.Skip) {
				// Addressed to a CoGo user building their own app, so it names the action they
				// have (Run/Debug), not a CoGo source file they cannot edit. The remedy on our
				// side is in quickbuild/core/README.md.
				throw GradleException(
					"Quick Build can't run on this project: the library component '$userClass' " +
						"can't be proxied (${resolution.reason}). Use Run/Debug to build and run it instead.",
				)
			}
		}
	}

	/**
	 * Compiles the generated proxy sources with an in-process javac; annotation processing off.
	 *
	 * @param sources the proxy `.java` files; an empty list is never passed.
	 * @param classpath everything the proxies compile against - boot classes, the opened project
	 *   classes, the runtime AAR and the variant compile classpath.
	 * @param outputDir destination for the `.class` output; javac creates the package tree.
	 * @throws org.gradle.api.GradleException if the JVM ships no compiler, or javac fails; the
	 *   collected diagnostics are appended to the message.
	 */
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
 * Writes build/quickbuild/setup.json, the handshake CoGo reads after the proxy app build: the
 * proxy app id, entry activity, declared activities, the APK to install, and everything the
 * on-device daemon needs to compile and relink.
 */
abstract class QuickBuildProxyAppReportTask : DefaultTask() {
	/** The generate task's manifest-info intermediate, copied into setup.json. */
	@get:InputFile
	abstract val manifestInfoFile: RegularFileProperty

	/** AGP's APK output directory, holding the built proxy APK and its metadata. */
	@get:InputFiles
	abstract val apkDirectory: DirectoryProperty

	/** AGP's loader for that metadata; a directory walk is the fallback when it finds nothing. */
	@get:Internal
	abstract val builtArtifactsLoader: Property<BuiltArtifactsLoader>

	/** Absolute jar/dir paths of the variant compile classpath, for the daemon. */
	@get:Input
	abstract val compileClasspathPaths: ListProperty<String>

	/** Compiled proxy classes dir (daemon bundles them into every payload dex). */
	@get:Input
	abstract val proxyClassesPath: Property<String>

	/** The transformed (proxy-app) manifest; resource relinks must link against it. */
	@get:Input
	abstract val transformedManifestPath: Property<String>

	/** The divert task's payload-classes dir; its jars/ carry R.jar and kin. */
	@get:Input
	abstract val payloadClassesPath: Property<String>

	/** True when the project uses Compose; the daemon then adds its compiler plugin. */
	@get:Input
	abstract val composeEnabled: Property<Boolean>

	/**
	 * Coordinates declared on the variant's `ksp` / `kapt` / `annotationProcessor`
	 * configurations. Empty for a project with no processors, where the quick path never has to
	 * think about stale generated code.
	 */
	@get:Input
	abstract val annotationProcessors: ListProperty<String>

	/**
	 * Every java/kotlin source root of the variant, generated roots included.
	 *
	 * Must stay a file collection, not a `ListProperty<String>` of paths: some of these roots are
	 * task outputs (viewBinding wires in `dataBindingGenBaseClasses<Variant>`), and the
	 * configuration cache realizes a `ListProperty` at store time, before any task has run, which
	 * throws `InvalidUserCodeException: querying the mapped value ... before task ... completed`.
	 * A file collection is stored lazily, so [report] can read absolute paths from it at
	 * execution time.
	 */
	@get:InputFiles
	@get:PathSensitive(PathSensitivity.ABSOLUTE)
	abstract val sourceRootDirs: ConfigurableFileCollection

	/**
	 * Directory to probe for AGP's `stableIds.txt`, conventionally
	 * `intermediates/stable_resource_ids_file/<variantName>/<taskName>/`.
	 *
	 * That artifact type is AGP-internal, with no public `SingleArtifact`, so [report] walks this
	 * directory at execution time instead of pulling AGP's internal API onto the plugin classpath.
	 * The walk tolerates the task-name subfolder varying across AGP versions and the file being
	 * absent entirely.
	 *
	 * [Internal] rather than [InputFiles] because the directory may not exist at configuration
	 * time, which Gradle's input validation would reject. No task-dependency edge is needed:
	 * resource processing always finishes before the APK artifact this task already depends on.
	 */
	@get:Internal
	abstract val stableIdsSearchDir: DirectoryProperty

	/**
	 * Directory to probe for AGP's merged_res closure: pre-compiled `.flat` units under
	 * `intermediates/merged_res/<variantName>/merge<Variant>Resources/`.
	 *
	 * That closure holds the project's own resources plus, for every VALUES-type resource
	 * (styles, themes, colors, strings, attrs), the transitively-flattened values of every
	 * dependency AAR. A relink of the project's own res/ alone cannot resolve a resource only a
	 * dependency declares. Confirmed on-host: a real `mergeDebugResources` log shows it compiling
	 * a `values.xml` that declares `Theme.Material3.DayNight.NoActionBar`.
	 *
	 * Probed and marked [Internal] for the same reasons as [stableIdsSearchDir].
	 */
	@get:Internal
	abstract val mergedResSearchDir: DirectoryProperty

	/**
	 * Every dependency's separately-compiled FILE-based resources (layouts, drawables, menus,
	 * ...): an `ArtifactView` over the runtime classpath filtered to artifact type
	 * `"android-compiled-dependencies-resources"`, a string literal because AGP exposes no public
	 * accessor for it.
	 *
	 * Disjoint from [mergedResSearchDir], which carries VALUES resources only. A theme's item
	 * values reference both kinds, so a relink needs both closures or it still fails on
	 * unresolved references.
	 *
	 * A file collection rather than a mapped `ListProperty<String>`, for the same
	 * configuration-cache reason as [sourceRootDirs].
	 */
	@get:InputFiles
	@get:PathSensitive(PathSensitivity.NONE)
	abstract val dependencyResourceDirs: ConfigurableFileCollection

	/** build/quickbuild/setup.json, the one file CoGo reads after the proxy app build. */
	@get:OutputFile
	abstract val reportFile: RegularFileProperty

	/** Resolves the built APK and every daemon input, then writes setup.json. */
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

		val outFile = reportFile.get().asFile.apply { parentFile.mkdirs() }
		val payloadClassesRoot = File(payloadClassesPath.get())
		val payloadJars =
			File(payloadClassesRoot, "jars")
				.listFiles { file -> file.extension == "jar" }
				.orEmpty()
				.sortedBy { it.name }
				.map { it.absolutePath }
		// Supertype closures of the proxied components, read from the diverted class headers.
		// The deploy policy's restart closure is seeded from these.
		val supertypeIndex = SupertypeResolver.supertypeIndex(payloadClassesRoot)
		val supertypes =
			info.components.associate { component ->
				component.userClass to SupertypeResolver.chainFor(component.userClass, supertypeIndex)
			}
		val stableIdsPath = findStableIdsFile()?.absolutePath
		val libraryResourcePaths = collectLibraryResourcePaths()
		outFile.writeText(
			QuickBuildJson.proxyAppReportJson(
				info,
				File(apkPath).absolutePath,
				classpath = compileClasspathPaths.get(),
				proxyClassesDir = proxyClassesPath.get(),
				manifestPath = transformedManifestPath.get(),
				payloadJars = payloadJars,
				composeEnabled = composeEnabled.getOrElse(false),
				supertypes = supertypes,
				annotationProcessors = annotationProcessors.getOrElse(emptyList()),
				sourceRoots =
					sourceRootDirs.files
						.map { it.absolutePath }
						.distinct()
						.sorted(),
				stableIdsPath = stableIdsPath,
				libraryResourcePaths = libraryResourcePaths,
			),
		)
		if (stableIdsPath == null) {
			logger.info(
				"Quick Build: no AGP stable-ids file found under {}; relinks won't pin resource type/entry ids",
				stableIdsSearchDir.orNull?.asFile,
			)
		}
		if (libraryResourcePaths.isEmpty()) {
			logger.info(
				"Quick Build: no merged_res or dependency-resource units found under {}; " +
					"relinks won't resolve resources a dependency AAR provides",
				mergedResSearchDir.orNull?.asFile,
			)
		}
		logger.lifecycle("Quick Build: proxy app report written to {}", outFile)
	}

	/**
	 * Finds AGP's `stableIds.txt` under [stableIdsSearchDir], or null if AGP wrote none.
	 *
	 * See that property's KDoc for why this walks rather than hardcoding the task-name subfolder.
	 *
	 * @return the first `stableIds.txt` found, or null when the directory is unset, absent, or
	 *   holds no such file - all of which are normal on some AGP versions.
	 */
	private fun findStableIdsFile(): File? =
		stableIdsSearchDir.orNull
			?.asFile
			?.takeIf { it.isDirectory }
			?.walkTopDown()
			?.firstOrNull { it.isFile && it.name == "stableIds.txt" }

	/**
	 * Collects every pre-compiled `.flat` unit a relink needs to resolve a dependency's
	 * resources: [mergedResSearchDir]'s closure plus [dependencyResourceDirs]' FILE-based units.
	 *
	 * Sorted for determinism only. The two sets never declare the same resource, and layering the
	 * relink's own fresh compile on top of both is `Aapt2Link`'s job, not this task's.
	 *
	 * @return absolute paths of every `.flat` unit found, sorted; empty when neither source
	 *   exists, which the caller logs rather than treating as an error.
	 */
	private fun collectLibraryResourcePaths(): List<String> {
		val mergedRes =
			mergedResSearchDir.orNull
				?.asFile
				?.takeIf { it.isDirectory }
				?.walkTopDown()
				?.filter { it.isFile && it.extension == "flat" }
				?.map { it.absolutePath }
				?.toList()
				.orEmpty()
		val dependencyFlats =
			dependencyResourceDirs.files
				.filter { it.isDirectory }
				.flatMap { dir -> dir.walkTopDown().filter { file -> file.isFile && file.extension == "flat" }.toList() }
				.map { it.absolutePath }
		return (mergedRes + dependencyFlats).sorted()
	}
}

/**
 * Deletes and recreates this directory, so a task never mixes stale output with fresh.
 *
 * @return this directory, now empty and existing, for chaining.
 */
private fun File.cleanDirectory(): File {
	deleteRecursively()
	mkdirs()
	return this
}
