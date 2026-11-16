package com.itsaky.androidide.gradle

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.Component
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_SQLITE_DEBUGGER_AAR
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_SQLITE_DEBUGGER_ENABLED
import org.adfa.constants.KOTLIN_VERSION
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.Logging
import java.io.File
import java.lang.reflect.Method

/**
 * Adds the SQLite debugger (ADFA-7131) to the debuggable variants of an Android application, as
 * [LogSenderPlugin] does for LogSender. [AndroidIDEGradlePlugin] applies it only when the project
 * sets [PROPERTY_SQLITE_DEBUGGER_ENABLED] to `true`.
 *
 * Fails the build when the AAR is not there: the project opted in, and a build that quietly drops
 * the debugger leaves the user wondering why no database shows up.
 */
class SqliteDebuggerPlugin : Plugin<Project> {
	companion object {
		private val logger = Logging.getLogger(SqliteDebuggerPlugin::class.java)

		/**
		 * The debugger is Kotlin, and the AAR is a file dependency, which brings no POM and so no
		 * kotlin-stdlib. KOTLIN_VERSION is the version CoGo's local Maven repository always holds
		 * (new projects are generated with it), so this resolves offline. Gradle keeps the highest
		 * version of a module, so an app on a newer Kotlin is not downgraded.
		 */
		internal const val KOTLIN_STDLIB_COORDINATES = "org.jetbrains.kotlin:kotlin-stdlib:$KOTLIN_VERSION"

		/**
		 * `Component.debuggable`, which AGP added after the oldest version this plugin supports, so it is
		 * reached by reflection: minAgpCheck compiles this file against that oldest version. Null there.
		 */
		private val componentDebuggable: Method? = runCatching { Component::class.java.getMethod("getDebuggable") }.getOrNull()

		/** Null when this AGP has no `Variant.debuggable`. */
		internal fun readDebuggable(variant: ApplicationVariant): Boolean? = componentDebuggable?.invoke(variant) as Boolean?
	}

	override fun apply(target: Project) {
		if (!target.plugins.hasPlugin(APP_PLUGIN)) {
			return
		}

		logger.info("Applying {} to project '${target.path}'", SqliteDebuggerPlugin::class.simpleName)

		if (target.isTestEnv) {
			logger.lifecycle("Applying {} to project '{}'", javaClass.simpleName, target.path)
		}

		val aar =
			target.findProperty(PROPERTY_SQLITE_DEBUGGER_AAR)?.let { File(it.toString()) }
				?: throw GradleException(
					"'$PROPERTY_SQLITE_DEBUGGER_ENABLED' is true but no property '$PROPERTY_SQLITE_DEBUGGER_AAR' is set. " +
						"Remove the line from gradle.properties to build without the SQLite debugger.",
				)

		if (!aar.isFile) {
			throw GradleException(
				"SQLite debugger AAR not found at '${aar.absolutePath}'. " +
					"Remove '$PROPERTY_SQLITE_DEBUGGER_ENABLED' from gradle.properties to build without it.",
			)
		}

		target.extensions.getByType(ApplicationAndroidComponentsExtension::class.java).apply {
			val addDebugger = { variant: ApplicationVariant ->
				variant.requireRuntimeConfiguration {
					logger.lifecycle(
						"Adding SQLite debugger dependency to variant '{}' of project '{}'",
						variant.name,
						target.path,
					)

					dependencies.add(target.dependencies.create(target.fileTree(aar)))
					dependencies.add(target.dependencies.create(KOTLIN_STDLIB_COORDINATES))
				}
			}
			// onDebuggableVariants reads variantBuilder.debuggable in beforeVariants, which AGP 8.11 rejects
			// when a plugin is applied from CoGo's init script (see QuickBuildPlugin). Variant.debuggable in
			// onVariants is the sanctioned read; AGPs that predate it still allow the builder's.
			if (componentDebuggable != null) {
				onVariants { variant ->
					if (readDebuggable(variant) == true) {
						addDebugger(variant)
					}
				}
			} else {
				onDebuggableVariants(addDebugger)
			}
		}
	}
}
