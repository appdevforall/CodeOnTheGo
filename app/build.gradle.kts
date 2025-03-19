@file:Suppress("UnstableApiUsage")

import ch.qos.logback.core.util.EnvUtil
import com.itsaky.androidide.build.config.BuildConfig
import com.itsaky.androidide.desugaring.ch.qos.logback.core.util.DesugarEnvUtil
import com.itsaky.androidide.desugaring.utils.JavaIOReplacements.applyJavaIOReplacements
import com.itsaky.androidide.plugins.AndroidIDEAssetsPlugin
import java.nio.file.Files
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.reflect.jvm.javaMethod

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("kotlin-kapt")
    id("kotlin-parcelize")
    id("androidx.navigation.safeargs.kotlin")
//  id("io.sentry.android.gradle") version "4.2.0"

    id("com.itsaky.androidide.desugaring")
}

apply {
    plugin(AndroidIDEAssetsPlugin::class.java)
}

buildscript {
    dependencies {
        classpath(libs.logging.logback.core)
        classpath(libs.composite.desugaringCore)
    }
}

android {
    namespace = BuildConfig.packageName

    defaultConfig {
        applicationId = BuildConfig.packageName
        vectorDrawables.useSupportLibrary = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["androidx.test.orchestrator.ENABLE"] = "true"
    }

    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }

    androidResources {
        generateLocaleConfig = true
    }

    buildTypes {
        release {
            isShrinkResources = true
        }

        applicationVariants.all {
            outputs.all {
                val date = SimpleDateFormat("-MMdd-HHmm").format(Date())
                val buildTypeName =
                    if (name.contains("dev") || name.contains("debug")) "debug" else "release" // This is the variant's build type (e.g., "debug" or "release")
                val newApkName = "CodeOnTheGo-${buildTypeName}${date}.apk"

                (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                    newApkName
            }
        }
    }

    android {
        sourceSets {
            getByName("androidTest") {
                manifest.srcFile("src/androidTest/AndroidManifest.xml")
            }
        }
    }

    lint {
        abortOnError = false
        disable.addAll(arrayOf("VectorPath", "NestedWeights", "ContentDescription", "SmallSp"))
    }

    installation {
        //installOptions("-timeout", "420000") // 5 minutes (in milliseconds)
    }
}

// Task to create symbolic link on Linux only
tasks.register("createSymbolicLinkForLayoutEditor") {
    // Check if the OS is Linux
    val os: String = System.getProperty("os.name").lowercase(Locale.ENGLISH)

    // Update paths to reflect the correct locations for LayoutEditor
    val sourcePath: java.nio.file.Path = Paths.get(rootDir.absolutePath, "LayoutEditor")
    val destinationPath: java.nio.file.Path = Paths.get(rootDir.absolutePath, "layouteditor")
    val destinationFile = destinationPath.toFile()

    if (os.contains("linux") || os.contains("nix") || os.contains("nux") || os.contains("mac")) {
        // Check if the symbolic link already exists
        if (destinationFile.exists() && Files.isSymbolicLink(destinationPath)) {
            doLast {
                println("Symbolic link already exists: $destinationPath -> $sourcePath")
            }
        } else {
            Files.createSymbolicLink(destinationPath, sourcePath)
        }
    } else {
        doLast {
            println("Skipping symbolic link creation: Not running on Linux.")
        }
    }
}

// Ensure the symbolic link task runs before preBuild
tasks.named("preBuild").configure {
    dependsOn("createSymbolicLinkForLayoutEditor")
}

kapt { arguments { arg("eventBusIndex", "${BuildConfig.packageName}.events.AppEventsIndex") } }

desugaring {
    replacements {
        includePackage(
            "org.eclipse.jgit",
            "ch.qos.logback.classic.util",
        )

        applyJavaIOReplacements()

        // EnvUtil.logbackVersion() uses newer Java APIs like Class.getModule() which is not available
        // on Android. We replace the method usage with DesugarEnvUtil.logbackVersion() which
        // always returns null
        replaceMethod(
            EnvUtil::logbackVersion.javaMethod!!,
            DesugarEnvUtil::logbackVersion.javaMethod!!
        )
    }
}

