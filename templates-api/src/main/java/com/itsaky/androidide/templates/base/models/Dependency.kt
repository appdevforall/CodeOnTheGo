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

package com.itsaky.androidide.templates.base.models

import com.itsaky.androidide.templates.base.models.DependencyConfiguration.DebugImplementation

data class Dependency(val configuration: DependencyConfiguration,
                      val group: String, val artifact: String,
                      val version: String?, val tomlDependency: String? = null
) {

  constructor(configuration: DependencyConfiguration, tomlDependency: String): this(configuration, "", "", null, tomlDependency)

  fun tomlValue() : String {
    return """
      ${configuration.configName}($tomlDependency)
    """.trimIndent()
  }

  fun tomlPlatformValue() : String {
    return """
      ${configuration.configName}(platform($tomlDependency))
    """.trimIndent()
  }

  fun value(): String {
    return """
      ${configuration.configName}("${group}:${artifact}${optionalVersion()}")
    """.trimIndent()
  }

  fun platformValue(): String {
    return """
      ${configuration.configName}(platform("${group}:${artifact}${optionalVersion()}"))
    """.trimIndent()
  }

  private fun optionalVersion() = version?.let { ":${it}" } ?: ""

  object AndroidX {

    // Version 2.6.1 results in 'duplicate classes' build issue
    private const val lifecycleVersion = "2.5.1"

    private const val navigationVersion = "2.5.3"

    @JvmStatic
    val AppCompat = parseDependency("androidx.appcompat:appcompat:1.6.1")

    @JvmStatic
    val ConstraintLayout =
      parseDependency("androidx.constraintlayout:constraintlayout:2.1.4")

    @JvmStatic
    val LifeCycle_LiveData = parseDependency(
      "androidx.lifecycle:lifecycle-livedata:${lifecycleVersion}")

    @JvmStatic
    val LifeCycle_LiveData_Ktx = parseDependency(
      "androidx.lifecycle:lifecycle-livedata-ktx:${lifecycleVersion}")

    @JvmStatic
    val LifeCycle_ViewModel = parseDependency(
      "androidx.lifecycle:lifecycle-viewmodel:${lifecycleVersion}")

    @JvmStatic
    val LifeCycle_ViewModel_Ktx = parseDependency(
      "androidx.lifecycle:lifecycle-viewmodel-ktx:${lifecycleVersion}")

    @JvmStatic
    val Navigation_Fragment = parseDependency(
      "androidx.navigation:navigation-fragment:${navigationVersion}")

    @JvmStatic
    val Navigation_Ui =
      parseDependency("androidx.navigation:navigation-ui:${navigationVersion}")

    @JvmStatic
    val Navigation_Fragment_Ktx = parseDependency(
      "androidx.navigation:navigation-fragment-ktx:${navigationVersion}")

    @JvmStatic
    val Navigation_Ui_Ktx = parseDependency(
      "androidx.navigation:navigation-ui-ktx:${navigationVersion}")

    @JvmStatic
    val Startup_Runtime = parseDependency("androidx.startup:startup-runtime:1.1.1")

    @JvmStatic
    val Interpolator = parseDependency("androidx.interpolator:interpolator:1.0.0")

    @JvmStatic
    val Collection_Jvm = parseDependency("androidx.collection:collection-jvm:1.4.2")
  }

  object Compose {

    @JvmStatic
    val Core_Ktx = parseDependency("libs.androidx.core.ktx", isToml = true)

    @JvmStatic
    val LifeCycle_Runtime_Ktx = parseDependency("libs.androidx.lifecycle.runtime.ktx", isToml = true)

    @JvmStatic
    val Activity = parseDependency("libs.androidx.activity.compose", isToml = true)

    @JvmStatic
    val BOM = parseDependency("libs.androidx.compose.bom", isPlatform = true, isToml = true)

    @JvmStatic
    val UI = parseDependency("libs.androidx.ui", isToml = true)

    @JvmStatic
    val UI_Graphics = parseDependency("libs.androidx.ui.graphics", isToml = true)

    @JvmStatic
    val UI_Tooling_Preview =
      parseDependency("libs.androidx.ui.tooling.preview", isToml = true)

    @JvmStatic
    val Material3 = parseDependency("libs.androidx.material3", isToml = true)

    @JvmStatic
    val UI_Tooling_Preview_Android = parseDependency("libs.androidx.ui.tooling.preview.android", isToml = true)

    @JvmStatic
    val UI_Tooling = parseDependency("libs.androidx.ui.tooling",
      configuration = DebugImplementation, isToml = true)

    @JvmStatic
    val UI_Test_Manifest =
      parseDependency("libs.androidx.ui.test.manifest",
        configuration = DebugImplementation, isToml = true)

    @JvmStatic
    val Collection_Ktx = parseDependency("libs.collection.ktx", isToml = true)

    /**
     * This is a compose dep, so it does not require java variant.
     */
    @JvmStatic
    val Kotlin_Stdlib = """
    // Exclude older conflicting version from transitive dependencies
    // Again this arises only when using a local maven repo. Most probably because it lacks flexibility of online one.
    // We can run some gradle:app dependency commands to compare the results for online and offline maven repo later.
    // Use Kotlin stdlib 1.9.22, and exclude old jdk7 and jdk8 versions
    implementation(libs.kotlin.stdlib) {
      exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
      exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    }"""

  }

  object Google {

    @JvmStatic
    val Material = parseDependency("com.google.android.material:material:1.9.0")
  }

  object Kotlin {
    @JvmStatic
    val Kotlinx_Coroutines_Core = parseDependency("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
  }

}