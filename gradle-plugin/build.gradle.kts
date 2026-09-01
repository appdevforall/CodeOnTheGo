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

import com.itsaky.androidide.build.config.AGP_VERSION_MINIMUM
import com.itsaky.androidide.build.config.BuildConfig
import com.itsaky.androidide.build.config.ProjectConfig
import org.gradle.api.file.SourceDirectorySet

plugins {
	id("org.jetbrains.kotlin.jvm")
	id("java-gradle-plugin")
}

description = "Gradle Plugin for projects that are built with AndroidIDE"

// The functional tests run a real Gradle build against this repo's own plugins, so those
// have to be staged into build-local maven repos first, and their locations handed to the
// harness through repos.txt. Wired here rather than in build-logic because a
// projectsEvaluated sweep silently misses projects under configure-on-demand.
val mavenLocalStagingProjects = listOf(":logsender", ":logger", ":build-info")

tasks.named<Test>("test") {
	useJUnitPlatform()

	val stagedRepos =
		mavenLocalStagingProjects.map { path ->
			dependsOn("$path:publishAllPublicationsToBuildMavenLocalRepository")
			project(path)
				.layout.buildDirectory
				.dir("maven-local")
				.get()
				.asFile.absolutePath
		}
	val reposFile =
		layout.buildDirectory
			.file("maven-local/repos.txt")
			.get()
			.asFile

	doFirst {
		reposFile.parentFile.mkdirs()
		reposFile.writeText(stagedRepos.joinToString(separator = File.pathSeparator))
	}
}

configurations {
	val androidBuildTool = create("androidBuildTool")

	getByName("compileOnly") {
		extendsFrom(androidBuildTool)
	}
	getByName("testImplementation") {
		extendsFrom(androidBuildTool)
	}
	findByName("integrationTestImplementation")?.run {
		extendsFrom(androidBuildTool)
	}
}

dependencies {
	implementation(libs.composite.constants)

	implementation(projects.gradlePluginConfig)
	implementation(projects.buildInfo)

	// Quick Build (ADFA-4128) needs the ScopedArtifacts API (AGP 7.4+) and the D8 API
	// shipped inside AGP's builder artifact, so this module compiles against the repo's
	// AGP instead of AGP_VERSION_MINIMUM. Projects on older AGPs are unaffected at
	// runtime: QuickBuildPlugin's classes load only when quick build is enabled, and the
	// other plugins stick to APIs that exist since the minimum supported version - a
	// claim the minAgpCheck guard below keeps honest by recompiling them against it.
	add("androidBuildTool", libs.android.gradle.plugin)

	testImplementation(gradleTestKit())
	testImplementation(libs.tests.junit.jupiter)
	testImplementation(libs.tests.google.truth)
	testImplementation(projects.shared)

	testRuntimeOnly(libs.tests.junit.platformLauncher)
}

// Min-AGP compatibility guard (restored after review). The main compile moved to the repo's
// AGP for Quick Build (above), which deleted the old red light: an innocent AGP-8-only API
// in LogSenderPlugin or AndroidIDEGradlePlugin would compile green and then fail every user
// project on an older AGP at configuration time, with Quick Build off. This source set
// recompiles the non-Quick-Build sources against AGP_VERSION_MINIMUM so that mistake goes
// red in `check`. The Quick Build sources are excluded on purpose: they genuinely need the
// newer AGP and only load when quick build is enabled (AndroidIDEGradlePlugin applies
// QuickBuildPlugin by name, not by class literal, to keep this compile honest).
val minAgpCheck: SourceSet =
	sourceSets.create("minAgpCheck") {
		java.setSrcDirs(emptyList<String>())
		resources.setSrcDirs(emptyList<String>())
	}
(minAgpCheck.extensions.getByName("kotlin") as SourceDirectorySet).apply {
	setSrcDirs(listOf("src/main/java"))
	exclude("**/QuickBuildPlugin.kt", "**/quickbuild/**")
}

dependencies {
	"minAgpCheckCompileOnly"(gradleApi())
	"minAgpCheckCompileOnly"("com.android.tools.build:gradle:$AGP_VERSION_MINIMUM")
	"minAgpCheckImplementation"(libs.composite.constants)
	"minAgpCheckImplementation"(projects.gradlePluginConfig)
	"minAgpCheckImplementation"(projects.buildInfo)
}

tasks.named("check") {
	dependsOn(tasks.named("minAgpCheckClasses"))
}

gradlePlugin {
	website.set(ProjectConfig.REPO_URL)
	vcsUrl.set(ProjectConfig.REPO_URL)

	plugins {
		create("initScriptPlugin") {
			id = "${BuildConfig.PACKAGE_NAME}.init"
			implementationClass = "${BuildConfig.PACKAGE_NAME}.gradle.AndroidIDEInitScriptPlugin"
			displayName = "AndroidIDE Init Script Gradle Plugin"
			description = "Init script Gradle plugin for projects that are built with AndroidIDE"
			tags.set(setOf("androidide", "init"))
		}

		create("gradlePlugin") {
			id = BuildConfig.PACKAGE_NAME
			implementationClass = "${BuildConfig.PACKAGE_NAME}.gradle.AndroidIDEGradlePlugin"
			displayName = "AndroidIDE Gradle Plugin"
			description = "Gradle plugin for projects that are built with AndroidIDE"
			tags.set(setOf("androidide", "gradle"))
		}

		create("logsenderPlugin") {
			id = "${BuildConfig.PACKAGE_NAME}.logsender"
			implementationClass = "${BuildConfig.PACKAGE_NAME}.gradle.LogSenderPlugin"
			displayName = "AndroidIDE LogSender Gradle Plugin"
			description =
				"Gradle plugin for applying LogSender-specific configuration to projects that are built with AndroidIDE"
			tags.set(setOf("androidide", "logsender"))
		}
	}
}

tasks.named<Jar>("jar") {
	archiveBaseName.set("cogo-plugin")
	archiveClassifier.set("") // Removes the default "all" classifier
	archiveVersion.set("")
}

// Coverage REPORTING only - no verification gate is wired here. This JVM
// module keeps the default build/jacoco/test.exec location; the report just
// needs xml enabled (for tooling to read the percentages) and the explicit
// test dependency so `:gradle-plugin:jacocoTestReport` is runnable on its own.
// The percentages under-count: the TestKit-driven tests exercise the plugin in
// a separate Gradle JVM this JaCoCo run does not instrument. Test failures do
// not block the report: the root build sets ignoreFailures on every Test task.
tasks.named<JacocoReport>("jacocoTestReport") {
	dependsOn(tasks.named("test"))
	reports {
		xml.required.set(true)
		html.required.set(true)
	}
}
