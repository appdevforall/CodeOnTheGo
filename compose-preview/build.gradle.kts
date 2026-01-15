import com.itsaky.androidide.build.config.BuildConfig
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.library")
    id("kotlin-android")
    alias(libs.plugins.kotlin.compose)
}

val composeVersion = "1.6.0"
val material3Version = "1.2.0"
val kotlinVersion = "1.9.22"
val composeCompilerVersion = "1.5.10"

val kotlinJarsForPreview: Configuration by configurations.creating {
    isTransitive = false
}

val composeAarsForPreview: Configuration by configurations.creating {
    isTransitive = false
}

dependencies {
    kotlinJarsForPreview("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinVersion")
    kotlinJarsForPreview("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    kotlinJarsForPreview("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
    kotlinJarsForPreview("org.jetbrains.kotlin:kotlin-script-runtime:$kotlinVersion")
    kotlinJarsForPreview("org.jetbrains.intellij.deps:trove4j:1.0.20200330")
    kotlinJarsForPreview("org.jetbrains:annotations:24.0.0")
    kotlinJarsForPreview("androidx.compose.compiler:compiler:$composeCompilerVersion")
    kotlinJarsForPreview("androidx.annotation:annotation:1.7.1")

    composeAarsForPreview("androidx.compose.runtime:runtime-android:$composeVersion")
    composeAarsForPreview("androidx.compose.ui:ui-android:$composeVersion")
    composeAarsForPreview("androidx.compose.ui:ui-graphics-android:$composeVersion")
    composeAarsForPreview("androidx.compose.ui:ui-text-android:$composeVersion")
    composeAarsForPreview("androidx.compose.ui:ui-unit-android:$composeVersion")
    composeAarsForPreview("androidx.compose.ui:ui-geometry-android:$composeVersion")
    composeAarsForPreview("androidx.compose.foundation:foundation-android:$composeVersion")
    composeAarsForPreview("androidx.compose.foundation:foundation-layout-android:$composeVersion")
    composeAarsForPreview("androidx.compose.material3:material3-android:$material3Version")
    composeAarsForPreview("androidx.compose.ui:ui-tooling-preview-android:$composeVersion")
    composeAarsForPreview("androidx.activity:activity-compose:1.8.2")
    composeAarsForPreview("androidx.activity:activity-ktx:1.8.2")
    composeAarsForPreview("androidx.activity:activity:1.8.2")
    composeAarsForPreview("androidx.lifecycle:lifecycle-runtime:2.6.1")
    composeAarsForPreview("androidx.lifecycle:lifecycle-common:2.6.1")
    composeAarsForPreview("androidx.lifecycle:lifecycle-viewmodel:2.6.1")
    composeAarsForPreview("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.6.1")
    composeAarsForPreview("androidx.savedstate:savedstate:1.2.1")
    composeAarsForPreview("androidx.core:core:1.12.0")
    composeAarsForPreview("androidx.core:core-ktx:1.12.0")
    composeAarsForPreview("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.7.3")
    composeAarsForPreview("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}

val downloadKotlinJars by tasks.registering(Copy::class) {
    from(kotlinJarsForPreview)
    into(layout.buildDirectory.dir("compose-jars"))

    rename { originalName ->
        when {
            originalName.startsWith("kotlin-compiler-embeddable") -> "kotlin-compiler.jar"
            originalName.startsWith("kotlin-stdlib") && !originalName.contains("common") -> "kotlin-stdlib.jar"
            originalName.startsWith("kotlin-reflect") -> "kotlin-reflect.jar"
            originalName.startsWith("kotlin-script-runtime") -> "kotlin-script-runtime.jar"
            originalName.startsWith("trove4j") -> "trove4j.jar"
            originalName.startsWith("annotations-") -> "jetbrains-annotations.jar"
            originalName.startsWith("compiler-") -> "compose-compiler-plugin.jar"
            originalName.startsWith("annotation-") -> "annotation.jar"
            else -> originalName
        }
    }
}

val extractComposeClasses by tasks.registering {
    dependsOn(downloadKotlinJars)

    val outputDir = layout.buildDirectory.dir("compose-jars")

    doLast {
        val outDir = outputDir.get().asFile
        outDir.mkdirs()

        composeAarsForPreview.files.forEach { file ->
            when {
                file.name.endsWith(".aar") -> {
                    ZipFile(file).use { aar ->
                        val classesEntry = aar.getEntry("classes.jar")
                        if (classesEntry != null) {
                            val targetName = file.nameWithoutExtension + ".jar"
                            val targetFile = File(outDir, targetName)
                            aar.getInputStream(classesEntry).use { input ->
                                targetFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            println("Extracted classes.jar from ${file.name} -> $targetName")
                        }
                    }
                }
                file.name.endsWith(".jar") -> {
                    val targetFile = File(outDir, file.name)
                    file.copyTo(targetFile, overwrite = true)
                    println("Copied JAR: ${file.name}")
                }
            }
        }
    }
}

val packageComposeJars by tasks.registering(Zip::class) {
    dependsOn(extractComposeClasses)

    from(layout.buildDirectory.dir("compose-jars"))
    archiveFileName.set("compose-jars.zip")
    destinationDirectory.set(file("src/main/assets/compose"))

    doFirst {
        file("src/main/assets/compose").mkdirs()
    }
}

tasks.named("preBuild") {
    dependsOn(packageComposeJars)
}

android {
    namespace = "${BuildConfig.PACKAGE_NAME}.compose.preview"

    buildFeatures {
        compose = true
        viewBinding = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.activity)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.common.kotlin.coroutines.android)

    implementation(projects.common)
    implementation(projects.editor)
    implementation(projects.editorApi)
    implementation(projects.resources)
    implementation(projects.logger)
    implementation(projects.subprojects.projects)
}
