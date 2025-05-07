@file:Suppress("UnstableApiUsage")

import com.itsaky.androidide.build.config.BuildConfig
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.FileNotFoundException
import java.util.Properties

plugins {
    id("com.android.library")
    id("kotlin-android")
}

android {
    namespace = "${BuildConfig.packageName}.libjdwp"
    ndkVersion = BuildConfig.ndkVersion

    defaultConfig {
        externalNativeBuild {
            cmake {
                val localProps = rootProject.file("local.properties")
                var javaRoot: String? = null
                if (localProps.exists()) {
                    javaRoot = localProps.inputStream().use { input ->
                        val props = Properties()
                        props.load(input)
                        props.getProperty("Java_ROOT", null)
                    }
                }

                if (javaRoot == null) {
                    javaRoot = System.getenv("Java_ROOT")
                }

                if (javaRoot == null) {
                    throw GradleException("Java_ROOT not set")
                }

                arguments += setOf("-DJava_ROOT=${javaRoot}")
                targets += setOf(
                    "dt_socket", "jdwp", "npt", "jdi-support"
                )
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = rootProject.file("oj-libjdwp/CMakeLists.txt")
            version = "3.31.4"
            buildStagingDirectory = file(".cmake")
        }
    }
}

val jdiSupportDir = project.layout.buildDirectory.dir("jdi-support")

val collectLibJdiSupport = tasks.register("collectLibJdiSupport") {
    dependsOn(tasks.matching { it.name.startsWith("externalNativeBuildDebug") })

    doLast {
        val outputDir = jdiSupportDir.get().asFile
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        val fileOut = outputDir.resolve("classes.jar")

        val fileIn = project.fileTree(".cmake").matching {
            include("**/jdi-support.jar")
        }.maxByOrNull { it.lastModified() }
            ?: throw FileNotFoundException("jdi-support.jar not found")

        fileIn.copyTo(fileOut, overwrite = true)
    }
}

tasks.withType<JavaCompile> {
    dependsOn(collectLibJdiSupport)
}

tasks.withType<KotlinCompile> {
    dependsOn(collectLibJdiSupport)
}

dependencies {
    api(fileTree(project.layout.buildDirectory) {
        include("jdi-support/classes.jar")
    })
    api(libs.common.kotlin.coroutines.android)
}