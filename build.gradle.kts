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
import org.gradle.api.logging.Logger
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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

		// Backstop: kill any individual Test task that runs longer than 10 minutes.
		// Prevents a single hung test JVM (e.g. the Tooling API child) from burning
		// the entire CI job budget.
		timeout.set(Duration.ofMinutes(10))

		// JPMS opens required by the unit-test stack on JDK 17+:
		//   - jdk.unsupported/sun.misc: HiddenApiBypass.<clinit> reflectively
		//     resolves sun.misc.Unsafe; without this the IDEApplication
		//     static initializer fails and poisons every Robolectric test.
		//   - java.base/java.lang(.reflect): Mockito's field injector calls
		//     setAccessible on java.lang.Class fields.
		//   - java.base/java.io, java.util: needed by Robolectric/Gradle worker
		//     reflection in the same test JVM.
		jvmArgs(
			"--add-opens=java.base/java.lang=ALL-UNNAMED",
			"--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
			"--add-opens=java.base/java.io=ALL-UNNAMED",
			"--add-opens=java.base/java.util=ALL-UNNAMED",
			"--add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED",
		)

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
			// Corpus fixtures mirror upstream apps' own formatting; reformatting them
			// breaks the mined-edit patches that must apply onto these exact bytes.
			"quick-build/corpus/**/*",
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
		// compose-rules ruleset validates @Composable naming/conventions so we can enable
		// PascalCase Composables (see ktlint_function_naming_ignore_when_annotated_with in .editorconfig)
		val composeRulesKtlint =
			libs.compose.rules.ktlint
				.get()
		ktlint().customRuleSets(listOf("${composeRulesKtlint.module}:${composeRulesKtlint.version}"))
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
		val binaries =
			subprojects.flatMap { subproj ->
				val dirs =
					listOf(
						subproj.layout.buildDirectory
							.dir("classes/java/main")
							.get()
							.asFile,
						subproj.layout.buildDirectory
							.dir("classes/kotlin/main")
							.get()
							.asFile,
						subproj.layout.buildDirectory
							.dir("intermediates/javac/v8Debug/classes")
							.get()
							.asFile,
						subproj.layout.buildDirectory
							.dir("tmp/kotlin-classes/v8Debug")
							.get()
							.asFile,
					)

				// include directories that actually exist
				dirs.filter { it.exists() }.map { it.absolutePath }
			}

		property("sonar.java.binaries", binaries.joinToString(","))

		property("sonar.c.file.suffixes", "-")
		property("sonar.cpp.file.suffixes", "-")
		property("sonar.objc.file.suffixes", "-")

		property(
			"sonar.coverage.jacoco.xmlReportPaths",
			project.layout.buildDirectory
				.dir("reports/jacoco/jacocoAggregateReport/jacocoAggregateReport.xml")
				.get()
				.asFile.absolutePath,
		)

		property("sonar.host.url", "https://sonarcloud.io")
		property("sonar.projectKey", "appdevforall_CodeOnTheGo")
		property("sonar.organization", "app-dev-for-all")
		property("sonar.androidVariant", "v8Debug")
		property("sonar.token", System.getenv("SONAR_TOKEN"))

		// The Sonar Android sensor auto-scans every Android module for a
		// lint XML at the standard AGP path and emits "Unable to import"
		// for each module where lint never ran (~57 warnings, because the
		// analyze workflow uses `-x lint`).  Pin the property to a single
		// empty-but-valid report so the sensor finds something and the
		// auto-scan never runs.  See :generateEmptySonarLintReport below.
		property(
			"sonar.androidLint.reportPaths",
			project.layout.buildDirectory
				.file("reports/sonar/empty-lint-report.xml")
				.get()
				.asFile.absolutePath,
		)
	}
}

val generateEmptySonarLintReport by tasks.registering {
	val output = project.layout.buildDirectory.file("reports/sonar/empty-lint-report.xml")
	outputs.file(output)
	doLast {
		val file = output.get().asFile
		file.parentFile.mkdirs()
		file.writeText(
			"""<?xml version="1.0" encoding="UTF-8"?>
			|<issues format="6" by="empty-placeholder"></issues>
			|
			""".trimMargin(),
		)
	}
}

tasks.named("sonarqube") {
	dependsOn("jacocoAggregateReport", generateEmptySonarLintReport)
}

