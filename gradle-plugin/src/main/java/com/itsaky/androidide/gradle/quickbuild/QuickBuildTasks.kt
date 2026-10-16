package com.itsaky.androidide.gradle.quickbuild

import com.android.build.api.variant.BuiltArtifact
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
 * proxy component sources and the manifest-info intermediate [QuickBuildProxyAppReportTask] reads.
 *
 * One task for all three outputs so the proxy numbering in the manifest and in the sources cannot
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
	 * The variant's dependency class artifacts, searched by [ComponentProxiabilityResolver] to skip
	 * a library component that cannot be proxied.
	 *
	 * Dependency artifacts, not `variant.compileClasspath`: AGP processes this task's merged-manifest
	 * output before compilation, so wiring the compile classpath here is a circular task dependency.
	 * A class absent from this narrower view may be project-owned, hence absence means proxiable.
	 */
	@get:Classpath
	abstract val dependencyClasspath: ConfigurableFileCollection

	/** The rewritten proxy-app manifest, which AGP packages in place of the merged one. */
	@get:OutputFile
	abstract val updatedManifest: RegularFileProperty

	/** Generated proxy .java sources, compiled by [QuickBuildPayloadDexTask] (not the variant). */
	@get:OutputDirectory
	abstract val proxySources: DirectoryProperty

	/** Manifest facts for the later tasks; not shipped in the APK. */
	@get:OutputFile
	abstract val manifestInfoFile: RegularFileProperty

	/** Transforms the manifest, then writes the proxy sources and the manifest info. */
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
			// Not necessarily missing: a MAIN/LAUNCHER filter on an <activity-alias> whose
			// target activity carries none is a supported pattern, and leaves no proxied
			// entry activity to name. The relaunch then uses the package's default intent.
			logger.warn(
				"Quick Build: no LAUNCHER activity to relaunch into; the app either declares " +
					"none, or declares it on an <activity-alias>",
			)
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
	 * service reads its own `R$string`) and that code loads on the APK classloader, which cannot see
	 * the payload dex. R is also diverted into the payload for the daemon's compile classpath; the
	 * duplication is harmless because the payload loader's parent is the APK loader.
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
		// AGP hands over declared artifact locations, not guaranteed files. The retained-jar
		// walk below already tolerates a missing directory, so the copies here skip missing
		// inputs the same way instead of throwing on them.
		val jars = allJars.get().filter { it.asFile.exists() }
		val directories = allDirectories.get().filter { it.asFile.exists() }
		jars.forEachIndexed { index, jar ->
			jar.asFile.copyTo(File(root, "jars/$index.jar"))
		}
		directories.forEachIndexed { index, dir ->
			dir.asFile.copyRecursively(File(root, "dirs/$index"))
		}

		writeRetainedApkJar(jars, directories)
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

	/** Collects every R class from the (existing) inputs into [outputJar] so the APK keeps them. */
	private fun writeRetainedApkJar(
		jars: List<RegularFile>,
		directories: List<Directory>,
	) {
		val seen = HashSet<String>()
		JarOutputStream(outputJar.get().asFile.outputStream()).use { out ->
			// A zip must contain at least one entry even when no R classes exist.
			out.putNextEntry(JarEntry("META-INF/com.itsaky.androidide.quickbuild.diverted"))
			out.closeEntry()

			directories.forEach { dir ->
				dir.asFile.walkTopDown().filter { it.isFile && isResourceClass(it.name) }.forEach { file ->
					val entry = file.relativeTo(dir.asFile).invariantSeparatorsPath
					if (seen.add(entry)) {
						out.putNextEntry(JarEntry(entry))
						file.inputStream().use { it.copyTo(out) }
						out.closeEntry()
					}
				}
			}
			jars.forEach { jar ->
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
 * plus the generated proxies: strips ACC_FINAL so the proxies can extend their targets, compiles
 * the proxy sources with an in-process javac - the variant's own javac would reject the still-final
 * superclasses, which is why the proxies are not variant sources - then runs D8 over the lot. The
 * compiled proxies also land in [proxyClasses], for the daemon to reuse in every later payload.
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

	/**
	 * Effective dex min API - the payload floor, NOT the device floor: at least 30 so d8 skips
	 * desugaring, while the emitted dex format still loads on the API 28+ devices Quick Build
	 * supports. See QuickBuildPlugin.MIN_PAYLOAD_API.
	 */
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
		// Jars get the same treatment as the diverted directories: a user class that lands in a
		// jar is still a class a proxy has to extend.
		val openedJars = payloadJars.map { jar -> ClassOpener.openJar(jar, File(openedDir, "jars/${jar.name}")) }

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
					bootClasspath.files + openedRoots + openedJars +
						runtimeClassesJars + compileClasspath.files,
				outputDir = proxyClassesDir,
			)
		}

		val programFiles =
			openedRoots.flatMap { root -> root.walkTopDown().filter { it.extension == "class" } } +
				proxyClassesDir.walkTopDown().filter { it.extension == "class" } +
				openedJars
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
			// Before the proxiability question, because this one is about the source we are
			// about to emit rather than about the class it extends. A keyword segment makes
			// the generated .java unparsable, and javac then reports "<identifier> expected"
			// while naming neither the component nor the user's way out.
			val reserved =
				ProxySourceGenerator.reservedJavaSegment(userClass)
					?: ProxySourceGenerator.reservedJavaSegment(proxyClassName)
			if (reserved != null) {
				throw GradleException(
					"Quick Build can't run on this project: the component '$userClass' " +
						"can't be proxied (its name contains '$reserved', which Java reserves, " +
						"so the generated proxy source would not compile). " +
						"Use Run/Debug to build and run it instead.",
				)
			}
			val resolution = resolver.resolveWithProjectOverride(userClass, projectClasses)
			if (resolution is ComponentProxiabilityResolver.Resolution.Skip) {
				// Addressed to a CoGo user building their own app, so it names the action they
				// have (Run/Debug), not a CoGo source file they cannot edit. The remedy on our
				// side is in quickbuild/README.md.
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
					// The same release the daemon pins its own javac to
					// (IncrementalCompiler.JVM_TARGET, a separate artifact). These proxies are
					// bundled into every payload dex beside daemon-compiled classes, and a
					// Gradle JVM above 17 would put two bytecode levels in one payload.
					"--release",
					"17",
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
 * Writes the baseline-generation stamp asset ([BaselineGenerationAsset.ASSET_RELATIVE_PATH]), the
 * sibling of the baseline payload dex, which the runtime reads pre-Context through the APK
 * classloader and boots the baseline at.
 *
 * A separate task from [QuickBuildPayloadDexTask] on purpose: the host allocates a fresh
 * generation for every provision and rebaseline, and making the stamp an input of the dex task
 * would re-run D8 over the whole payload each time for a one-line asset.
 */
abstract class QuickBuildBaselineGenerationTask : DefaultTask() {
	/** The generation the host allocated for this baseline; 0 when the host sent none. */
	@get:Input
	abstract val generation: Property<Long>

	/** Generated assets layer carrying the stamp file. */
	@get:OutputDirectory
	abstract val generatedAssets: DirectoryProperty

	/** Writes the stamp as decimal text. */
	@TaskAction
	fun write() {
		BaselineGenerationAsset.write(generatedAssets.get().asFile.cleanDirectory(), generation.get())
	}
}

/**
 * Writes `build/quickbuild/<variant>/setup.json`, the handshake CoGo reads after the proxy app
 * build: the proxy app id, entry activity, declared activities, the APK to install, and
 * everything the on-device daemon needs to compile and relink.
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

	/**
	 * The divert task's payload-classes dir; its jars/ carry R.jar and kin.
	 *
	 * A directory input, not the path as a string: the report reads the tree itself (the jar
	 * list, and the class headers the supertype index is built from), so tracking only the
	 * path leaves the report up to date after the classes underneath it change.
	 */
	@get:InputDirectory
	abstract val payloadClasses: DirectoryProperty

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
	 * task outputs (viewBinding wires in `dataBindingGenBaseClasses<Variant>`), and the configuration
	 * cache realizes a `ListProperty` at store time, before any task has run, which throws
	 * `InvalidUserCodeException`. A file collection is stored lazily, so [report] reads its absolute
	 * paths at execution time.
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
	 * That closure holds the project's own resources plus, for every VALUES-type resource (styles,
	 * themes, colors, strings, attrs), the transitively-flattened values of every dependency AAR - a
	 * relink of the project's own res/ alone cannot resolve a resource only a dependency declares.
	 *
	 * Probed and marked [Internal] for the same reasons as [stableIdsSearchDir].
	 */
	@get:Internal
	abstract val mergedResSearchDir: DirectoryProperty

	/**
	 * Every dependency's separately-compiled FILE-based resources (layouts, drawables, menus, ...):
	 * an `ArtifactView` over the runtime classpath filtered to artifact type
	 * `"android-compiled-dependencies-resources"`.
	 *
	 * Disjoint from [mergedResSearchDir], which carries VALUES resources only, and a theme's item
	 * values reference both kinds. A file collection for the same reason as [sourceRootDirs].
	 *
	 * ABSOLUTE, like [sourceRootDirs], because these directories' absolute paths are part of this
	 * task's OUTPUT - `collectLibraryResourcePaths` writes them into setup.json. Under NONE a
	 * dependency's compiled-resources artifact could relocate without changing bytes (a Gradle or
	 * AGP upgrade rehashing `caches/transforms-*`, or a re-download after cache pruning) and leave
	 * the task UP-TO-DATE with dead paths, so every on-device relink would silently lose
	 * dependency-provided resources.
	 */
	@get:InputFiles
	@get:PathSensitive(PathSensitivity.ABSOLUTE)
	abstract val dependencyResourceDirs: ConfigurableFileCollection

	/**
	 * The API level [QuickBuildPayloadDexTask] dexed the seed payload at, published so the
	 * on-device daemon dexes its increments at the same level instead of falling back to the
	 * protocol's own floor. Set from the same expression as that task's `minApiLevel`.
	 */
	@get:Input
	abstract val minApiLevel: Property<Int>

	/**
	 * `build/quickbuild/<variant>/setup.json` - the file CoGo reads after the proxy app build,
	 * scoped to this variant so a flavored project's report tasks never share one output.
	 */
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
				?.takeIf { it.isNotEmpty() }
				?.let { selectUniversalApk(it, apkDirectory.get().asFile) }
				?: soleApkUnder(apkDirectory.get().asFile)

		val outFile = reportFile.get().asFile.apply { parentFile.mkdirs() }
		val payloadClassesRoot = payloadClasses.get().asFile
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
				minApi = minApiLevel.get(),
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
	 * The one APK under [apkDirectory], for a build whose artifact metadata could not be read.
	 *
	 * Without the metadata nothing here can tell a split from the universal APK, so the only
	 * safe answer is that there must be exactly one candidate. Picking one of several was the
	 * same arbitrary choice [selectUniversalApk] exists to prevent, one level down.
	 *
	 * @param apkDirectory the variant's APK output directory.
	 * @return the single APK's absolute path.
	 * @throws GradleException when the directory holds no APK, or more than one.
	 */
	private fun soleApkUnder(apkDirectory: File): String {
		val apks = apkDirectory.walkTopDown().filter { it.extension == "apk" }.toList()
		return when (apks.size) {
			1 -> {
				apks.single().absolutePath
			}

			0 -> {
				throw GradleException("Quick Build: no APK found under '$apkDirectory'")
			}

			else -> {
				throw GradleException(
					"Quick Build: '$apkDirectory' holds ${apks.size} APKs (" +
						apks.map { it.name }.sorted().joinToString(", ") +
						") and their build metadata could not be read, so the universal one " +
						"cannot be identified; disable APK splits for this variant or use a " +
						"Standard Run",
				)
			}
		}
	}

	companion object {
		/**
		 * Picks the one APK every device can install out of AGP's built-artifact metadata.
		 *
		 * With APK splits enabled the metadata lists each split beside the universal APK in no
		 * contractual order, so "the first element" is an arbitrary split that installs - or
		 * fails to - depending on the device it meets. Only the unfiltered (universal) element
		 * is safe to hand to CoGo.
		 *
		 * @param elements the loaded metadata's artifacts; must be non-empty.
		 * @param apkDirectory the APK output directory, for the error message only.
		 * @return the universal APK's path, as recorded in the metadata.
		 * @throws GradleException when every element is a split: Quick Build does not support
		 *   splits, and picking one here would only fail later, on the device.
		 */
		internal fun selectUniversalApk(
			elements: Collection<BuiltArtifact>,
			apkDirectory: File,
		): String =
			elements.firstOrNull { it.filters.isEmpty() }?.outputFile
				?: throw GradleException(
					"Quick Build: every APK under '$apkDirectory' is a split (" +
						elements
							.flatMap { it.filters }
							.map { "${it.filterType}=${it.identifier}" }
							.distinct()
							.sorted()
							.joinToString(", ") +
						"); Quick Build does not support APK splits yet - disable splits for " +
						"this variant or use a Standard Run",
				)
	}

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
