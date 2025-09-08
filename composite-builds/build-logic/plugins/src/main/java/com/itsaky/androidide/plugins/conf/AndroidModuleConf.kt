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

import com.android.build.api.dsl.CommonExtension
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.FilterConfiguration
import com.android.build.api.variant.Variant
import com.android.build.api.variant.impl.getFilter
import com.android.build.gradle.AppExtension
import com.android.build.gradle.BaseExtension
import com.itsaky.androidide.build.config.BuildConfig
import com.itsaky.androidide.build.config.projectVersionCode
import com.itsaky.androidide.build.config.simpleVersionName
import com.itsaky.androidide.plugins.util.SdkUtils.getAndroidJar
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.provider.Provider
import java.text.SimpleDateFormat
import java.util.Date

/**
 * ABIs for which the product flavors will be created.
 * The keys in this map are the names of the product flavors whereas,
 * the value for each flavor is a number that will be incremented to the base version code of the IDE
 * and set as the version code of that flavor.
 *
 * For example, if the base version code of the IDE is 270 (for v2.7.0), then for arm64-v8a
 * flavor, the version code will be `100 * 270 + 1` i.e. `27001`
 */
internal val flavorsAbis = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2)
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
internal fun hasBundledAssets(variant: Variant): Boolean = !variant.debuggable || variant.buildType == INSTRUMENTATION_BUILD_TYPE

fun Project.configureAndroidModule(coreLibDesugDep: Provider<MinimalExternalModuleDependency>) {
	var isAppModule = plugins.hasPlugin("com.android.application")
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

	extensions.getByType(CommonExtension::class.java).run {
		lint {
			checkDependencies = true
		}

		packaging {
			resources {
				excludes.addAll(
					arrayOf(
						"META-INF/CHANGES",
						"META-INF/README.md",
						"META-INF/LICENSE-notice.md",
						"com/sun/jna/**",
					),
				)
				pickFirsts.addAll(
					arrayOf(
						"META-INF/eclipse.inf",
						"META-INF/LICENSE.md",
						"META-INF/AL2.0",
						"META-INF/LGPL2.1",
						"META-INF/INDEX.LIST",
						"META-INF/versions/9/OSGI-INF/MANIFEST.MF",
						"about_files/LICENSE-2.0.txt",
						"plugin.xml",
						"plugin.properties",
						"about.mappings",
						"about.properties",
						"about.ini",
						"modeling32.png",
					),
				)
			}
		}
	}

	extensions.getByType(BaseExtension::class.java).run {
		compileSdkVersion(BuildConfig.COMPILE_SDK)

		defaultConfig {
			minSdk = BuildConfig.MIN_SDK
			targetSdk = BuildConfig.TARGET_SDK
			versionCode = projectVersionCode
			versionName = rootProject.simpleVersionName

			// required
			multiDexEnabled = true

			testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
		}

		compileOptions {
			sourceCompatibility = BuildConfig.JAVA_VERSION
			targetCompatibility = BuildConfig.JAVA_VERSION
		}

		configureCoreLibDesugaring(this, coreLibDesugDep)

		if (":app" == project.path) {
			packagingOptions {
				jniLibs {
					useLegacyPackaging = true
				}
			}

			flavorsAbis.forEach { (abi, _) ->
				// the common defaultConfig, not the flavor-specific
				defaultConfig.buildConfigField(
					"String",
					"ABI_${abi.replace('-', '_').uppercase()}",
					"\"${abi}\"",
				)
			}

			extensions.getByType(ApplicationAndroidComponentsExtension::class.java).apply {
				onVariants { variant ->
					variant.outputs.forEach { output ->
						// version code increment
						val filter = output.getFilter(FilterConfiguration.FilterType.ABI)
						val verCodeIncrement = flavorsAbis[filter?.identifier] ?: 10000
						output.versionCode.set(100 * projectVersionCode + verCodeIncrement)
					}

					if (hasBundledAssets(variant)) {
						// include bundled assets in the APK
						val assetsDir = rootProject.file("assets/release")
						variant.sources.assets?.apply {
							val commonAssets = assetsDir.resolve("common")
							val flavorAssets = assetsDir.resolve(variant.flavorName!!)

							if (!commonAssets.isDirectory) {
								throw GradleException("${commonAssets.absolutePath} does not exist or is not a directory")
							}

							if (!flavorAssets.isDirectory) {
								throw GradleException("${flavorAssets.absolutePath} does not exist or is not a directory")
							}

							addStaticSourceDirectory(commonAssets.absolutePath)
							addStaticSourceDirectory(flavorAssets.absolutePath)
						}
					}
				}
			}

			extensions.getByType(AppExtension::class.java).apply {
				applicationVariants.all {
					outputs.all {
						val flavorName = productFlavors.firstOrNull()?.name ?: "default"
						val date = SimpleDateFormat("-MMdd-HHmm").format(Date())
						val buildTypeName = buildType.name
						val newApkName = "CodeOnTheGo-$flavorName-${buildTypeName}$date.apk"

						(this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = newApkName
					}
				}
			}
		} else {
			defaultConfig {
				ndk {
					abiFilters.clear()
					abiFilters += flavorsAbis.keys
				}
			}
		}

		flavorDimensions("abi")

		productFlavors {
			create("v7") {
				dimension = "abi"

				ndk.abiFilters.clear()
				ndk.abiFilters += "armeabi-v7a"
			}

			create("v8") {
				dimension = "abi"

				ndk.abiFilters.clear()
				ndk.abiFilters += "arm64-v8a"
			}
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
			consumerProguardFiles("consumer-rules.pro")
		}

		testOptions { unitTests.isIncludeAndroidResources = true }

		buildFeatures.viewBinding = true
		buildFeatures.buildConfig = true
	}
}

private fun Project.configureCoreLibDesugaring(
	baseExtension: BaseExtension,
	coreLibDesugDep: Provider<MinimalExternalModuleDependency>,
) {
	val coreLibDesugaringEnabled = project.path !in disableCoreLibDesugaringForModules

	baseExtension.compileOptions.isCoreLibraryDesugaringEnabled = coreLibDesugaringEnabled

	if (coreLibDesugaringEnabled) {
		project.dependencies.add("coreLibraryDesugaring", coreLibDesugDep)
	}
}
