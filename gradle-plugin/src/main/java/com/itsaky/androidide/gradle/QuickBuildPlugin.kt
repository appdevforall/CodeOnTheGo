package com.itsaky.androidide.gradle

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.component.analytics.AnalyticsEnabledApplicationVariant
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.ScopedArtifacts
import com.android.build.api.variant.impl.ApplicationVariantImpl
import com.itsaky.androidide.gradle.quickbuild.BaselineGenerationAsset
import com.itsaky.androidide.gradle.quickbuild.QuickBuildBaselineGenerationTask
import com.itsaky.androidide.gradle.quickbuild.QuickBuildGenerateSourcesTask
import com.itsaky.androidide.gradle.quickbuild.QuickBuildPayloadDexTask
import com.itsaky.androidide.gradle.quickbuild.QuickBuildPayloadTransformTask
import com.itsaky.androidide.gradle.quickbuild.QuickBuildProxyAppReportTask
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_LOG_SENDER_AAR
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_QUICK_BUILD_BASELINE_GENERATION
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_QUICK_BUILD_RUNTIME_AAR
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.logging.Logging
import java.io.File
import java.io.FileNotFoundException

/**
 * Turns a debuggable application build into the Quick Build proxy app build (ADFA-4128). Applied by
 * [AndroidIDEGradlePlugin] when quick build is enabled; per debuggable variant it injects the
 * runtime AAR, rewrites the merged manifest to proxy component names, diverts project classes into
 * the baseline payload dex, and writes `build/quickbuild/<variant>/setup.json` for CoGo. The proxy
 * app keeps the project's real applicationId, so switching build type is a clobber CoGo handles.
 */
class QuickBuildPlugin : Plugin<Project> {
	companion object {
		private val logger = Logging.getLogger(QuickBuildPlugin::class.java)

		/** The runtime's factory; instantiates components from the current payload generation. */
		const val APP_COMPONENT_FACTORY =
			"com.itsaky.androidide.quickbuild.runtime.QuickBuildAppComponentFactory"

		/**
		 * Floor for the payload dex, NOT the device floor: Quick Build supports API 28+
		 * (28/29 take the runtime's degraded ResourceSwapStrategy path). Dexing at 30 skips
		 * desugaring against the runtime classpath, and the dex format it emits (039) loads
		 * on 28+.
		 */
		const val MIN_PAYLOAD_API = 30

		/**
		 * Configuration names that carry annotation processors: `ksp` / `kapt` (plus their
		 * per-variant forms) and `annotationProcessor` (plain or variant-prefixed).
		 */
		internal val PROCESSOR_CONFIGURATION =
			Regex("^(ksp|kapt)([A-Z].*)?$|^annotationProcessor$|^[a-z][A-Za-z0-9]*AnnotationProcessor$")

		/**
		 * AGP's artifact-type attribute for a dependency's separately-compiled FILE-based resources
		 * (`AndroidArtifacts.ArtifactType.COMPILED_DEPENDENCIES_RESOURCES`). AGP-internal with no
		 * public constant, so the raw string is used rather than pulling AGP's internal
		 * `AndroidArtifacts` class onto this plugin's classpath.
		 */
		internal const val COMPILED_DEPENDENCIES_RESOURCES_ARTIFACT_TYPE = "android-compiled-dependencies-resources"

		/**
		 * Artifact-type attribute for a dependency's classes as a jar
		 * (`AndroidArtifacts.ArtifactType.CLASSES_JAR`) - an AAR's extracted classes.jar, or a plain
		 * jar dependency. AGP-internal like the constant above, so the raw string is used directly.
		 */
		internal const val CLASSES_JAR_ARTIFACT_TYPE = "android-classes-jar"
	}

