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

import com.diffplug.spotless.FormatterFunc
import com.diffplug.spotless.LineEnding
import com.diffplug.spotless.extra.wtp.EclipseWtpFormatterStep
import com.itsaky.androidide.build.config.BuildConfig
import com.itsaky.androidide.build.config.FDroidConfig
import com.itsaky.androidide.build.config.publishingVersion
import com.itsaky.androidide.plugins.AndroidIDEPlugin
import com.itsaky.androidide.plugins.conf.configureAndroidModule
import com.itsaky.androidide.plugins.conf.configureJavaModule
import com.itsaky.androidide.plugins.conf.configureMavenPublish
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.Serializable

plugins {
    id("build-logic.root-project")
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.gradle.publish) apply false
    alias(libs.plugins.spotless)
}

buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
        classpath(libs.nav.safe.args.gradle.plugin)
    }
}

subprojects {
    // Always load the F-Droid config
    FDroidConfig.load(project)

    afterEvaluate {
        apply {
            plugin(AndroidIDEPlugin::class.java)
        }
    }
}

spotless {
    // Common directories to exclude
    // These mainly contain module that are external and huge, but are built from source
    val commonTargetExcludes =
        arrayOf(
            "composite-builds/build-deps/java-compiler/**/*",
            "composite-builds/build-deps/jaxp/**/*",
            "composite-builds/build-deps/jdk-compiler/**/*",
            "composite-builds/build-deps/jdk-jdeps/**/*",
            "composite-builds/build-deps/jdt/**/*",
            "composite-builds/build-login/properties-parser/**/*",
            "eventbus/**/*",
            "subprojects/xml-dom/**/*",
            "termux/**/*",
        )

    // ALWAYS use line feeds (LF -- '\n')
    lineEndings = LineEnding.UNIX

    java {
        eclipse()
            .configFile("spotless.eclipse-java.xml")
            // Sort member variables in the following order
            //   SF,SI,SM,F,I,C,M,T = Static Fields, Static Initializers, Static Methods, Fields, Initializers, Constructors, Methods, (Nested) Types
            .sortMembersEnabled(true)
            .sortMembersOrder("SF,SI,SM,F,I,C,M,T")
            // Disable field sorting
            .sortMembersDoNotSortFields(true)
            // Sort members based on their visibility in the following order
            //   B,R,D,V = Public, Protected, Package, Private
            .sortMembersVisibilityOrderEnabled(true)
            .sortMembersVisibilityOrder("B,R,D,V")

        target("**/src/*/java/**/*.java")
        targetExclude(*commonTargetExcludes)

        // enable import ordering
        importOrder()

        removeUnusedImports()
        removeWildcardImports()

        // custom rule to fix lambda formatting
        custom(
            "Lambda fix",
            object : Serializable, FormatterFunc {
                override fun apply(input: String): String =
                    input
                        .replace("} )", "})")
                        .replace("} ,", "},")
            },
        )
    }

    kotlin {
        ktlint()

        target("**/src/*/java/**/*.kt")
        target("**/src/*/kotlin/**/*.kt")
        targetExclude(*commonTargetExcludes)
    }

    kotlinGradle {
        ktlint()
        target("**/*.gradle.kts")
        targetExclude(*commonTargetExcludes)
    }

    format("xml") {
        eclipseWtp(EclipseWtpFormatterStep.XML)
            .configFile("spotless.eclipse-xml.prefs")
        endWithNewline()
        trimTrailingWhitespace()

        target("**/src/*/res/**/*.xml")
        targetExclude(*commonTargetExcludes)
    }

    format("misc") {
        target("**/.gitignore", "**/.gradle")
        targetExclude(*commonTargetExcludes)
    }
}

allprojects {
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

tasks.named<Delete>("clean") {
    doLast {
        delete(rootProject.layout.buildDirectory)
    }
}