tasks.register<JacocoReport>("jacocoAggregateReport") {
	val excludedProjects = emptySet<String>()

	// Depend only on testV8DebugUnitTest tasks in subprojects
	dependsOn(
		subprojects
			.filterNot { it.name in excludedProjects }
			.mapNotNull { it.tasks.findByName("testV8DebugUnitTest") },
	)

	reports {
		xml.required.set(true)
		html.required.set(true)
	}

	val fileFilter =
		listOf(
			"**/R.class",
			"**/R$*.class",
			"**/BuildConfig.*",
			"**/Manifest*.*",
			"**/*Test*.*",
		)

	// Collect kotlin and java class directories for v8Debug and v8DebugUnitTest variant
	val classDirs =
		subprojects
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
					},
				)
			}

	// Collect source directories
	val sourceDirs =
		subprojects
			.filterNot { it.name in excludedProjects }
			.map { it.file("src/main/java") }

	// Collect execution data (.exec files)
	val execFiles =
		subprojects
			.filterNot { it.name in excludedProjects }
			.map { subproj ->
				subproj.layout.buildDirectory.file(
					"outputs/unit_test_code_coverage/v8DebugUnitTest/testV8DebugUnitTest.exec",
				)
			}

	classDirectories.setFrom(classDirs)
	sourceDirectories.setFrom(sourceDirs)
	executionData.setFrom(execFiles)
}

val mavenCacheDirProvider = providers.gradleProperty("mavenCacheDir").orElse("build/maven-cache")
val localMavenRepoDirProvider = providers.gradleProperty("localMavenRepoDir").orElse("build/localMavenRepository")
val mavenRepoDirProvider = providers.gradleProperty("mavenRepoDir").orElse(localMavenRepoDirProvider)
val zeroMavenRepoDirProvider =
	providers
		.gradleProperty("zeroMavenRepoDir")
		.orElse(
			mavenRepoDirProvider.map { repoDir ->
				val repoPath = file(repoDir).toPath()
				val fileName = repoPath.fileName.toString()
				repoPath.resolveSibling("$fileName-zero").toString()
			},
		)

tasks.register("cacheToLocalMavenRepo") {
	group = "cicd"
	description = "Converts an exported Gradle module cache into a local Maven repository layout."

	val source = mavenCacheDirProvider.map { file(it) }
	val destination = localMavenRepoDirProvider.map { file(it) }

	inputs.dir(source)
	outputs.dir(destination)

	doLast {
		convertCacheToLocalMavenRepo(source.get().toPath(), destination.get().toPath(), logger)
	}
}

tasks.register("zeroCompressMavenRepo") {
	group = "cicd"
	description = "Copies a Maven repository and rewrites all JAR/AAR archives with ZIP zero compression."

	val source = mavenRepoDirProvider.map { file(it) }
	val destination = zeroMavenRepoDirProvider.map { file(it) }
	val validateArchives =
		providers
			.gradleProperty("zeroMavenRepoValidate")
			.map(String::toBoolean)
			.orElse(true)

	inputs.dir(source)
	inputs.property("validateArchives", validateArchives)
	outputs.dir(destination)

	doLast {
		zeroCompressMavenRepo(
			source = source.get().toPath(),
			destination = destination.get().toPath(),
			validateArchives = validateArchives.get(),
			logger = logger,
		)
	}
}

tasks.register("zeroCompressLocalMavenRepo") {
	group = "cicd"
	description = "Converts an exported Gradle cache to a local Maven repository, then zero-compresses all JAR/AAR archives."
	dependsOn("cacheToLocalMavenRepo", "zeroCompressMavenRepo")
}

tasks.named("zeroCompressMavenRepo") {
	mustRunAfter("cacheToLocalMavenRepo")
}

fun Path.resolveParts(parts: Iterable<String>): Path = parts.fold(this) { path, part -> path.resolve(part) }

fun Path.relativeTo(base: Path): Path = base.relativize(this)

fun Path.normalizedAbsolute(): Path = toAbsolutePath().normalize()