dependencies {
    //debugImplementation(libs.common.leakcanary)

    // Annotation processors
    kapt(libs.common.glide.ap)
    kapt(libs.google.auto.service)
    kapt(projects.annotationProcessors)
    kapt(libs.room.compiler)

    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    implementation(libs.common.editor)
    implementation(libs.common.utilcode)
    implementation(libs.common.glide)
    implementation(libs.common.jsoup)
    implementation(libs.common.kotlin.coroutines.android)
    implementation(libs.common.retrofit)
    implementation(libs.common.retrofit.gson)
    implementation(libs.common.charts)
    implementation(libs.common.hiddenApiBypass)

    implementation(libs.google.auto.service.annotations)
    implementation(libs.google.gson)
    implementation(libs.google.guava)

    //Room
    implementation(libs.room.ktx)

    // Git
    implementation(libs.git.jgit)

    // AndroidX
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.drawer)
    implementation(libs.androidx.grid)
    implementation(libs.androidx.nav.fragment)
    implementation(libs.androidx.nav.ui)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.transition)
    implementation(libs.androidx.vectors)
    implementation(libs.androidx.animated.vectors)
    implementation(libs.androidx.work)
    implementation(libs.androidx.work.ktx)
    implementation(libs.google.material)
    implementation(libs.google.flexbox)

    // Kotlin
    implementation(libs.androidx.core.ktx)
    implementation(libs.common.kotlin)

    // Dependencies in composite build
    implementation(libs.composite.appintro)
    implementation(libs.composite.desugaringCore)
    implementation(libs.composite.javapoet)

    // Local projects here
    implementation(projects.actions)
    implementation(projects.buildInfo)
    implementation(projects.common)
    implementation(projects.editor)
    implementation(projects.termux.termuxApp)
    implementation(projects.termux.termuxView)
    implementation(projects.termux.termuxEmulator)
    implementation(projects.termux.termuxShared)
    implementation(projects.eventbus)
    implementation(projects.eventbusAndroid)
    implementation(projects.eventbusEvents)
    implementation(projects.gradlePluginConfig)
    implementation(projects.idestats)
    implementation(projects.subprojects.aaptcompiler)
    implementation(projects.subprojects.javacServices)
    implementation(projects.subprojects.xmlUtils)
    implementation(projects.subprojects.projects)
    implementation(projects.subprojects.toolingApi)
    implementation(projects.logsender)
    implementation(projects.lsp.api)
    implementation(projects.lsp.java)
    implementation(projects.lsp.xml)
    implementation(projects.lexers)
    implementation(projects.lookup)
    implementation(projects.preferences)
    implementation(projects.resources)
    implementation(projects.treeview)
    implementation(projects.templatesApi)
    implementation(projects.templatesImpl)
    implementation(projects.uidesigner)
    implementation(projects.xmlInflater)

    implementation(projects.layouteditor.layouteditorApp)
    //implementation(projects.layouteditor.vectormaster)

    //LaoutEditor
    //implementation(libs.desugar.jdk.libs)
    //implementation(libs.editor)
    //implementation(libs.textmate)
    //implementation(libs.junit)
    //implementation(libs.androidx.junit)
    //implementation(libs.androidx.espresso.core)
    //implementation(libs.androidx.lifecycle.runtime.ktx)
    //implementation(libs.androidx.activity.compose)
    //implementation(platform(libs.androidx.compose.bom))
    //implementation(libs.androidx.ui)
    //implementation(libs.androidx.ui.graphics)
    //implementation(libs.androidx.ui.tooling)
    //implementation(libs.androidx.ui.tooling.preview)
    //implementation(libs.androidx.ui.test.manifest)
    //implementation(libs.androidx.ui.test.junit4)
    //implementation(libs.androidx.material3)
    //implementation(libs.material)
    //implementation(libs.utilcodex)
    //implementation(libs.zoomage)
    //implementation(files("libs/layouteditor-release.aar"))

    // This is to build the tooling-api-impl project before the app is built
    // So we always copy the latest JAR file to assets
    compileOnly(projects.subprojects.toolingApiImpl)

    androidTestImplementation(libs.tests.kaspresso)
    androidTestImplementation(libs.tests.junit.kts)
    androidTestUtil(libs.tests.orchestrator)

    testImplementation(projects.testing.unit)
    testImplementation(libs.core.tests.anroidx.arch)
    androidTestImplementation(projects.testing.android)

    androidTestImplementation(libs.tests.androidx.test.runner)
}

//sentry {
//    org.set("appdevforall-inc-pb")
//    projectName.set("android")
//
//    // this will upload your source code to Sentry to show it as part of the stack traces
//    // disable if you don't want to expose your sources
//    includeSourceContext.set(true)
//}
