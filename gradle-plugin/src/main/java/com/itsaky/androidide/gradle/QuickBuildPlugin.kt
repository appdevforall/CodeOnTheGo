package com.itsaky.androidide.gradle

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.component.analytics.AnalyticsEnabledApplicationVariant
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.ScopedArtifacts
import com.android.build.api.variant.impl.ApplicationVariantImpl
import com.itsaky.androidide.gradle.quickbuild.QuickBuildGenerateSourcesTask
import com.itsaky.androidide.gradle.quickbuild.QuickBuildPayloadDexTask
import com.itsaky.androidide.gradle.quickbuild.QuickBuildPayloadTransformTask
import com.itsaky.androidide.gradle.quickbuild.QuickBuildProxyAppReportTask
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_LOG_SENDER_AAR
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
 * Turns a debuggable application build into the Quick Build proxy app build (ADFA-4128): a
 * one-per-baseline real Gradle build that produces the installable proxy app.
 *
 * Applied by [AndroidIDEGradlePlugin] when
 * [com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_QUICK_BUILD_ENABLED] is
 * true. For every debuggable application variant it:
 *
 * - injects the quick-build runtime AAR into the runtime classpath (LogSender pattern);
 * - builds the proxy app under the project's real applicationId (Quick Build and Standard
 *   Run share the one package slot; switching build type is a confirmed clobber, handled
 *   CoGo-side);
 * - rewrites the merged manifest: proxy component names + the runtime's
 *   appComponentFactory (everything else - permissions, icon, label, filters - kept);
 * - diverts all project-scope classes out of the APK and into the baseline payload dex,
 *   baked in as assets/quickbuild/gen-0.dex next to assets/quickbuild/components.json;
 * - writes build/quickbuild/setup.json for CoGo to read after the build.
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
		 * The Gradle artifact-type attribute value AGP tags each resource-providing
		 * dependency's separately-compiled FILE-based resources with
		 * (`AndroidArtifacts.ArtifactType.COMPILED_DEPENDENCIES_RESOURCES`). AGP-internal
		 * (no public constant), so the raw string is used directly rather than pulling
		 * AGP's internal `AndroidArtifacts` class onto this plugin's classpath - confirmed
		 * by inspecting AGP 8.8.2's `AndroidArtifacts$ArtifactType.class` constant pool
		 * (ADFA-4128 Bug 8).
		 */
		internal const val COMPILED_DEPENDENCIES_RESOURCES_ARTIFACT_TYPE = "android-compiled-dependencies-resources"
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
			}
		variant.artifacts
			.use(generate)
			.wiredWithFiles(
				taskInput = QuickBuildGenerateSourcesTask::mergedManifest,
				taskOutput = QuickBuildGenerateSourcesTask::updatedManifest,
			).toTransform(SingleArtifact.MERGED_MANIFEST)
		variant.sources.assets
			?.addGeneratedSourceDirectory(generate, QuickBuildGenerateSourcesTask::generatedAssets)

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
				// Wire the variant source roots (GENERATED ones included - KSP/kapt/viewBinding
				// register their output here) as a file collection, NOT a mapped
				// ListProperty<String>: the config cache realizes ListProperty values at store
				// time, which forces the generated roots' producer providers before those tasks
				// run and fails the store (ADFA-4128 Bug 1). A file collection stores lazily and
				// carries the producer task dependencies; the report resolves paths at execution.
				variant.sources.java
					?.all
					?.let { task.sourceRootDirs.from(it) }
				variant.sources.kotlin
					?.all
					?.let { task.sourceRootDirs.from(it) }
				// AGP's own STABLE_RESOURCE_IDS_FILE for this variant, if any - conventionally
				// intermediates/stable_resource_ids_file/<variantName>/<taskName>/stableIds.txt.
				// The task-name subfolder isn't part of any public API, so the report task
				// probes this directory rather than assuming an exact path (see its KDoc).
				task.stableIdsSearchDir.set(
					buildDirectory.dir("intermediates/stable_resource_ids_file/${variant.name}"),
				)
				// AGP's merged_res closure for this variant - project res PLUS every
				// dependency AAR's transitively-flattened VALUES resources (ADFA-4128 Bug 8).
				// Same probing rationale as stableIdsSearchDir: the task-name subfolder isn't
				// part of any public API.
				task.mergedResSearchDir.set(
					buildDirectory.dir("intermediates/merged_res/${variant.name}"),
				)
				// Every resource-providing dependency's separately-compiled FILE-based
				// resources - NOT part of merged_res's closure (ADFA-4128 Bug 8). Resolved via
				// a plain Gradle ArtifactView over the variant's runtime classpath, lazily (a
				// FileCollection, not an eagerly-mapped property) for the same config-cache
				// reason as sourceRootDirs (Bug 1).
				task.dependencyResourceDirs.from(compiledDependencyResources(variant, project))
				// One report per proxy app build (the contract path CoGo reads); proxy app builds build
				// exactly one variant, so variants never race on it.
				task.reportFile.set(buildDirectory.file("quickbuild/setup.json"))
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
	 * Wires the quick-build runtime AAR, plus CoGo's injected LogSender AAR when
	 * configured - the runtime-only classpath a component's class can resolve from even
	 * though it never appears on the variant compile classpath (the LogSender service is
	 * the one shipping case). Extracted from [QuickBuildPayloadDexTask]'s inline wiring so
	 * it reads as one named step rather than a nested `findProperty` block.
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
	 * Every resource-providing dependency's separately-compiled FILE-based resources
	 * (ADFA-4128 Bug 8): an `ArtifactView` over the variant's runtime classpath
	 * configuration, filtered to [COMPILED_DEPENDENCIES_RESOURCES_ARTIFACT_TYPE]. Each
	 * resolved "file" is actually a DIRECTORY (one per resource-providing library,
	 * containing its compiled `.flat` units directly) - confirmed on-host inspecting the
	 * Gradle transforms cache. `lenient(true)` so a dependency with no such variant (a
	 * plain jar, a project with no resources) is silently skipped rather than failing
	 * resolution. Returns an empty [FileCollection] when the variant exposes no runtime
	 * configuration at all (an AGP variant type this plugin doesn't recognize) - same
	 * fallback shape as [withRuntimeConfiguration].
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
