@file:Suppress("UnstableApiUsage")

import com.itsaky.androidide.build.config.BuildConfig
import com.itsaky.androidide.build.config.downloadVersion
import com.itsaky.androidide.plugins.TerminalBootstrapPackagesPlugin
import com.itsaky.androidide.plugins.tasks.CopyTermuxCacheAbiTask
import com.itsaky.androidide.plugins.tasks.GenerateInitScriptTask
import com.itsaky.androidide.plugins.tasks.GradleWrapperGeneratorTask

plugins {
    id("com.android.library")
    id("kotlin-android")
}

val packageVariant = System.getenv("TERMUX_PACKAGE_VARIANT") ?: "apt-android-7" // Default: "apt-android-7"

android {
    namespace = "com.termux"
    ndkVersion = BuildConfig.ndkVersion

    defaultConfig {
        buildConfigField("String", "TERMUX_PACKAGE_VARIANT", "\"" + packageVariant + "\"") // Used by TermuxApplication class

        manifestPlaceholders["TERMUX_PACKAGE_NAME"] = BuildConfig.packageName
        manifestPlaceholders["TERMUX_APP_NAME"] = "AndroidIDE"

        externalNativeBuild {
            ndkBuild {
                cFlags("-std=c11", "-Wall", "-Wextra", "-Werror", "-Os", "-fno-stack-protector", "-Wl,--gc-sections")
            }
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/cpp/Android.mk")
        }
    }

    lint.disable += "ProtectedPermissions"

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging.jniLibs.useLegacyPackaging = true
}

android {
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

androidComponents {
    onVariants { variant ->
        var flavorName = variant.productFlavors.firstOrNull()?.second ?: "default"

        when (flavorName) {
            "v8" -> variant.sources.assets?.addGeneratedSourceDirectory(
                copyV8TermuxCache,
                CopyTermuxCacheAbiTask::outputDirectory)
            "v7" -> variant.sources.assets?.addGeneratedSourceDirectory(
                copyV7TermuxCache,
                CopyTermuxCacheAbiTask::outputDirectory)
            // NOTE: disable x86_64 builds for now
            //"x86" -> variant.sources.assets?.addGeneratedSourceDirectory(
            //    copyX86TermuxCache,
            //    CopyTermuxCacheAbiTask::outputDirectory)
            else -> variant.sources.assets?.addGeneratedSourceDirectory(
                copyV8TermuxCache,
                CopyTermuxCacheAbiTask::outputDirectory)// default to v8
        }
   }
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core)
    implementation(libs.androidx.drawer)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.viewpager)
    implementation(libs.google.material)
    implementation(libs.google.guava)
    implementation(libs.common.markwon.core)
    implementation(libs.common.markwon.extStrikethrough)
    implementation(libs.common.markwon.linkify)
    implementation(libs.common.markwon.recycler)

    implementation(projects.common)
    implementation(projects.preferences)
    implementation(projects.resources)
    implementation(projects.termux.termuxView)
    implementation(projects.termux.termuxShared)

    testImplementation(projects.testing.unit)
}

tasks.register("versionName") {
    doLast {
        print(project.rootProject.version)
    }
}

tasks.register("applyTerminalBootstrapPackagesPlugin") {
    doFirst {
        project.pluginManager.apply(TerminalBootstrapPackagesPlugin::class.java)
    }
}

tasks.named("preBuild").configure {
    dependsOn("applyTerminalBootstrapPackagesPlugin")
}

val copyV8TermuxCache = tasks.register<CopyTermuxCacheAbiTask>("copyV8TermuxCache")
copyV8TermuxCache.configure {
    srcDir = "v8"
}

val copyV7TermuxCache = tasks.register<CopyTermuxCacheAbiTask>("copyV7TermuxCache") {
    srcDir = "v7"
}

// NOTE: disable x86_64 builds for now
//val copyX86TermuxCache = tasks.register<CopyTermuxCacheAbiTask>("copyX86TermuxCache") {
//    srcDir = "x86"
//}

afterEvaluate {
    listOf("generateV8DebugResValues", "generateV8ReleaseResValues").forEach { taskName ->
        tasks.named(taskName).configure {
            dependsOn("copyV8TermuxCache")
        }
    }

    listOf("generateV7DebugResValues", "generateV7ReleaseResValues").forEach { taskName ->
        tasks.named(taskName).configure {
            dependsOn("copyV7TermuxCache")
        }
    }
    // NOTE: disable x86_64 builds for now
    //listOf("generateX86DebugResValues", "generateX86ReleaseResValues").forEach { taskName ->
    //    tasks.named(taskName).configure {
    //        dependsOn("copyX86TermuxCache")
    //    }
    //}
}
