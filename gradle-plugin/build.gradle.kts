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

fun Test.stageMavenLocalRepos() {
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

	// Without these the staged repos are invisible to the input snapshot, so `test` reports
	// UP-TO-DATE after a republish and the TestKit builds that resolve through repos.txt
	// never run.
	inputs.files(stagedRepos).withPropertyName("stagedMavenLocalRepos")
	outputs.file(reposFile).withPropertyName("stagedMavenLocalReposFile")

	doFirst {
		reposFile.parentFile.mkdirs()
		reposFile.writeText(stagedRepos.joinToString(separator = File.pathSeparator))
	}
}

val sqliteReleaseApkTestClass = "com.itsaky.androidide.gradle.SqliteReleaseApkTest"

tasks.named<Test>("test") {
	useJUnitPlatform()
	stageMavenLocalRepos()
	// Run by sqliteReleaseApkTest below, which builds the AARs it needs
	filter { excludeTestsMatching(sqliteReleaseApkTestClass) }
}

// The release-APK guard (ADFA-7138): SqliteReleaseApkTest builds the minified release and the debug variant
// of a sample app that opted into the SQLite debugger, with the real debugger, rewrite runtime and requery
// AARs, and runs scripts/check-release-apk-no-sqlite-debugger.sh on both APKs. A task of its own because it
// needs the NDK-built debugger AAR, which the other tests do not; CI's build_apk job runs it on every push.
val requeryAar =
	configurations.create("sqliteReleaseApkTestRequery") {
		isCanBeConsumed = false
		isTransitive = false
	}

dependencies {
	add(requeryAar.name, variantOf(libs.tests.requery.sqliteAndroid) { artifactType("aar") })
}

// Under configure-on-demand the AAR projects are not configured yet when the task below reads their build
// directories, as in :app
val sqliteReleaseApkTestAarProjects =
	mapOf(
		"debugger" to ":subprojects:sqlite-debugger",
		"rewriteRuntime" to ":subprojects:sqlite-rewrite-runtime",
	)
sqliteReleaseApkTestAarProjects.values.forEach(::evaluationDependsOn)

tasks.register<Test>("sqliteReleaseApkTest") {
	description = "Builds an opted-in sample app's release APK and checks that it carries no SQLite debugger"
	group = "verification"
	val testSourceSet = sourceSets["test"]
	testClassesDirs = testSourceSet.output.classesDirs
	classpath = testSourceSet.runtimeClasspath
	useJUnitPlatform()
	stageMavenLocalRepos()
	filter { includeTestsMatching(sqliteReleaseApkTestClass) }

	// The tasks AndroidIDEAssetsPlugin bundles these AARs with, so this task and :app:assemble build them once
	for ((name, path) in sqliteReleaseApkTestAarProjects) {
		dependsOn("$path:assembleRelease")
		val aar =
			project(path)
				.layout.buildDirectory
				.file("outputs/aar/${path.substringAfterLast(':')}-release.aar")
				.get()
				.asFile
		inputs.file(aar).withPropertyName("${name}Aar")
		systemProperty("cogo.test.sqlite.${name}Aar", aar.absolutePath)
	}
	inputs.files(requeryAar).withPropertyName("requeryAar")
	inputs.file(rootProject.file("scripts/check-release-apk-no-sqlite-debugger.sh")).withPropertyName("checkScript")
	jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dcogo.test.sqlite.requeryAar=${requeryAar.singleFile.absolutePath}") })
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

	// The SQLite rewrite (ADFA-7138). AGP's runtime classpath carries ASM, but its API
	// artifacts expose only asm itself, so the rewrite compiles against the rest of it here.
	compileOnly(libs.ow2.asm)
	compileOnly(libs.ow2.asmCommons)
	compileOnly(libs.ow2.asmTree)
	compileOnly(libs.ow2.asmAnalysis)

	testImplementation(gradleTestKit())
	testImplementation(libs.tests.junit.jupiter)
	testImplementation(libs.tests.google.truth)
	testImplementation(projects.shared)
	testImplementation(libs.ow2.asm)
	testImplementation(libs.ow2.asmCommons)
	testImplementation(libs.ow2.asmTree)
	testImplementation(libs.ow2.asmAnalysis)

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
	"minAgpCheckCompileOnly"(libs.ow2.asm)
	"minAgpCheckCompileOnly"(libs.ow2.asmCommons)
	"minAgpCheckCompileOnly"(libs.ow2.asmTree)
	"minAgpCheckCompileOnly"(libs.ow2.asmAnalysis)
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

		create("sqliteDebuggerPlugin") {
			id = "${BuildConfig.PACKAGE_NAME}.sqlitedebugger"
			implementationClass = "${BuildConfig.PACKAGE_NAME}.gradle.SqliteDebuggerPlugin"
			displayName = "AndroidIDE SQLite Debugger Gradle Plugin"
			description =
				"Gradle plugin for adding the SQLite debugger to debuggable variants of projects that are built with AndroidIDE"
			tags.set(setOf("androidide", "sqlite"))
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
