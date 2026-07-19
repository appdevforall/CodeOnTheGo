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
import com.itsaky.androidide.gradle.quickbuild.QuickBuildSetupReportTask
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_QUICK_BUILD_RUNTIME_AAR
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.logging.Logging
import java.io.File
import java.io.FileNotFoundException

/**
 * Turns a debuggable application build into the Quick Build setup build (ADFA-4128): a
 * one-per-baseline real Gradle build that produces the installable test app.
 *
 * Applied by [AndroidIDEGradlePlugin] when
 * [com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_QUICK_BUILD_ENABLED] is
 * true. For every debuggable application variant it:
 *
 * - injects the quick-build runtime AAR into the runtime classpath (LogSender pattern);
 * - suffixes the application id with `.quickbuild` so test app and real app coexist;
 * - rewrites the merged manifest: proxy component names + the runtime's
 *   appComponentFactory (everything else - permissions, icon, label, filters - kept);
 * - diverts all project-scope classes out of the APK and into the baseline payload dex,
 *   baked in as assets/quickbuild/gen-0.dex next to assets/quickbuild/components.json;
 * - writes build/quickbuild/setup.json for CoGo to read after the build.
 */
class QuickBuildPlugin : Plugin<Project> {
	companion object {
		private val logger = Logging.getLogger(QuickBuildPlugin::class.java)

		/** Suffix that keeps the test app installable next to the user's real app. */
		const val TEST_APP_ID_SUFFIX = ".quickbuild"

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
			extension.buildTypes.forEach { buildType ->
				if (buildType.isDebuggable) {
					buildType.applicationIdSuffix = buildType.applicationIdSuffix.orEmpty() + TEST_APP_ID_SUFFIX
				}
			}
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
				configureVariant(target, variant, runtimeAar, bootClasspath) { composeEnabled }
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
				task.compileClasspath.from(variant.compileClasspath)
				task.runtimeAar.from(runtimeAar)
				task.bootClasspath.from(bootClasspath)
				task.minApiLevel.set(maxOf(variant.minSdk.apiLevel, MIN_PAYLOAD_API))
				task.proxyClasses.set(buildDirectory.dir("$variantDir/proxy-classes"))
			}
		variant.sources.assets
			?.addGeneratedSourceDirectory(dex, QuickBuildPayloadDexTask::generatedAssets)

		val report =
			project.tasks.register(
				variant.generateTaskName("write", "QuickBuildSetupReport"),
				QuickBuildSetupReportTask::class.java,
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
				// One report per setup build (the contract path CoGo reads); setup builds build
				// exactly one variant, so variants never race on it.
				task.setupReport.set(buildDirectory.file("quickbuild/setup.json"))
			}

		// Ensure a plain `assemble<Variant>` setup build also produces the report.
		val assembleTaskName = variant.generateTaskName("assemble")
		project.tasks.matching { it.name == assembleTaskName }.configureEach { assemble ->
			assemble.finalizedBy(report)
		}
	}

	private fun ApplicationVariant.withRuntimeConfiguration(action: Configuration.() -> Unit) {
		if (this is ApplicationVariantImpl) {
			variantDependencies.runtimeClasspath.action()
		} else if (this is AnalyticsEnabledApplicationVariant) {
			delegate.withRuntimeConfiguration(action)
		}
	}
}