	override fun apply(target: Project) {
		if (!target.plugins.hasPlugin(APP_PLUGIN)) {
			return
		}

		logger.info("Applying {} to project '{}'", QuickBuildPlugin::class.simpleName, target.path)
		if (target.isTestEnv) {
			logger.lifecycle("Applying {} to project '{}'", javaClass.simpleName, target.path)
		}

		val runtimeAar =
			target
				.findProperty(PROPERTY_QUICK_BUILD_RUNTIME_AAR)
				?.let { aarPath -> File(aarPath.toString()) }
				?: throw GradleException(
					"QuickBuildPlugin has been applied but no property '$PROPERTY_QUICK_BUILD_RUNTIME_AAR' is set",
				)

		if (!runtimeAar.exists()) {
			throw FileNotFoundException("Quick Build runtime AAR not found at '${runtimeAar.absolutePath}'")
		}
		if (!runtimeAar.isFile) {
			throw GradleException("Quick Build runtime AAR at '${runtimeAar.absolutePath}' is not a file")
		}

		val components = target.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)

		// Detected in finalizeDsl (user DSL is final there, before variants lock).
		// Covers both eras: buildFeatures.compose (AGP flag, Kotlin 1.x projects with
		// composeOptions) and the Kotlin 2.x Compose compiler Gradle plugin.
		var composeEnabled = false
		components.finalizeDsl { extension ->
			composeEnabled = extension.buildFeatures.compose == true ||
				target.pluginManager.hasPlugin("org.jetbrains.kotlin.plugin.compose")
		}

