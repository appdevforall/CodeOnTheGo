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

@file:Suppress("UnstableApiUsage")

import com.itsaky.androidide.build.config.BuildConfig
import com.itsaky.androidide.build.config.FDroidConfig
import com.itsaky.androidide.build.config.publishingVersion
import com.itsaky.androidide.plugins.AndroidIDEPlugin
import com.itsaky.androidide.plugins.conf.configureAndroidModule
import com.itsaky.androidide.plugins.conf.configureJavaModule
import com.itsaky.androidide.plugins.conf.configureMavenPublish
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("build-logic.root-project")
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.gradle.publish) apply false
}

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
        classpath(libs.kotlin.serialization.plugin)
        classpath(libs.nav.safe.args.gradle.plugin)
    }
}

subprojects {
    // Always load the F-Droid config
    FDroidConfig.load(project)

    afterEvaluate {
        apply { plugin(AndroidIDEPlugin::class.java) }
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
    project.group = BuildConfig.packageName
    project.version = rootProject.version

    plugins.withId("com.android.application") {
        configureAndroidModule(libs.androidx.libDesugaring)
    }

    plugins.withId("com.android.library") {
        configureAndroidModule(libs.androidx.libDesugaring)
    }

    plugins.withId("java-library") {
        configureJavaModule()
    }

    plugins.withId("com.vanniktech.maven.publish.base") {
        configureMavenPublish()
    }

    plugins.withId("com.gradle.plugin-publish") {
        configure<GradlePluginDevelopmentExtension> {
            version = project.publishingVersion
        }
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.fromTarget(BuildConfig.javaVersion.majorVersion))
    }
}

tasks.register<Delete>("clean") { delete(rootProject.layout.buildDirectory) }
