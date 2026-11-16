@file:Suppress("ktlint:standard:max-line-length")

package com.itsaky.androidide.gradle.sqlite

import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.itsaky.androidide.gradle.APP_PLUGIN
import com.itsaky.androidide.gradle.requireRuntimeConfiguration
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_SQLITE_DEBUGGER_ENABLED
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_SQLITE_REWRITE_RUNTIME_AAR
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.logging.Logging
import java.io.File

/**
 * Registers the android.database.sqlite -> requery class rewrite on every variant (ADFA-7138), adds the
 * helpers the rewritten classes call to every variant's runtime classpath, and checks that requery and
 * androidx.sqlite are there too.
 *
 * Applied only when [PROPERTY_SQLITE_DEBUGGER_ENABLED] is "true": the rewritten classes need requery in
 * the app, and it reaches apps only through the template's libs/ (ADFA-7082), so a project that
 * did not opt in would crash at class load. The helper AAR is Code on the Go's, passed in like the
 * debugger's; the build fails when it is missing, because the project opted in.
 */
class SqliteRewritePlugin : Plugin<Project> {
	companion object {
		private val logger = Logging.getLogger(SqliteRewritePlugin::class.java)

		/** AGP's artifact-type attribute for a dependency's classes as a jar; AGP-internal, so the raw string. */
		internal const val CLASSES_JAR_ARTIFACT_TYPE = "android-classes-jar"
	}

	override fun apply(target: Project) {
		if (!target.plugins.hasPlugin(APP_PLUGIN)) {
			return
		}
		logger.info("Applying SqliteRewritePlugin to project '${target.path}'")

		val runtimeAar =
			target.findProperty(PROPERTY_SQLITE_REWRITE_RUNTIME_AAR)?.let { File(it.toString()) }
				?: throw GradleException(
					"'$PROPERTY_SQLITE_DEBUGGER_ENABLED' is true but no property '$PROPERTY_SQLITE_REWRITE_RUNTIME_AAR' is set. " +
						"Remove the line from gradle.properties to build without the SQLite rewrite.",
				)
		if (!runtimeAar.isFile) {
			throw GradleException(
				"SQLite rewrite runtime AAR not found at '${runtimeAar.absolutePath}'. " +
					"Remove '$PROPERTY_SQLITE_DEBUGGER_ENABLED' from gradle.properties to build without it.",
			)
		}

		target.extensions.getByType(ApplicationAndroidComponentsExtension::class.java).onVariants { variant ->
			variant.instrumentation.transformClassesWith(
				SqliteRewriteVisitorFactory::class.java,
				InstrumentationScope.ALL,
			) {}
			// Frames are copied; the rewriter keeps the one method whose descriptor changes valid itself.
			variant.instrumentation.setAsmFramesComputationMode(SqliteRewriter.FRAMES_MODE)

			val capitalized = variant.name.replaceFirstChar { it.uppercase() }
			variant.requireRuntimeConfiguration {
				logger.lifecycle("Adding SQLite rewrite runtime dependency to variant '{}' of project '{}'", variant.name, target.path)
				dependencies.add(target.dependencies.create(target.fileTree(runtimeAar)))

				val check =
					target.tasks.register(
						"check${capitalized}SqliteRewriteClasspath",
						CheckSqliteRewriteClasspathTask::class.java,
					) { task ->
						task.variantName.set(variant.name)
						task.runtimeClasspath.from(
							incoming
								.artifactView { view ->
									view.attributes {
										it.attribute(
											ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
											CLASSES_JAR_ARTIFACT_TYPE,
										)
									}
									view.setLenient(true)
								}.files,
						)
						task.stamp.set(target.layout.buildDirectory.file("intermediates/sqlite_rewrite_classpath/${variant.name}/ok"))
					}
				target.tasks
					.matching { it.name in CheckSqliteRewriteClasspathTask.guardedTaskNames(capitalized) }
					.configureEach { it.dependsOn(check) }
			}
		}
	}
}
