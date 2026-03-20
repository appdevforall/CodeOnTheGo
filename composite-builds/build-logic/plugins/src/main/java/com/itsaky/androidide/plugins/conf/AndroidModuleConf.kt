/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.plugins.conf

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.Variant
import com.itsaky.androidide.build.config.BuildConfig
import com.itsaky.androidide.build.config.projectVersionCode
import com.itsaky.androidide.build.config.simpleVersionName
import com.itsaky.androidide.plugins.util.SdkUtils.getAndroidJar
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.provider.Provider

/**
 * ABIs for which the product flavors will be created.
 * The keys in this map are the names of the product flavors whereas,
 * the value for each flavor is a number that will be incremented to the base
 * version code of the IDE and set as the version code of that flavor.
 *
 * For example, if the base version code of the IDE is 270 (for v2.7.0), then for arm64-v8a
 * flavor, the version code will be `100 * 270 + 1` i.e. `27001`
 */
val COTG_FLAVOR_ABIS = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2)

private val disableCoreLibDesugaringForModules =
	arrayOf(
		":logsender",
		":logger",
	)

/**
 * The name of the build type used for automated testing.
 */
private const val INSTRUMENTATION_BUILD_TYPE = "instrumentation"

/**
 * Whether the given variant has bundled assets or not.
 *
 * This is `true` for non-debug builds and for [INSTRUMENTATION_BUILD_TYPE] builds. When updating this
 * value, please update the corresponding value in `AssetsInstaller.kt` in `:app` module.
 */
fun hasBundledAssets(variant: Variant): Boolean =
	!variant.debuggable || variant.buildType == INSTRUMENTATION_BUILD_TYPE

fun Project.configureAndroidModule(coreLibDesugDep: Provider<MinimalExternalModuleDependency>) {
	val isAppModule = plugins.hasPlugin("com.android.application")
	assert(
		isAppModule || plugins.hasPlugin("com.android.library"),
	) {
		"${javaClass.simpleName} can only be applied to Android projects"
	}

	val androidJar =
		extensions
			.getByType(AndroidComponentsExtension::class.java)
			.getAndroidJar(assertExists = true)

	findProject(":subprojects:framework-stubs")
		?.file("libs/android.jar")
		.also { it?.parentFile?.mkdirs() }
		?.also { frameworkStubsJar ->
			if (!(frameworkStubsJar.exists() && frameworkStubsJar.isFile)) {
				androidJar.copyTo(frameworkStubsJar)
			}
		}

	extensions.getByType(CommonExtension::class.java).apply {
		compileSdk = BuildConfig.COMPILE_SDK
		defaultConfig.apply {
			minSdk = BuildConfig.MIN_SDK

			ndk {
				abiFilters.clear()
				abiFilters += COTG_FLAVOR_ABIS.keys
			}
		}

		compileOptions.apply {
			sourceCompatibility = BuildConfig.JAVA_VERSION
			targetCompatibility = BuildConfig.JAVA_VERSION
		}

		configureCoreLibDesugaring(this, coreLibDesugDep)

		lint.apply {
			checkDependencies = true
		}

		buildTypes.create(INSTRUMENTATION_BUILD_TYPE) {
			initWith(buildTypes.getByName("debug"))
			matchingFallbacks += "debug"
		}

		buildTypes.getByName("debug") {
			isMinifyEnabled = false
		}

		buildTypes.getByName("release") {
			isMinifyEnabled = isAppModule
			isShrinkResources = isAppModule

			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"),
				"proguard-rules.pro",
			)
		}

		buildFeatures.viewBinding = true
		buildFeatures.buildConfig = true
	}

	extensions.findByType(LibraryExtension::class.java)?.apply {
		buildTypes.getByName("release") {
			consumerProguardFiles("consumer-rules.pro")
		}

		testOptions { unitTests.isIncludeAndroidResources = true }
	}

	extensions.findByType(ApplicationExtension::class.java)?.apply {
		defaultConfig {
			targetSdk = BuildConfig.TARGET_SDK
			targetSdk = BuildConfig.TARGET_SDK
			versionCode = projectVersionCode
			versionName = rootProject.simpleVersionName

			// required
			multiDexEnabled = true

			testInstrumentationRunner =
				"com.itsaky.androidide.testing.android.TestInstrumentationRunner"
			testInstrumentationRunnerArguments["androidx.test.orchestrator.ENABLE"] = "true"
			testInstrumentationRunnerArguments["androidide.test.mode"] = "true"
		}
	}
}

private fun Project.configureCoreLibDesugaring(
	commonExtension: CommonExtension,
	coreLibDesugaringDep: Provider<MinimalExternalModuleDependency>,
) {
	val coreLibDesugaringEnabled = project.path !in disableCoreLibDesugaringForModules

	commonExtension.compileOptions.isCoreLibraryDesugaringEnabled = coreLibDesugaringEnabled

	if (coreLibDesugaringEnabled) {
		project.dependencies.add("coreLibraryDesugaring", coreLibDesugaringDep)
	}
}