fun convertCacheToLocalMavenRepo(
	source: Path,
	destination: Path,
	logger: Logger,
) {
	val allowedExtensions = setOf("aar", "jar", "module", "pom")
	val normalizedSource = source.normalizedAbsolute()
	val normalizedDestination = destination.normalizedAbsolute()

	require(Files.isDirectory(normalizedSource)) {
		"Maven cache directory does not exist or is not a directory: $normalizedSource"
	}
	require(!normalizedDestination.startsWith(normalizedSource)) {
		"Local Maven output directory must not be inside the input cache: $normalizedDestination"
	}

	Files.createDirectories(normalizedDestination)

	normalizedSource
		.toFile()
		.walkTopDown()
		.filter { it.isFile }
		.filter { it.extension.lowercase() in allowedExtensions }
		.forEach { file ->
			val filePath = file.toPath()
			val relativeParent =
				filePath.parent
					?.let { normalizedSource.relativize(it).map(Path::toString).toList() }
					.orEmpty()

			val targetParts =
				if (relativeParent.firstOrNull()?.contains(".") == true) {
					relativeParent.first().split(".") + relativeParent.drop(1).dropLast(1)
				} else {
					relativeParent.dropLast(1)
				}

			val targetParent = normalizedDestination.resolveParts(targetParts)
			val targetFile = targetParent.resolve(file.name)

			Files.createDirectories(targetParent)
			Files.copy(filePath, targetFile, StandardCopyOption.REPLACE_EXISTING)
			logger.lifecycle("Copied ${filePath.relativeTo(normalizedSource)} -> ${targetFile.relativeTo(normalizedDestination)}")
		}
}

fun zeroCompressMavenRepo(
	source: Path,
	destination: Path,
	validateArchives: Boolean,
	logger: Logger,
) {
	val normalizedSource = source.normalizedAbsolute()
	val normalizedDestination = destination.normalizedAbsolute()

	require(Files.isDirectory(normalizedSource)) {
		"Maven repository directory does not exist or is not a directory: $normalizedSource"
	}
	require(!normalizedDestination.startsWith(normalizedSource)) {
		"Zero-compressed output directory must not be inside the input repository: $normalizedDestination"
	}

	Files.createDirectories(normalizedDestination)

	normalizedSource
		.toFile()
		.walkTopDown()
		.filter { it.isFile }
		.forEach { file ->
			val sourceFile = file.toPath()
			val targetFile = normalizedDestination.resolve(normalizedSource.relativize(sourceFile))

			Files.createDirectories(targetFile.parent)

			when (file.extension.lowercase()) {
				"aar", "jar" -> {
					zeroCompressArchive(sourceFile, targetFile)
					if (validateArchives) {
						validateZeroCompressedArchive(sourceFile, targetFile)
					}
					logger.lifecycle("Zero-compressed ${sourceFile.relativeTo(normalizedSource)}")
				}
				else -> Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING)
			}
		}
}

fun zeroCompressArchive(
	source: Path,
	destination: Path,
) {
	ZipInputStream(Files.newInputStream(source).buffered()).use { input ->
		ZipOutputStream(Files.newOutputStream(destination).buffered()).use { output ->
			while (true) {
				val entry = input.nextEntry ?: break
				val bytes = if (entry.isDirectory) ByteArray(0) else input.readBytes()
				val crc = CRC32().apply { update(bytes) }.value

				val outputEntry =
					ZipEntry(entry.name).apply {
						comment = entry.comment
						extra = entry.extra
						method = ZipEntry.STORED
						size = bytes.size.toLong()
						compressedSize = bytes.size.toLong()
						this.crc = crc
						if (entry.time >= 0) {
							time = entry.time
						}
					}

				output.putNextEntry(outputEntry)
				if (!entry.isDirectory) {
					output.write(bytes)
				}
				output.closeEntry()
				input.closeEntry()
			}
		}
	}
}

fun validateZeroCompressedArchive(
	source: Path,
	destination: Path,
) {
	ZipFile(source.toFile()).use { sourceZip ->
		ZipFile(destination.toFile()).use { destinationZip ->
			val sourceEntries =
				sourceZip
					.entries()
					.asSequence()
					.map { it.name }
					.toSet()
			val destinationEntries = destinationZip.entries().asSequence().toList()
			val destinationEntryNames = destinationEntries.map { it.name }.toSet()

			require(sourceEntries == destinationEntryNames) {
				"Entry mismatch after zero-compressing $source"
			}

			val compressedEntry =
				destinationEntries.firstOrNull { !it.isDirectory && it.method != ZipEntry.STORED }

			require(compressedEntry == null) {
				"Archive entry was not zero-compressed in $destination: ${compressedEntry?.name}"
			}
		}
	}
}
