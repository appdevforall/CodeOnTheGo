import com.itsaky.androidide.build.config.BuildConfig

plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.library)
    id("kotlin-kapt")
    kotlin("plugin.serialization")
}

android {
    namespace = "${BuildConfig.PACKAGE_NAME}.agent"
    buildFeatures {
        viewBinding = true
    }
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    packaging {
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }
    sourceSets {
        getByName("androidTest") {
            assets.srcDirs("src/androidTest/assets", "$buildDir/generated/androidTest/assets")
        }
    }
}

// Task to extract processed Llama AAR (with classes.dex) from app's assets zip for integration tests
// The app module processes the AAR with D8 to convert classes.jar to classes.dex
val extractLlamaAarForTests by tasks.registering {
    description = "Extracts processed Llama AAR files from app's assets zip for integration tests"

    val v8AssetsZip = file("../app/build/outputs/assets/assets-arm64-v8a.zip")
    val v7AssetsZip = file("../app/build/outputs/assets/assets-armeabi-v7a.zip")
    val outputDir = file("$buildDir/generated/androidTest/assets/dynamic_libs")

    inputs.files(v8AssetsZip, v7AssetsZip).optional()
    outputs.dir(outputDir)

    doLast {
        outputDir.mkdirs()

        // Extract v8 AAR
        if (v8AssetsZip.exists()) {
            val v8Dest = File(outputDir, "llama-v8.aar")
            project.exec {
                commandLine("unzip", "-p", v8AssetsZip.absolutePath, "dynamic_libs/llama.aar")
                standardOutput = v8Dest.outputStream()
            }
            logger.lifecycle("Extracted processed Llama v8 AAR to ${v8Dest.absolutePath}")
        } else {
            logger.warn("V8 assets zip not found at ${v8AssetsZip.absolutePath}. Run ':app:packageAssetsV8' first.")
        }

        // Extract v7 AAR
        if (v7AssetsZip.exists()) {
            val v7Dest = File(outputDir, "llama-v7.aar")
            project.exec {
                commandLine("unzip", "-p", v7AssetsZip.absolutePath, "dynamic_libs/llama.aar")
                standardOutput = v7Dest.outputStream()
            }
            logger.lifecycle("Extracted processed Llama v7 AAR to ${v7Dest.absolutePath}")
        } else {
            logger.warn("V7 assets zip not found at ${v7AssetsZip.absolutePath}. Run ':app:packageAssetsV7' first.")
        }
    }
}

// Make sure the extract task runs before androidTest tasks
tasks.matching { it.name.contains("AndroidTest") && it.name.contains("Assets") }.configureEach {
    dependsOn(extractLlamaAarForTests)
}

dependencies {
    implementation(projects.common)
    implementation(projects.commonUi)
    implementation(projects.subprojects.projects)
    implementation(projects.idetooltips)
    implementation(projects.resources)
    implementation(projects.llamaApi)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.nav.fragment)
    implementation(libs.androidx.nav.ui)

    implementation(libs.google.material)
    implementation(libs.common.kotlin.coroutines.android)
    implementation(libs.koin.android)
    implementation(libs.common.markwon.core)
    implementation(libs.common.markwon.linkify)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation(libs.tooling.slf4j)
    implementation("io.github.java-diff-utils:java-diff-utils:4.12")
    implementation(libs.androidx.security.crypto)
    implementation(libs.google.genai)
    "v7Implementation"(files("../app/libs/v7/llama-v7-release.aar"))
    "v8Implementation"(files("../app/libs/v8/llama-v8-release.aar"))

    testImplementation(projects.testing.unit)
    testImplementation("io.mockk:mockk:1.13.3")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.1")

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.1")

    // Use slf4j-android for tests instead of Logback (Logback uses Java modules which Android doesn't support)
    androidTestImplementation("org.slf4j:slf4j-android:1.7.36")
}

configurations.named("androidTestImplementation") {
    exclude(group = "ch.qos.logback", module = "logback-classic")
    exclude(group = "ch.qos.logback", module = "logback-core")
}
