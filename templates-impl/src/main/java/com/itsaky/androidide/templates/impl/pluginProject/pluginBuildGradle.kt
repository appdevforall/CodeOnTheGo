package com.itsaky.androidide.templates.impl.pluginProject

import com.itsaky.androidide.utils.Environment
import org.adfa.constants.ANDROID_GRADLE_PLUGIN_VERSION
import org.adfa.constants.COMPILE_SDK_VERSION
import org.adfa.constants.GRADLE_DISTRIBUTION_VERSION
import org.adfa.constants.JAVA_SOURCE_VERSION
import org.adfa.constants.JAVA_TARGET_VERSION
import org.adfa.constants.TARGET_SDK_VERSION

const val PLUGIN_MIN_SDK = 28
const val PLUGIN_KOTLIN_VERSION = "2.1.0"
const val APPCOMPAT_VERSION = "1.6.1"
const val MATERIAL_VERSION = "1.10.0"
const val FRAGMENT_KTX_VERSION = "1.8.8"

fun pluginBuildGradleKts(data: PluginTemplateData): String = """
plugins {
    id("com.android.application") version "$ANDROID_GRADLE_PLUGIN_VERSION"
    id("org.jetbrains.kotlin.android") version "$PLUGIN_KOTLIN_VERSION"
}

android {
    namespace = "${data.pluginId}"
    compileSdk = ${COMPILE_SDK_VERSION.api}

    defaultConfig {
        applicationId = "${data.pluginId}"
        minSdk = $PLUGIN_MIN_SDK
        targetSdk = ${TARGET_SDK_VERSION.api}
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_$JAVA_SOURCE_VERSION
        targetCompatibility = JavaVersion.VERSION_$JAVA_TARGET_VERSION
    }

    kotlinOptions {
        jvmTarget = "$JAVA_TARGET_VERSION"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }
}

dependencies {
    compileOnly(files("${Environment.PLUGIN_API_JAR_RELATIVE_PATH}"))

    implementation("androidx.appcompat:appcompat:$APPCOMPAT_VERSION")
    implementation("com.google.android.material:material:$MATERIAL_VERSION")
    implementation("androidx.fragment:fragment-ktx:$FRAGMENT_KTX_VERSION")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$PLUGIN_KOTLIN_VERSION")
}

tasks.wrapper {
    gradleVersion = "$GRADLE_DISTRIBUTION_VERSION"
    distributionType = Wrapper.DistributionType.BIN
}

tasks.register<Copy>("assemblePlugin") {
    group = "build"
    description = "Builds and packages the plugin as a .cgp file"

    dependsOn("assembleRelease")

    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
    }
    into(layout.buildDirectory.dir("plugin"))
    rename { "${data.className.lowercase()}.cgp" }

    doLast {
        logger.lifecycle("Plugin CGP created in: ${"$"}{layout.buildDirectory.get().asFile.absolutePath}/plugin/")
    }
}

tasks.register<Copy>("assemblePluginDebug") {
    group = "build"
    description = "Builds and packages the debug plugin as a .cgp file"

    dependsOn("assembleDebug")

    from(layout.buildDirectory.dir("outputs/apk/debug")) {
        include("*.apk")
    }
    into(layout.buildDirectory.dir("plugin"))
    rename { "${data.className.lowercase()}-debug.cgp" }

    doLast {
        logger.lifecycle("Debug plugin CGP created in: ${"$"}{layout.buildDirectory.get().asFile.absolutePath}/plugin/")
    }
}

tasks.matching {
    it.name.contains("checkDebugAarMetadata") ||
    it.name.contains("checkReleaseAarMetadata")
}.configureEach {
    enabled = false
}
""".trimIndent()

fun pluginSettingsGradleKts(data: PluginTemplateData): String = """
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "${data.pluginName}"
""".trimIndent()

fun pluginGradleProperties(): String = """
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
""".trimIndent()

fun pluginProguardRules(): String = """
# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Keep plugin main class
-keep class * implements com.itsaky.androidide.plugins.api.IPlugin { *; }

# Keep all extension implementations
-keep class * implements com.itsaky.androidide.plugins.api.extensions.UIExtension { *; }
-keep class * implements com.itsaky.androidide.plugins.api.extensions.EditorTabExtension { *; }
-keep class * implements com.itsaky.androidide.plugins.api.extensions.DocumentationExtension { *; }
-keep class * implements com.itsaky.androidide.plugins.api.extensions.EditorExtension { *; }
-keep class * implements com.itsaky.androidide.plugins.api.extensions.ProjectExtension { *; }

# Keep Fragment classes
-keep class * extends androidx.fragment.app.Fragment { *; }
""".trimIndent()