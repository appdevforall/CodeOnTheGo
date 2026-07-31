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
 * Rewrites the merged manifest for the proxy app and generates the artifacts derived from
 * it: proxy component sources (activities, services, receivers, providers), the
 * proxy-to-user component map (an APK asset), and the manifest-info intermediate consumed
 * by [QuickBuildSetupReportTask].
 *
 * One task for all four outputs so the proxy numbering in the manifest and in the sources
 * can never drift apart.
 */
abstract class QuickBuildGenerateSourcesTask : DefaultTask() {
	@get:InputFile
	abstract val mergedManifest: RegularFileProperty

	/** The proxy app's application id - the project's real applicationId (no suffix). */
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
		logger.lifecycle(
			"Quick Build: generated {} proxy components for '{}'",
			proxied.size,
			appId,
		)
	}
}

/**
 * Diverts ALL project-scope classes out of the APK: the classes pipeline receives an
 * empty jar (so the installed proxy app carries no user code), while the real classes are
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

	/**
	 * The manifest-info intermediate [QuickBuildGenerateSourcesTask] also writes - read here
	 * only to map a proxy source file back to its target userClass, for
	 * [checkProxiability]'s pre-compile diagnostic.
	 */
	@get:InputFile
	abstract val manifestInfoFile: RegularFileProperty

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

	/** Extracts classes.jar from each [runtimeAar] into the task temp dir (javac and D8 cannot read AARs). */
	private fun extractRuntimeClasses(): List<File> = RuntimeClassesExtractor.extract(runtimeAar.files, temporaryDir)

	/**
	 * Fails loud, with one clear line naming the component and why, BEFORE the real proxy
	 * compile below attempts a doomed `extends` - instead of javac's multi-line "cannot
	 * inherit from final ..." diagnostic dump (ADFA-4128, generalizing Bug 7's detection:
	 * see [ComponentProxiabilityResolver]'s KDoc for why the DECISION to skip a component
	 * still has to be a named [QuickBuildManifestTransformer.UNPROXIABLE_LIBRARY_COMPONENTS]
	 * entry rather than a silent auto-skip here).
	 *
	 * Maps each proxy source file back to its target userClass via [manifestInfoFile] (the
	 * same intermediate [QuickBuildGenerateSourcesTask] wrote). [payloadRoot] (the divert
	 * task's diverted project classes) is read via [SupertypeResolver.supertypeIndex] into
	 * a project-owned class-name set - checked FIRST via
	 * [ComponentProxiabilityResolver.resolveWithProjectOverride], so it always wins over
	 * [runtimeClassesJars] + [compileClasspath]: a mixed Kotlin/Java module's compile
	 * classpath can expose a RAW (pre-[ClassOpener]) copy of the project's own class
	 * alongside the divert task's opened copy, and every ordinary Kotlin class is `final`
	 * in that raw form - without the project-owned override this flagged a real corpus
	 * app's own `MainActivity` as unproxiable (ADFA-4128 regression). A class genuinely
	 * absent from BOTH the project set and the library search path is still assumed
	 * project-owned by [ComponentProxiabilityResolver.resolve] and would fail at the javac
	 * compile immediately below if it's actually not, unchanged from before this check
	 * existed.
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
		val resolver = ComponentProxiabilityResolver.forSetupBuild(runtimeClassesJars + compileClasspath.files)
		val proxySourcesRoot = proxySources.get().asFile
		for (proxyFile in proxyJavaFiles) {
			val proxyClassName =
				proxyFile
					.relativeTo(proxySourcesRoot)
					.path
					.removeSuffix(".java")
					.replace(File.separatorChar, '.')
			val userClass = userClassByProxyClass[proxyClassName] ?: continue
			val resolution = ComponentProxiabilityResolver.resolveWithProjectOverride(userClass, projectClasses, resolver)
			if (resolution is ComponentProxiabilityResolver.Resolution.Skip) {
				throw GradleException(
					"Quick Build: '$userClass' cannot be proxied (${resolution.reason}); add it to " +
						"QuickBuildManifestTransformer.UNPROXIABLE_LIBRARY_COMPONENTS to keep it under its real manifest name",
				)
			}
		}
	}

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
 * proxy app id, entry activity, declared activities and the APK to install.
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
	 * configurations. Empty for a project with no processors - the common case, and the
	 * one where the quick path never has to think about stale generated code.
	 */
	@get:Input
	abstract val annotationProcessors: ListProperty<String>

	/**
	 * Every java/kotlin source root of the variant, GENERATED roots included, held as a
	 * file collection rather than a `ListProperty<String>` of paths.
	 *
	 * The variant source providers (`variant.sources.java/kotlin.all`) include AGP-generated
	 * roots contributed as task outputs - e.g. viewBinding/dataBinding wire the
	 * `dataBindingGenBaseClasses<Variant>` task output in. The configuration cache serializes
	 * every task field to persist the work graph, and serializing a `ListProperty` REALIZES
	 * its value at STORE time (before any task runs), which forces those output providers and
	 * throws `InvalidUserCodeException: querying the mapped value ... before task ... completed`
	 * - killing every viewBinding-enabled setup build (ADFA-4128 Bug 1). A file collection is
	 * the one type the configuration cache stores lazily (roots + producer task dependencies,
	 * resolved only when queried), so store never forces the providers. The absolute root
	 * paths are read from [files] in [report] - safely, because the report runs after the
	 * generating tasks (it consumes the APK artifact and is finalizer of `assemble`).
	 */
	@get:InputFiles
	@get:PathSensitive(PathSensitivity.ABSOLUTE)
	abstract val sourceRootDirs: ConfigurableFileCollection

	/**
	 * Directory to probe for AGP's stable-ids file (`InternalArtifactType.STABLE_RESOURCE_IDS_FILE`,
	 * written by `process<Variant>Resources` as `stableIds.txt`) - conventionally
	 * `intermediates/stable_resource_ids_file/<variantName>/<taskName>/stableIds.txt`. That type is
	 * AGP-internal (no public `SingleArtifact`), so rather than pull AGP's internal API onto this
	 * plugin's classpath, [report] walks this directory at execution time looking for a file named
	 * `stableIds.txt` - tolerant of the task-name subfolder varying across AGP versions, and of the
	 * file being entirely absent (older AGP, or a variant whose resource processing never ran it).
	 *
	 * Marked [Internal], not [InputFiles]: the directory may not exist yet at configuration time
	 * (Gradle's input-file validation would reject a declared-but-missing directory), and ordering
	 * doesn't need Gradle's task-dependency graph here - real resource processing always completes
	 * before the APK artifact this task already depends on ([apkDirectory]) is produced, so by the
	 * time [report] runs the directory (if AGP ever writes it) is already populated.
	 */
	@get:Internal
	abstract val stableIdsSearchDir: DirectoryProperty

	/**
	 * Directory to probe for AGP's merged_res closure (written by `merge<Variant>Resources`
	 * as pre-compiled `.flat` units under `intermediates/merged_res/<variantName>/
	 * merge<Variant>Resources/`) - the project's own resources PLUS, for every VALUES-type
	 * resource (styles/themes/colors/dimens/strings/attrs), the transitively-flattened
	 * closure of every dependency AAR's own values. This is AGP's classic Java-side
	 * resource merger, confirmed on-host by grepping a real `mergeDebugResources --info`
	 * log: it compiles `.../merged.dir/values/values.xml`, a file that contains the literal
	 * `Theme.Material3.DayNight.NoActionBar` declaration though the project's own
	 * `res/values/` never does (ADFA-4128 Bug 8 - a relink of the project's own res/ alone
	 * can't resolve any resource a dependency AAR provides).
	 *
	 * That artifact type is AGP-internal too (no public `SingleArtifact`), so [report]
	 * walks this directory at execution time for every `*.flat` file - tolerant of the
	 * task-name subfolder varying across AGP versions, and of the directory being entirely
	 * absent. Marked [Internal] for the same reason as [stableIdsSearchDir]: the directory
	 * may not exist at configuration time, and ordering doesn't need Gradle's
	 * task-dependency graph here (real resource processing always precedes the APK
	 * artifact this task already depends on).
	 */
	@get:Internal
	abstract val mergedResSearchDir: DirectoryProperty

	/**
	 * Every resource-providing dependency's separately-compiled FILE-based resources
	 * (layouts, drawables, anims, menus, ...) - the `ArtifactView` over the variant's
	 * runtime classpath filtered to the artifact-type attribute value
	 * `"android-compiled-dependencies-resources"` (`AndroidArtifacts.ArtifactType
	 * .COMPILED_DEPENDENCIES_RESOURCES`; confirmed by inspecting AGP's own
	 * `AndroidArtifacts$ArtifactType.class` constant pool, since that type has no public
	 * accessor). This is NOT part of [mergedResSearchDir]'s closure - confirmed on-host
	 * that closure has zero FILE-type entries for a real Material3 dependency - and a
	 * Material3 theme's own item values reference both VALUES and FILE resources, so a
	 * relink needs BOTH closures or linking still fails (ADFA-4128 Bug 8; verified by
	 * adding each independently and observing which errors remain).
	 *
	 * Held as a `ConfigurableFileCollection`, not a mapped `ListProperty<String>`, for the
	 * same config-cache-safety reason as [sourceRootDirs] (ADFA-4128 Bug 1): the artifact
	 * view's files aren't known at configuration time, and the configuration cache would
	 * force them at STORE time if this were an eagerly-mapped property.
	 */
	@get:InputFiles
	@get:PathSensitive(PathSensitivity.NONE)
	abstract val dependencyResourceDirs: ConfigurableFileCollection

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
		val payloadClassesRoot = File(payloadClassesPath.get())
		val payloadJars =
			File(payloadClassesRoot, "jars")
				.listFiles { file -> file.extension == "jar" }
				.orEmpty()
				.sortedBy { it.name }
				.map { it.absolutePath }
		// User-side supertype closures (superclasses + interfaces) of the proxied
		// components, read from the diverted class headers - the deploy policy's restart
		// closure is seeded from these.
		val supertypeIndex = SupertypeResolver.supertypeIndex(payloadClassesRoot)
		val supertypes =
			info.components.associate { component ->
				component.userClass to SupertypeResolver.chainFor(component.userClass, supertypeIndex)
			}
		val stableIdsPath = findStableIdsFile()?.absolutePath
		val libraryResourcePaths = collectLibraryResourcePaths()
		reportFile.writeText(
			QuickBuildJson.setupJson(
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
		logger.lifecycle("Quick Build: setup report written to {}", reportFile)
	}

	/**
	 * Walks [stableIdsSearchDir] for a file literally named `stableIds.txt` - see that
	 * property's KDoc for why this probes rather than hardcodes the AGP task-name subfolder.
	 * Returns the first match (there should only ever be one per variant); null when the
	 * directory doesn't exist or contains no such file.
	 */
	private fun findStableIdsFile(): File? =
		stableIdsSearchDir.orNull
			?.asFile
			?.takeIf { it.isDirectory }
			?.walkTopDown()
			?.firstOrNull { it.isFile && it.name == "stableIds.txt" }

	/**
	 * Every pre-compiled `.flat` resource unit a relink needs to resolve a dependency
	 * AAR's resources (ADFA-4128 Bug 8): [mergedResSearchDir]'s closure (project res +
	 * transitively-flattened library VALUES) followed by [dependencyResourceDirs]' FILE
	 * -based units. Sorted for determinism only - relative order between these two
	 * doesn't matter (they never declare the same resource by construction: one is
	 * VALUES-only, the other FILE-only); what matters is that the RELINK'S OWN fresh
	 * compile is layered on top of ALL of this, which is `Aapt2Link`'s job, not this
	 * task's - see its KDoc.
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

private fun File.cleanDirectory(): File {
	deleteRecursively()
	mkdirs()
	return this
}