		// sdkComponents.bootClasspath must not be read here: the getter resolves eagerly
		// on AGP 8.11+ and throws "targetCompatibility is not yet finalized" when this
		// plugin is applied from CoGo's init script (afterEvaluate, before AGP finalizes
		// the DSL). Wrap it so the getter runs at task-graph time instead.
		val bootClasspath = target.provider { components.sdkComponents.bootClasspath }.flatMap { it }
		// Not onDebuggableVariants: that helper reads variantBuilder.debuggable in
		// beforeVariants, which AGP 8.11 rejects (PropertyAccessNotAllowedException)
		// when the plugin is applied from CoGo's init script. variant.debuggable in
		// onVariants is the sanctioned read.
		components.onVariants { variant ->
			if (variant.debuggable) {
				configureVariant(
					target,
					variant,
					runtimeAar,
					bootClasspath,
				) { composeEnabled }
			}
		}
	}

	private fun configureVariant(
		project: Project,
		variant: ApplicationVariant,
		runtimeAar: File,
		bootClasspath: org.gradle.api.provider.Provider<List<org.gradle.api.file.RegularFile>>,
		composeEnabled: () -> Boolean,
	) {
		logger.lifecycle(
			"Configuring Quick Build for variant '{}' of project '{}'",
			variant.name,
			project.path,
		)

		variant.withRuntimeConfiguration {
			dependencies.add(project.dependencies.create(project.fileTree(runtimeAar)))
		}

		val buildDirectory = project.layout.buildDirectory
		val variantDir = "quickbuild/${variant.name}"

		val generate =
			project.tasks.register(
				variant.generateTaskName("generate", "QuickBuildSources"),
				QuickBuildGenerateSourcesTask::class.java,
			) { task ->
				task.applicationId.set(variant.applicationId)
				task.appComponentFactory.set(APP_COMPONENT_FACTORY)
				task.proxySources.set(buildDirectory.dir("$variantDir/proxy-sources"))
				task.manifestInfoFile.set(buildDirectory.file("$variantDir/manifest-info.json"))
				// Dependency artifacts only - see the task's dependencyClasspath KDoc for why
				// variant.compileClasspath would be a circular task dependency here.
				task.dependencyClasspath.from(dependencyClassesJars(variant, project))
			}
		variant.artifacts
			.use(generate)
			.wiredWithFiles(
				taskInput = QuickBuildGenerateSourcesTask::mergedManifest,
				taskOutput = QuickBuildGenerateSourcesTask::updatedManifest,
			).toTransform(SingleArtifact.MERGED_MANIFEST)

		val divert =
			project.tasks.register(
				variant.generateTaskName("divert", "QuickBuildPayloadClasses"),
				QuickBuildPayloadTransformTask::class.java,
			) { task ->
				task.payloadClasses.set(buildDirectory.dir("$variantDir/payload-classes"))
			}
		variant.artifacts
			.forScope(ScopedArtifacts.Scope.PROJECT)
			.use(divert)
			.toTransform(
				ScopedArtifact.CLASSES,
				QuickBuildPayloadTransformTask::allJars,
				QuickBuildPayloadTransformTask::allDirectories,
				QuickBuildPayloadTransformTask::outputJar,
			)

		val dex =
			project.tasks.register(
				variant.generateTaskName("dex", "QuickBuildPayload"),
				QuickBuildPayloadDexTask::class.java,
			) { task ->
				task.payloadClasses.set(divert.flatMap { it.payloadClasses })
				task.proxySources.set(generate.flatMap { it.proxySources })
				task.manifestInfoFile.set(generate.flatMap { it.manifestInfoFile })
				task.compileClasspath.from(variant.compileClasspath)
				// Components are proxied uniformly, including ones whose class arrives on
				// the RUNTIME-only classpath (CoGo's injected LogSender service): javac
				// needs the superclass, so the injected AAR joins the proxy classpath.
				task.runtimeAar.addRuntimeAars(project, runtimeAar)
				task.bootClasspath.from(bootClasspath)
				task.minApiLevel.set(maxOf(variant.minSdk.apiLevel, MIN_PAYLOAD_API))
				task.proxyClasses.set(buildDirectory.dir("$variantDir/proxy-classes"))
			}
		variant.sources.assets
			?.addGeneratedSourceDirectory(dex, QuickBuildPayloadDexTask::generatedAssets)

		val stamp =
			project.tasks.register(
				variant.generateTaskName("stamp", "QuickBuildBaselineGeneration"),
				QuickBuildBaselineGenerationTask::class.java,
			) { task ->
				// Missing property stamps 0: a host older than the stamping change passes no
				// -P, and the runtime treats a 0 stamp exactly like its pre-stamp baseline.
				task.generation.set(
					project.providers
						.gradleProperty(PROPERTY_QUICK_BUILD_BASELINE_GENERATION)
						.map(BaselineGenerationAsset::parse)
						.orElse(0L),
				)
				task.generatedAssets.set(buildDirectory.dir("$variantDir/baseline-generation-assets"))
			}
		variant.sources.assets
			?.addGeneratedSourceDirectory(stamp, QuickBuildBaselineGenerationTask::generatedAssets)

		val report =
			project.tasks.register(
				variant.generateTaskName("write", "QuickBuildProxyAppReport"),
				QuickBuildProxyAppReportTask::class.java,
			) { task ->
				task.manifestInfoFile.set(generate.flatMap { it.manifestInfoFile })
				task.apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
				task.builtArtifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
				task.compileClasspathPaths.set(
					variant.compileClasspath.elements.map { elements ->
						elements.map { it.asFile.absolutePath }
					},
				)
				task.proxyClassesPath.set(dex.flatMap { it.proxyClasses }.map { it.asFile.absolutePath })
				task.transformedManifestPath.set(
					generate.flatMap { it.updatedManifest }.map { it.asFile.absolutePath },
				)
				task.payloadClassesPath.set(
					divert.flatMap { it.payloadClasses }.map { it.asFile.absolutePath },
				)
				// Provider, not a plain value: finalizeDsl (which computes the flag) runs
				// during configuration, but reading here at task-config time could race it.
				task.composeEnabled.set(project.provider { composeEnabled() })
				// Lazy for the same reason: a `dependencies { ksp(...) }` block may not have
				// been evaluated yet when this task is configured.
				task.annotationProcessors.set(
					project.provider { annotationProcessorCoordinates(project) },
				)
				// A file collection, not a mapped ListProperty<String>: see the task's
				// sourceRootDirs KDoc for the configuration-cache reason.
				variant.sources.java
					?.all
					?.let { task.sourceRootDirs.from(it) }
				variant.sources.kotlin
					?.all
					?.let { task.sourceRootDirs.from(it) }
				// A search directory, not an exact path: the task-name subfolder AGP writes under
				// is not public API, so the task probes (see its KDoc). Same for merged_res below.
				task.stableIdsSearchDir.set(
					buildDirectory.dir("intermediates/stable_resource_ids_file/${variant.name}"),
				)
				task.mergedResSearchDir.set(
					buildDirectory.dir("intermediates/merged_res/${variant.name}"),
				)
				task.dependencyResourceDirs.from(compiledDependencyResources(variant, project))
				// Variant-scoped: a report task is registered per debuggable variant, so a fixed
				// `quickbuild/setup.json` would make them all declare the same output and CoGo
				// would install whichever flavor finished last.
				task.reportFile.set(buildDirectory.file("$variantDir/setup.json"))
			}

		// Ensure a plain `assemble<Variant>` proxy app build also produces the report.
		val assembleTaskName = variant.generateTaskName("assemble")
		project.tasks.matching { it.name == assembleTaskName }.configureEach { assemble ->
			assemble.finalizedBy(report)
		}
	}

	/**
	 * Coordinates on every annotation-processor configuration in the project (`ksp`,
	 * `kspV8Debug`, `kapt`, `annotationProcessor`, `v8DebugAnnotationProcessor`, ...).
	 *
	 * Deliberately NOT filtered to the built variant: a coordinate that belongs to another
	 * variant only makes CoGo's classifier more conservative, while missing one would let
	 * an edit past that should have rebaselined.
	 */
	private fun annotationProcessorCoordinates(project: Project): List<String> =
		project.configurations
			.filter { PROCESSOR_CONFIGURATION.matches(it.name) }
			.flatMap { it.allDependencies }
			.map { dependency ->
				listOfNotNull(dependency.group, dependency.name, dependency.version)
					.joinToString(":")
			}.distinct()
			.sorted()

	/**
	 * Wires the quick-build runtime AAR, plus CoGo's injected LogSender AAR when configured - the
	 * runtime-only classpath a component's class can resolve from even though it never appears on
	 * the variant compile classpath (the LogSender service is the one shipping case).
	 */
	private fun ConfigurableFileCollection.addRuntimeAars(
		project: Project,
		runtimeAar: File,
	) {
		from(runtimeAar)
		project.findProperty(PROPERTY_LOG_SENDER_AAR)?.let { aarPath ->
			val logsenderAar = File(aarPath.toString())
			if (logsenderAar.isFile) {
				from(logsenderAar)
			}
		}
	}

	private fun ApplicationVariant.withRuntimeConfiguration(action: Configuration.() -> Unit) {
		if (this is ApplicationVariantImpl) {
			variantDependencies.runtimeClasspath.action()
		} else if (this is AnalyticsEnabledApplicationVariant) {
			delegate.withRuntimeConfiguration(action)
		}
	}

	/**
	 * Every dependency's classes as jars: a lenient `ArtifactView` over the variant's COMPILE
	 * configuration filtered to [CLASSES_JAR_ARTIFACT_TYPE].
	 *
	 * The configuration, not `variant.compileClasspath`: that FileCollection also carries the
	 * project's own compile outputs, and wiring those into the task that PRODUCES the merged
	 * manifest is a circular task dependency. Lenient because a skipped dependency at worst leaves a
	 * component proxied that should not be, which `checkProxiability` still catches.
	 */
	private fun dependencyClassesJars(
		variant: ApplicationVariant,
		project: Project,
	): FileCollection =
		variant.compileConfiguration.incoming
			.artifactView { view ->
				view.attributes {
					it.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, CLASSES_JAR_ARTIFACT_TYPE)
				}
				view.setLenient(true)
			}.files
			.let { project.files(it) }

	/**
	 * Every resource-providing dependency's separately-compiled FILE-based resources: a lenient
	 * `ArtifactView` over the variant's runtime classpath configuration, filtered to
	 * [COMPILED_DEPENDENCIES_RESOURCES_ARTIFACT_TYPE]. Each resolved "file" is actually a
	 * DIRECTORY holding one library's compiled `.flat` units. Empty when the variant exposes no
	 * runtime configuration, i.e. an AGP variant type this plugin does not recognize.
	 */
	private fun compiledDependencyResources(
		variant: ApplicationVariant,
		project: Project,
	): FileCollection {
		var configuration: Configuration? = null
		variant.withRuntimeConfiguration { configuration = this }
		val resolvedConfiguration = configuration ?: return project.files()
		return resolvedConfiguration.incoming
			.artifactView { view ->
				view.attributes {
					it.attribute(
						ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
						COMPILED_DEPENDENCIES_RESOURCES_ARTIFACT_TYPE,
					)
				}
				view.setLenient(true)
			}.files
	}
}
