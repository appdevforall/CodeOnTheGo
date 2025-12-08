

package com.itsaky.androidide.templates.impl.pluginProject

fun pluginBuildGradleKts(data: PluginTemplateData): String = """
plugins {
    id("com.android.application") version "8.8.2"
    id("org.jetbrains.kotlin.android") version "2.1.21"
}

android {
    namespace = "${data.pluginId}"
    compileSdk = 34

    defaultConfig {
        applicationId = "${data.pluginId}"
        minSdk = 26
        targetSdk = 34
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
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
    compileOnly(files("libs/plugin-api.jar"))

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.fragment:fragment-ktx:1.8.8")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.21")
}

tasks.wrapper {
    gradleVersion = "8.10.2"
    distributionType = Wrapper.DistributionType.BIN
}

tasks.register<Copy>("assemblePlugin") {
    group = "build"
    description = "Builds and packages the plugin as a .cgp file"

    dependsOn("assembleRelease")

    from(layout.buildDirectory.file("outputs/apk/release/${data.pluginId.substringAfterLast(".")}-release-unsigned.apk"))
    into(layout.buildDirectory.dir("plugin"))
    rename { "${data.className.lowercase()}.cgp" }

    doLast {
        logger.lifecycle("Plugin CGP created: ${"$"}{layout.buildDirectory.get().asFile.absolutePath}/plugin/${data.className.lowercase()}.cgp")
    }
}

tasks.register<Copy>("assemblePluginDebug") {
    group = "build"
    description = "Builds and packages the debug plugin as a .cgp file"

    dependsOn("assembleDebug")

    from(layout.buildDirectory.file("outputs/apk/debug/${data.pluginId.substringAfterLast(".")}-debug.apk"))
    into(layout.buildDirectory.dir("plugin"))
    rename { "${data.className.lowercase()}-debug.cgp" }

    doLast {
        logger.lifecycle("Debug plugin CGP created: ${"$"}{layout.buildDirectory.get().asFile.absolutePath}/plugin/${data.className.lowercase()}-debug.cgp")
    }
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