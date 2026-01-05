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
	alias(libs.plugins.rikka.autoresconfig) apply false
	alias(libs.plugins.rikka.materialthemebuilder) apply false
	alias(libs.plugins.rikka.refine) apply false
	alias(libs.plugins.google.protobuf) apply false
	alias(libs.plugins.spotless)
    alias(libs.plugins.sonarqube)
    id("jacoco")
}

jacoco {
    toolVersion = "0.8.11"
}

buildscript {
	dependencies {
		classpath(libs.kotlin.gradle.plugin)
		classpath(libs.nav.safe.args.gradle.plugin)
		classpath(libs.kotlin.serialization.plugin)
	}
}

subprojects {
    plugins.apply("jacoco")

    extensions.configure<JacocoPluginExtension> {
        toolVersion = "0.8.11"
    }

    // Always load the F-Droid config
	FDroidConfig.load(project)

    tasks.withType<Test> {
        // Continue even if tests fail, so coverage data is written
        ignoreFailures = true

        // Attach jacoco agent
        extensions.configure<JacocoTaskExtension> {
            isIncludeNoLocationClasses = true
            excludes = listOf("jdk.internal.*")
        }
    }

    afterEvaluate {
		apply {
			plugin(AndroidIDEPlugin::class.java)
		}
	}
}

spotless {
	ratchetFrom = "origin/stage"

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
			"LayoutEditor/**/*",
			"subprojects/aaptcompiler/src/*/java/com/android/**/*",
			"subprojects/builder-model-impl/src/*/java/com/android/**/*",
			"subprojects/flashbar/**/*",
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
			// some fields reference other fields of the same class, which can cause compilation
			// errors if re-ordered
			.sortMembersDoNotSortFields(true)
			// Sort members based on their visibility in the following order
			//   B,R,D,V = Public, Protected, Package, Private
			.sortMembersVisibilityOrderEnabled(true)
			.sortMembersVisibilityOrder("B,R,D,V")

		// use tabs
		leadingSpacesToTabs()
		trimTrailingWhitespace()
		endWithNewline()

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

		target("**/src/*/java/**/*.java")
		targetExclude(*commonTargetExcludes)
	}

	kotlin {
		ktlint()
		leadingSpacesToTabs()
		trimTrailingWhitespace()
		endWithNewline()

		target(
			"**/src/*/java/**/*.kt",
			"**/src/*/kotlin/**/*.kt",
		)
		targetExclude(*commonTargetExcludes)

		suppressLintsFor {
			// suppress the 'file name <some-file> should conform PascalCase' errors
			step = "ktlint"
			shortCode = "standard:filename"
		}
	}

	kotlinGradle {
		ktlint()
		leadingSpacesToTabs()
		trimTrailingWhitespace()
		endWithNewline()

		target("**/*.gradle.kts")
		targetExclude(*commonTargetExcludes)
	}

	format("xml") {
		eclipseWtp(EclipseWtpFormatterStep.XML)
			.configFile("spotless.eclipse-xml.prefs")

		leadingSpacesToTabs()
		trimTrailingWhitespace()
		endWithNewline()

		target("**/src/*/res/**/*.xml")
		targetExclude(*commonTargetExcludes)

		// Formatting strings.xml with Eclipse WTP causes the strings to be
		// split into multiple lines, which is not what we want.
		// Exclude strings.xml from this rule.
		targetExclude("**/src/*/res/values*/strings.xml")
	}

	format("misc") {
		leadingSpacesToTabs()
		trimTrailingWhitespace()
		endWithNewline()

		target("**/.gitignore", "**/.gradle")
		targetExclude(*commonTargetExcludes)
	}

	shell {
		leadingSpacesToTabs()
		trimTrailingWhitespace()
		endWithNewline()

		target(
			".githooks/**/*",
			"scripts/**/*",
		)
        targetExclude("scripts/debug-keystore/adfa-keystore.jks")
	}
}

allprojects {
	project.group = BuildConfig.PACKAGE_NAME
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
		compilerOptions.jvmTarget.set(JvmTarget.fromTarget(BuildConfig.JAVA_VERSION.majorVersion))
	}
}

tasks.named<Delete>("clean") {
	doLast {
		delete(rootProject.layout.buildDirectory)
	}
}

sonar {
    properties {
        val binaries = subprojects.flatMap { subproj ->
            val dirs = listOf(
                subproj.layout.buildDirectory.dir("classes/java/main").get().asFile,
                subproj.layout.buildDirectory.dir("classes/kotlin/main").get().asFile,

                subproj.layout.buildDirectory.dir("intermediates/javac/v8Debug/classes").get().asFile,
                subproj.layout.buildDirectory.dir("tmp/kotlin-classes/v8Debug").get().asFile
            )

            // include directories that actually exist
            dirs.filter { it.exists() }.map { it.absolutePath }
        }

        property("sonar.java.binaries", binaries.joinToString(","))

        property("sonar.c.file.suffixes", "-")
        property("sonar.cpp.file.suffixes", "-")
        property("sonar.objc.file.suffixes", "-")

        property("sonar.coverage.jacoco.xmlReportPaths",
            project.layout.buildDirectory
                .dir("reports/jacoco/jacocoAggregateReport/jacocoAggregateReport.xml").get().asFile.absolutePath
        )


        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.projectKey", "appdevforall_CodeOnTheGo")
        property("sonar.organization", "app-dev-for-all")
        property("sonar.androidVariant", "v8Debug")
        property("sonar.token", System.getenv("SONAR_TOKEN"))
    }
}

tasks.named("sonarqube") {
    dependsOn("jacocoAggregateReport")
}

tasks.register<JacocoReport>("jacocoAggregateReport") {
    val excludedProjects = emptySet<String>()

    // Depend only on testV8DebugUnitTest tasks in subprojects
    dependsOn(
        subprojects
            .filterNot { it.name in excludedProjects }
            .mapNotNull { it.tasks.findByName("testV8DebugUnitTest") }
    )

    reports {
        xml.required.set(true)
        html.required.set(true)
    }

    val fileFilter = listOf(
        "**/R.class", "**/R$*.class", "**/BuildConfig.*",
        "**/Manifest*.*", "**/*Test*.*"
    )

    // Collect kotlin and java class directories for v8Debug and v8DebugUnitTest variant
    val classDirs = subprojects
        .filterNot { it.name in excludedProjects }
        .flatMap { subproj ->
            listOf(
                fileTree(subproj.layout.buildDirectory.dir("tmp/kotlin-classes/v8Debug")) {
                    exclude(fileFilter)
                },
                fileTree(subproj.layout.buildDirectory.dir("tmp/kotlin-classes/v8DebugUnitTest")) {
                    exclude(fileFilter)
                },
                fileTree(subproj.layout.buildDirectory.dir("classes/java/v8Debug")) {
                    exclude(fileFilter)
                },
                fileTree(subproj.layout.buildDirectory.dir("intermediates/javac/v8DebugUnitTest/classes")) {
                    exclude(fileFilter)
                }
            )
        }

    // Collect source directories
    val sourceDirs = subprojects
        .filterNot { it.name in excludedProjects }
        .map { it.file("src/main/java") }

    // Collect execution data (.exec files)
    val execFiles = subprojects
        .filterNot { it.name in excludedProjects }
        .map { subproj ->
            subproj.layout.buildDirectory.file(
                "outputs/unit_test_code_coverage/v8DebugUnitTest/testV8DebugUnitTest.exec"
            )
        }

    classDirectories.setFrom(classDirs)
    sourceDirectories.setFrom(sourceDirs)
    executionData.setFrom(execFiles)
}



