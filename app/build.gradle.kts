@file:Suppress("UnstableApiUsage")

import ch.qos.logback.core.util.EnvUtil
import com.itsaky.androidide.build.config.BuildConfig
import com.itsaky.androidide.desugaring.ch.qos.logback.core.util.DesugarEnvUtil
import com.itsaky.androidide.desugaring.utils.JavaIOReplacements.applyJavaIOReplacements
import com.itsaky.androidide.plugins.AndroidIDEAssetsPlugin
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.reflect.jvm.javaMethod

val isWindows = System.getProperty("os.name").lowercase().contains("windows")

fun TaskContainer.registerD8Task(
	taskName: String,
	inputJar: File,
	outputDex: File,
): org.gradle.api.tasks.TaskProvider<Exec> {
	val androidSdkDir = android.sdkDirectory.absolutePath
	val buildToolsVersion = android.buildToolsVersion // Gets the version from your project
	val d8Executable =
		File(
			"$androidSdkDir/build-tools/$buildToolsVersion/" +
				if (isWindows) "d8.bat" else "d8",
		)

	if (!d8Executable.exists()) {
		throw FileNotFoundException("D8 executable not found at: ${d8Executable.absolutePath}")
	}

	return register<Exec>(taskName) {
		inputs.file(inputJar)
		outputs.file(outputDex)

		commandLine(
			d8Executable.absolutePath,
			"--release", // Enables optimizations
			"--output",
			outputDex.parent, // D8 outputs to a directory
			inputJar.absolutePath,
		)
	}
}

plugins {
	id("com.android.application")
	id("kotlin-android")
	id("kotlin-kapt")
	id("kotlin-parcelize")
	id("androidx.navigation.safeargs.kotlin")
	id("com.itsaky.androidide.desugaring")
	alias(libs.plugins.sentry)
	alias(libs.plugins.google.services)
	kotlin("plugin.serialization")
}

fun propOrEnv(name: String): String =
	project.findProperty(name) as String?
		?: System.getenv(name)
		?: ""

val props =
	Properties().apply {
		val file = rootProject.file("local.properties")
		if (file.exists()) load(file.inputStream())
	}

apply {
	plugin(AndroidIDEAssetsPlugin::class.java)
}

buildscript {
	dependencies {
		classpath(libs.logging.logback.core)
		classpath(libs.composite.desugaringCore)
		classpath(libs.org.json)
	}
}

android {
	namespace = BuildConfig.PACKAGE_NAME

	defaultConfig {
		applicationId = BuildConfig.PACKAGE_NAME
		vectorDrawables.useSupportLibrary = true
		testInstrumentationRunnerArguments["class"] = "com.itsaky.androidide.OrderedTestSuite"
	}

	signingConfigs {
		getByName("debug") {
			enableV2Signing = true
			enableV3Signing = true
		}
	}

	buildTypes {
		debug {
			signingConfig = signingConfigs.getByName("debug")
			manifestPlaceholders["sentryDsn"] =
				props.getProperty("sentryDsnDebug") ?: propOrEnv("SENTRY_DSN_DEBUG")
		}
		release {
			manifestPlaceholders["sentryDsn"] =
				props.getProperty("sentryDsnRelease") ?: propOrEnv("SENTRY_DSN_RELEASE")
		}
	}

	testOptions {
		unitTests {
			isIncludeAndroidResources = true
			all {
				// Skip TreeSitter native library loading in tests
				it.systemProperty("java.library.path", System.getProperty("java.library.path"))
				it.systemProperty("androidide.test.mode", "true")
			}
		}
	}

	androidResources {
		noCompress.add("tflite")
		generateLocaleConfig = true
	}

	sourceSets {
		getByName("androidTest") {
			manifest.srcFile("src/androidTest/AndroidManifest.xml")
		}
	}

	lint {
		abortOnError = false
		disable.addAll(arrayOf("VectorPath", "NestedWeights", "ContentDescription", "SmallSp"))
	}

	packaging {
		resources {
			excludes += "META-INF/DEPENDENCIES"
			excludes += "META-INF/gradle/incremental.annotation.processors"

			pickFirsts += "kotlin/internal/internal.kotlin_builtins"
			pickFirsts += "kotlin/reflect/reflect.kotlin_builtins"
			pickFirsts += "kotlin/kotlin.kotlin_builtins"
			pickFirsts += "kotlin/coroutines/coroutines.kotlin_builtins"
			pickFirsts += "kotlin/ranges/ranges.kotlin_builtins"
			pickFirsts += "kotlin/concurrent/atomics/atomics.kotlin_builtins"
			pickFirsts += "kotlin/collections/collections.kotlin_builtins"
			pickFirsts += "kotlin/annotation/annotation.kotlin_builtins"

			pickFirsts += "META-INF/FastDoubleParser-LICENSE"
			pickFirsts += "META-INF/thirdparty-LICENSE"
			pickFirsts += "META-INF/FastDoubleParser-NOTICE"
			pickFirsts += "META-INF/thirdparty-NOTICE"
		}

		jniLibs {
			useLegacyPackaging = false
		}
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
		isCoreLibraryDesugaringEnabled = true
	}
}

sentry {
      includeProguardMapping = false
}

kapt { arguments { arg("eventBusIndex", "${BuildConfig.PACKAGE_NAME}.events.AppEventsIndex") } }

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
			DesugarEnvUtil::logbackVersion.javaMethod!!,
		)
	}
}

configurations.matching { it.name.contains("AndroidTest") }.configureEach {
	exclude(group = "com.google.protobuf", module = "protobuf-lite")
}

configurations.configureEach {
	exclude(group = "org.jetbrains.kotlin", module = "kotlin-android-extensions-runtime")
}

dependencies {
	debugImplementation(libs.common.leakcanary)

	// Annotation processors
	kapt(libs.common.glide.ap)
	kapt(libs.google.auto.service)
	kapt(projects.annotationProcessors)
	kapt(libs.room.compiler)

	implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

	implementation(platform(libs.sora.bom))
	implementation(libs.common.editor)
	implementation(libs.common.utilcode)
	implementation(libs.common.glide)
	implementation(libs.common.jsoup)
	implementation(libs.common.kotlin.coroutines.android)
	implementation(libs.common.retrofit)
	implementation(libs.common.retrofit.gson)
	implementation(libs.common.charts)
	implementation(libs.common.hiddenApiBypass)
	implementation(libs.commons.compress)

	implementation(libs.google.auto.service.annotations)
	implementation(libs.google.gson)
	implementation(libs.google.guava)

	// Room
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
	implementation(libs.libsu.core)

	// Kotlin
	implementation(libs.androidx.core.ktx)
	implementation(libs.common.kotlin)

	// Dependencies in composite build
	implementation(libs.composite.appintro)
	implementation(libs.composite.desugaringCore)
	implementation(libs.composite.javapoet)
	implementation(libs.composite.treeview)

	// Local projects here
	implementation(projects.actions)
	implementation(projects.buildInfo)
	implementation(projects.common)
	implementation(projects.commonUi)
	implementation(projects.editor)
	implementation(projects.termux.termuxApp)
	implementation(projects.termux.termuxView)
	implementation(projects.termux.termuxEmulator)
	implementation(projects.termux.termuxShared)
	implementation(projects.eventbus)
	implementation(projects.eventbusAndroid)
	implementation(projects.eventbusEvents)
	implementation(projects.gradlePluginConfig)
	implementation(projects.subprojects.aaptcompiler)
	implementation(projects.subprojects.javacServices)
	implementation(projects.subprojects.kotlinAnalysisApi)
	implementation(projects.subprojects.shizukuApi)
	implementation(projects.subprojects.shizukuManager)
	implementation(projects.subprojects.shizukuProvider)
	implementation(projects.subprojects.shizukuServerShared)
	implementation(projects.subprojects.xmlUtils)
	implementation(projects.subprojects.projects)
	implementation(projects.subprojects.toolingApi)
	implementation(projects.logsender)
	implementation(projects.lsp.api)
	implementation(projects.lsp.java)
	implementation(projects.lsp.kotlin)
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
	implementation(projects.pluginApi)
	implementation(projects.pluginManager)

	implementation(projects.layouteditor)
	implementation(projects.idetooltips)
	implementation(projects.composePreview)
	implementation(projects.gitCore)

	// This is to build the tooling-api-impl project before the app is built
	// So we always copy the latest JAR file to assets
	compileOnly(projects.subprojects.toolingApiImpl)

	testImplementation(projects.testing.unit)
	testImplementation(libs.core.tests.anroidx.arch)
	androidTestImplementation(projects.common)
	androidTestImplementation(projects.testing.android) {
		exclude(group = "com.google.protobuf", module = "protobuf-lite")
	}
	androidTestImplementation(libs.tests.kaspresso)
	androidTestImplementation(libs.tests.junit.kts)
	androidTestImplementation(libs.tests.androidx.test.runner)
	// androidTestUtil(libs.tests.orchestrator)
	testImplementation(libs.tests.kotlinx.coroutines)

	// brotli4j
	implementation(libs.brotli4j)

	implementation(libs.common.markwon.core)
	implementation(libs.common.markwon.linkify)
	implementation(libs.commons.text.v1140)

	implementation(libs.kotlinx.serialization.json)
	// Koin for Dependency Injection
	implementation(libs.koin.android)
	implementation(libs.androidx.security.crypto)

	// Sentry Android SDK (core + replay for quality configuration)
	implementation(libs.sentry.core)
	implementation(libs.sentry.android.core)
	implementation(libs.sentry.android.replay)

	// Firebase Analytics
	implementation(platform(libs.firebase.bom))
	implementation(libs.firebase.analytics)

	// Lifecycle Process for app lifecycle tracking
	implementation(libs.androidx.lifecycle.process)
	implementation(libs.androidx.lifecycle.runtime.ktx)
	implementation(libs.google.genai)
	coreLibraryDesugaring(libs.desugar.jdk.libs.v215)

    // Pebble template engine
    implementation("io.pebbletemplates:pebble:4.1.1")

    // Jackson JSON parsing dependency
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
}

tasks.register("downloadDocDb") {
	doLast {
		val githubRepo = "appdevforall/OfflineDocumentationTools"
		val latestReleaseApiUrl = "https://api.github.com/repos/$githubRepo/releases/latest"

		project.logger.lifecycle("Fetching latest release metadata...")
		try {
			val jsonResponse = URI(latestReleaseApiUrl).toURL().readText()
			val jsonObject = JSONObject(jsonResponse)

			val assets = jsonObject.getJSONArray("assets")
			var assetUrl: String? = null
			var assetName: String? = null

			for (i in 0 until assets.length()) {
				val asset = assets.getJSONObject(i)
				val name = asset.getString("name")
				if (name.endsWith(".sqlite")) {
					assetUrl = asset.getString("browser_download_url")
					assetName = name
					break
				}
			}

			val dbName = "documentation.db"
			if (assetUrl != null && assetName != null) {
				val destinationPath =
					project.rootProject.projectDir
						.resolve("assets/$dbName")
						.toPath()

				project.logger.lifecycle("Downloading : $assetUrl as $destinationPath")

				URL(assetUrl).openStream().use { input ->
					Files.copy(input, destinationPath, StandardCopyOption.REPLACE_EXISTING)
					println("Download complete: $destinationPath")
				}
			} else {
				project.logger.lifecycle("No `.sqlite` asset found in the latest release.")
			}
		} catch (e: Exception) {
			project.logger.lifecycle("Failed to fetch documentation.db info: ${e.message}")
		}
	}
}


tasks.register("copyPluginApiJarToAssets") {
	dependsOn(":plugin-api:createPluginApiJar")
	val sourceFile = project(":plugin-api").layout.buildDirectory.file("libs/plugin-api-1.0.0.jar")
	val destFile = rootProject.layout.projectDirectory.file("assets/plugin-api.jar")
	inputs.file(sourceFile)
	outputs.file(destFile)
	doLast {
		sourceFile.get().asFile.copyTo(destFile.asFile, overwrite = true)
	}
}

tasks.register<Zip>("createPluginArtifactsZip") {
	dependsOn("copyPluginApiJarToAssets")
	dependsOn(gradle.includedBuild("plugin-builder").task(":jar"))

	from(rootProject.file("assets/plugin-api.jar"))
	from(rootProject.file("plugin-api/plugin-builder/build/libs/plugin-builder-1.0.0.jar")) {
		rename { "gradle-plugin.jar" }
	}

	archiveFileName.set("plugin-artifacts.zip")
	destinationDirectory.set(rootProject.file("assets"))
}

tasks.register("assembleV8Assets") {
	dependsOn("createPluginArtifactsZip")
	if (!isCiCd) {
		dependsOn("assetsDownloadDebug")
	}
}

tasks.register("assembleV7Assets") {
	dependsOn("createPluginArtifactsZip")
	if (!isCiCd) {
		dependsOn("assetsDownloadDebug")
	}
}

tasks.register("assembleAssets") {
	dependsOn("assembleV8Assets", "assembleV7Assets")
}

tasks.register("recompressApk") {
	doLast {
		val abi: String = extensions.extraProperties["abi"].toString()
		val buildName: String = extensions.extraProperties["buildName"].toString()
		val noCompressExtensions =
			if (extensions.extraProperties.has("noCompressExtensions")) {
				extensions.extraProperties["noCompressExtensions"] as? Set<String> ?: emptySet()
			} else {
				emptySet()
			}

		project.logger.lifecycle("Calling recompressApk abi:$abi buildName:$buildName")

		val start = System.nanoTime()
		recompressApk(abi, buildName, noCompressExtensions)
		val durationMs = "%.2f".format((System.nanoTime() - start) / 1_000_000.0)

		project.logger.lifecycle("recompressApk completed in ${durationMs}ms")
	}
}

val isCiCd = System.getenv("GITHUB_ACTIONS") == "true"

val noCompress =
	setOf(
		"so",
		"ogg",
		"mp3",
		"mp4",
		"zip",
		"jar",
		"ttf",
		"otf",
		"br",
		"tflite",
		"binarypb",
		"bincfg",
		"conv_model",
		"lstm_model",
	)

// Debug APKs DEFLATE jar assets (tooling-api-all.jar, cogo-plugin.jar) for
// ~75% size reduction (ADFA-4188). Release keeps jars STORED because
// tooling-api-all.jar ships as a brotli-encoded .jar.br.
val noCompressDebug = noCompress - "jar"

afterEvaluate {
	tasks.named("assembleV8Release").configure {
		finalizedBy("recompressApk")

		doLast {
			tasks.named("recompressApk").configure {
				extensions.extraProperties["abi"] = "v8"
				extensions.extraProperties["buildName"] = "release"
				extensions.extraProperties["noCompressExtensions"] = noCompress
			}
		}

		if (!isCiCd) {
			dependsOn("assetsDownloadRelease")
		}
	}

	tasks.named("assembleV7Release").configure {
		finalizedBy("recompressApk")

		doLast {
			tasks.named("recompressApk").configure {
				extensions.extraProperties["abi"] = "v7"
				extensions.extraProperties["buildName"] = "release"
				extensions.extraProperties["noCompressExtensions"] = noCompress
			}
		}

		if (!isCiCd) {
			dependsOn("assetsDownloadRelease")
		}
	}

	tasks.named("assembleV8Debug").configure {
		if (isCiCd) {
			finalizedBy("recompressApk")
		}

		doLast {
			if (isCiCd) {
				tasks.named("recompressApk").configure {
					extensions.extraProperties["abi"] = "v8"
					extensions.extraProperties["buildName"] = "debug"
					extensions.extraProperties["noCompressExtensions"] = noCompressDebug
				}
			}
		}

		if (!isCiCd) {
			dependsOn("assetsDownloadDebug")
		}
	}

	tasks.named("assembleV7Debug").configure {
		if (isCiCd) {
			finalizedBy("recompressApk")
		}

		doLast {
			if (isCiCd) {
				tasks.named("recompressApk").configure {
					extensions.extraProperties["abi"] = "v7"
					extensions.extraProperties["buildName"] = "debug"
					extensions.extraProperties["noCompressExtensions"] = noCompressDebug
				}
			}
		}

		if (!isCiCd) {
			dependsOn("assetsDownloadDebug")
		}
	}
}

fun recompressApk(
	abi: String,
	buildName: String,
	noCompressExtensions: Set<String>,
) {
	project.logger.lifecycle("Recompressing APK with exclusions: $noCompressExtensions")

	val apkDir: File =
		layout.buildDirectory
			.dir("outputs/apk/$abi/$buildName")
			.get()
			.asFile
	project.logger.lifecycle("Recompressing APK Dir: ${apkDir.path}")

	apkDir.walk().filter { it.extension == "apk" }.forEach { apkFile ->
		project.logger.lifecycle("Recompressing APK: ${apkFile.name}")
		val tempZipFile = File(apkFile.parentFile, "${apkFile.name}.tmp")
		recompressZip(apkFile, tempZipFile, noCompressExtensions)
		signApk(tempZipFile)
		if (apkFile.delete()) {
			tempZipFile.renameTo(apkFile)
		}
	}
}

fun recompressZip(
	inputZip: File,
	outputZip: File,
	noCompressExtensions: Set<String>,
) {
	ZipInputStream(BufferedInputStream(FileInputStream(inputZip))).use { zis ->
		ZipOutputStream(BufferedOutputStream(FileOutputStream(outputZip))).use { zos ->
			zos.setLevel(Deflater.BEST_COMPRESSION)

			var entry = zis.nextEntry
			while (entry != null) {
				if (!entry.isDirectory) {
					val extension = entry.name.substringAfterLast('.', "").lowercase()
					val newEntry = ZipEntry(entry.name)

					// Remove timestamps for deterministic output (-X)
					newEntry.time = 0L
					try {
						newEntry.creationTime = FileTime.fromMillis(0)
						newEntry.lastModifiedTime = FileTime.fromMillis(0)
						newEntry.lastAccessTime = FileTime.fromMillis(0)
					} catch (_: Throwable) {
					}

					// Force STORE method for no-compress extensions
					if (extension in noCompressExtensions) {
						val byteArray = zis.readBytes()
						newEntry.method = ZipEntry.STORED
						newEntry.size = byteArray.size.toLong()
						newEntry.crc = CRC32().apply { update(byteArray) }.value
						zos.putNextEntry(newEntry)
						zos.write(byteArray)
					} else {
						newEntry.method = ZipEntry.DEFLATED
						zos.putNextEntry(newEntry)
						zis.copyTo(zos)
					}

					zos.closeEntry()
				}
				entry = zis.nextEntry
			}
		}
	}
}

fun signApk(apkFile: File) {
	project.logger.lifecycle("Signing APK: ${apkFile.name}")

	val isWindows = System.getProperty("os.name").lowercase().contains("windows")
	var signerExec = "apksigner"
	if (isWindows) {
		signerExec = "apksigner.bat"
	}

	val signingConfig =
		android.signingConfigs.findByName("common")
			?: android.signingConfigs.getByName("debug")

	project.logger.lifecycle("Signing Config: ${signingConfig.name}")

	val keystorePath = signingConfig.storeFile?.absolutePath ?: error("Keystore not found!")
	val keystorePassword = signingConfig.storePassword ?: error("Keystore password missing!")
	val keyAlias = signingConfig.keyAlias ?: error("Key alias missing!")
	val keyPassword = signingConfig.keyPassword ?: error("Key password missing!")

	val androidSdkDir = android.sdkDirectory.absolutePath
	val apkSignerPath: File =
		File(androidSdkDir, "build-tools")
			.listFiles()
			?.filter { it.isDirectory && File(it, signerExec).exists() }
			?.maxByOrNull { it.name } // pick the highest version
			?.resolve(signerExec)
			?: error("Could not find apksigner in any build-tools directory")

	project.logger.lifecycle("APK Signer: ${apkSignerPath.absolutePath}")

	ant.withGroovyBuilder {
		"exec"(
			"executable" to apkSignerPath.absolutePath,
			"failonerror" to "true",
		) {
			"arg"("value" to "sign")
			"arg"("value" to "--v3-signing-enabled")
			"arg"("value" to "true")
			"arg"("value" to "--v2-signing-enabled")
			"arg"("value" to "true")
			"arg"("value" to "--ks")
			"arg"("value" to keystorePath)
			"arg"("value" to "--ks-key-alias")
			"arg"("value" to keyAlias)
			"arg"("value" to "--ks-pass")
			"arg"("value" to "pass:$keystorePassword")
			"arg"("value" to "--key-pass")
			"arg"("value" to "pass:$keyPassword")
			"arg"("value" to apkFile.absolutePath)
		}
	}
}

val scpServer: String = propOrEnv("SCP_HOST")

// git lfs avoidance
data class Asset(
	val localPath: String,
	val url: String,
	val remotePath: String,
	val variant: String,
)

val debugAssets =
	listOf(
		Asset(
			"assets/android-sdk-arm64-v8a.zip",
			"https://appdevforall.org/dev-assets/debug/android-sdk-arm64-v8a.zip",
			"android-sdk-arm64-v8a.zip",
			"debug",
		),
		Asset(
			"assets/android-sdk-armeabi-v7a.zip",
			"https://appdevforall.org/dev-assets/debug/android-sdk-armeabi-v7a.zip",
			"android-sdk-armeabi-v7a.zip",
			"debug",
		),
		Asset(
			"assets/bootstrap-arm64-v8a.zip",
			"https://appdevforall.org/dev-assets/debug/bootstrap-arm64-v8a.zip",
			"bootstrap-arm64-v8a.zip",
			"debug",
		),
		Asset(
			"assets/bootstrap-armeabi-v7a.zip",
			"https://appdevforall.org/dev-assets/debug/bootstrap-armeabi-v7a.zip",
			"bootstrap-armeabi-v7a.zip",
			"debug",
		),
		Asset(
			"assets/documentation.db",
			"https://appdevforall.org/dev-assets/debug/documentation.db",
			"documentation.db",
			"debug",
		),
		Asset(
			"assets/gradle-8.14.3-bin.zip",
			"https://appdevforall.org/dev-assets/debug/gradle-8.14.3-bin.zip",
			"gradle-8.14.3-bin.zip",
			"debug",
		),
		Asset(
			"assets/gradle-api-8.14.3.jar.zip",
			"https://appdevforall.org/dev-assets/debug/gradle-api-8.14.3.jar.zip",
			"gradle-api-8.14.3.jar.zip",
			"debug",
		),
		Asset(
			"assets/localMvnRepository.zip",
			"https://appdevforall.org/dev-assets/debug/localMvnRepository.zip",
			"localMvnRepository.zip",
			"debug",
		),
        Asset(
          "assets/core.cgt",
          "https://appdevforall.org/dev-assets/debug/core.cgt",
          "core.cgt",
          "debug",
        ),
	)

val releaseAssets =
	listOf(
		Asset(
			"assets/release/common/data/common/gradle-8.14.3-bin.zip.br",
			"https://appdevforall.org/dev-assets/release/gradle-8.14.3-bin.zip.br",
			"gradle-8.14.3-bin.zip.br",
			"release",
		),
		Asset(
			"assets/release/common/data/common/gradle-api-8.14.3.jar.br",
			"https://appdevforall.org/dev-assets/release/gradle-api-8.14.3.jar.br",
			"gradle-api-8.14.3.jar.br",
			"release",
		),
		Asset(
			"assets/release/common/data/common/localMvnRepository.zip.br",
			"https://appdevforall.org/dev-assets/release/localMvnRepository.zip.br",
			"localMvnRepository.zip.br",
			"release",
		),
		Asset(
			"assets/release/common/database/documentation.db.br",
			"https://appdevforall.org/dev-assets/release/documentation.db.br",
			"documentation.db.br",
			"release",
		),
		Asset(
			"assets/release/v7/data/common/android-sdk.zip.br",
			"https://appdevforall.org/dev-assets/release/v7/android-sdk.zip.br",
			"v7/android-sdk.zip.br",
			"release",
		),
		Asset(
			"assets/release/v7/data/common/bootstrap.zip.br",
			"https://appdevforall.org/dev-assets/release/v7/bootstrap.zip.br",
			"v7/bootstrap.zip.br",
			"release",
		),
		Asset(
			"assets/release/v8/data/common/android-sdk.zip.br",
			"https://appdevforall.org/dev-assets/release/v8/android-sdk.zip.br",
			"v8/android-sdk.zip.br",
			"release",
		),
		Asset(
			"assets/release/v8/data/common/bootstrap.zip.br",
			"https://appdevforall.org/dev-assets/release/v8/bootstrap.zip.br",
			"v8/bootstrap.zip.br",
			"release",
		),
        Asset(
          "assets/release/common/data/common/core.cgt.br",
          "https://appdevforall.org/dev-assets/release/core.cgt.br",
          "core.cgt.br",
          "release",
        ),
	)

fun assetsBatch(
	projectDir: File,
	project: Project,
	variant: String,
) {
	if (isCiCd) {
		val tmpDir = File(projectDir, ".tmp/assets")
		tmpDir.mkdirs()
		project.logger.lifecycle("Downloading $variant assets → ${tmpDir.absolutePath}")
		project.exec {
			commandLine(
				"scp",
				"-r",
				"$scpServer:public_html/dev-assets/$variant/",
				tmpDir.absolutePath,
			)
		}
		project.logger.lifecycle("SCP batch downloaded $variant assets → ${tmpDir.absolutePath}")
	}
}

fun stagedFileFor(
	asset: Asset,
	projectDir: File,
): File {
	val variantDir = File(projectDir, ".tmp/assets/${asset.variant}")
	return File(variantDir, asset.remotePath)
}

fun stagedChecksumFor(
	asset: Asset,
	projectDir: File,
): File {
	val variantDir = File(projectDir, ".tmp/assets/${asset.variant}")
	return File(variantDir, asset.remotePath + ".md5")
}

fun assetsFileDownload(
	asset: Asset,
	target: File,
) {
	if (isCiCd) {
		val stagedFile = stagedFileFor(asset, rootProject.projectDir)
		if (!stagedFile.exists()) {
			throw GradleException("Staged file not found: ${stagedFile.absolutePath}")
		}
		target.parentFile.mkdirs()
		stagedFile.copyTo(target, overwrite = true)
		project.logger.lifecycle("Copied staged ${stagedFile.absolutePath} → ${target.absolutePath}")
	} else {
		val url = URL(asset.url)
		val conn = url.openConnection() as HttpURLConnection
		conn.requestMethod = "GET"
		conn.setRequestProperty(
			"User-Agent",
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
		)
		conn.setRequestProperty("Accept", "*/*")
		conn.setRequestProperty("Connection", "keep-alive")
		conn.instanceFollowRedirects = true
		conn.connectTimeout = 10_000
		conn.readTimeout = 10_000

		try {
			val status = conn.responseCode
			if (status == HttpURLConnection.HTTP_OK) {
				// Stream the response body into the target file
				conn.inputStream.use { input ->
					target.outputStream().use { output ->
						input.copyTo(output)
					}
				}
				project.logger.lifecycle("Downloaded ${asset.url} → ${target.absolutePath}")
			} else {
				project.logger.warn("Failed to download ${asset.url} (HTTP $status: ${conn.responseMessage})")
				project.logger.warn("Build will continue without this asset - some functionality may be unavailable")
			}
		} finally {
			conn.disconnect()
		}
	}
}

fun assetsFileChecksum(asset: Asset): String? {
	return if (isCiCd) {
		val stagedChecksum = stagedChecksumFor(asset, rootProject.projectDir)
		if (stagedChecksum.exists()) {
			stagedChecksum.readText().trim()
		} else {
			throw GradleException("Failed to find checksum in ${stagedChecksum.absolutePath}")
		}
	} else {
		val checksumUrl = asset.url + ".md5"
		val conn = URL(checksumUrl).openConnection() as HttpURLConnection
		conn.requestMethod = "GET"
		conn.setRequestProperty("User-Agent", "Mozilla/5.0")
		conn.instanceFollowRedirects = true
		conn.connectTimeout = 10_000
		conn.readTimeout = 10_000

		return try {
			val status = conn.responseCode

			if (status == HttpURLConnection.HTTP_OK) {
				val checksum = conn.inputStream.bufferedReader().use { it.readText().trim() }
				// Validate MD5 format (32 hex characters) to detect HTML responses from Cloudflare
				if (checksum.matches(Regex("^[a-fA-F0-9]{32}$"))) {
					checksum
				} else {
					project.logger.warn("Invalid checksum format from $checksumUrl (got: ${checksum.take(50)}...)")
					project.logger.warn("Server likely returning HTML instead of checksum - skipping")
					null
				}
			} else {
				// Workaround for HTTP 415 errors from asset server
				project.logger.warn("Failed to fetch checksum from $checksumUrl (HTTP $status: ${conn.responseMessage})")
				null
			}
		} finally {
			conn.disconnect()
		}
	}
}

fun assetsDownload(
	assets: List<Asset>,
	projectDir: File,
) {
	val checksumDir = File(projectDir, ".checksum")
	checksumDir.mkdirs()

	assets.forEach { asset ->
		val target = File(projectDir, asset.localPath)
		target.parentFile.mkdirs()

		// Path for checksum file
		val canonicalName = asset.localPath.replace("/", "_")
		val checksumFile = File(checksumDir, "${asset.variant}-$canonicalName.md5")

		// Load previous checksum (empty string if missing)
		val previousChecksum: String =
			if (checksumFile.exists()) {
				checksumFile.readText()
			} else {
				""
			}

		val remoteChecksum = assetsFileChecksum(asset)

		val needsDownload = (previousChecksum != remoteChecksum) || previousChecksum.isEmpty()

		if (needsDownload) {
			project.logger.lifecycle("Downloading ${asset.url} → ${asset.localPath}")

			assetsFileDownload(asset, File(rootProject.projectDir, asset.localPath))

			// Only verify checksum if download succeeded
			if (target.exists()) {
				// Recompute checksum after download
				val digest = MessageDigest.getInstance("MD5")
				target.inputStream().use { input ->
					val buffer = ByteArray(8192)
					var read: Int
					while (input.read(buffer).also { read = it } > 0) {
						digest.update(buffer, 0, read)
					}
				}
				val newChecksum = digest.digest().joinToString("") { "%02x".format(it) }
				if (!remoteChecksum.isNullOrEmpty() && newChecksum != remoteChecksum) {
					project.logger.warn("Checksum mismatch for ${asset.localPath} (expected $remoteChecksum, got $newChecksum)")
					project.logger.warn("This may indicate asset server issues - build will continue with downloaded file")
				}

				checksumFile.writeText(newChecksum)
				project.logger.lifecycle("Updated checksum stored: ${checksumFile.absolutePath}")
			} else {
				project.logger.warn("Download failed for ${asset.localPath} - skipping checksum verification")
				project.logger.warn("Build will continue - some functionality may be unavailable")
			}
		} else {
			project.logger.lifecycle("File ${asset.localPath} is up-to-date (checksum matches).")
		}
	}
}

tasks.register("assetsDownloadDebug") {
	group = "setup"
	description = "Download and verify debug assets"
	doLast {
		assetsBatch(rootProject.projectDir, project, "debug")
		assetsDownload(debugAssets, rootProject.projectDir)
	}
}

tasks.register("assetsDownloadRelease") {
	group = "setup"
	description = "Download and verify release assets"
	doLast {
		assetsBatch(rootProject.projectDir, project, "release")
		assetsDownload(releaseAssets, rootProject.projectDir)
	}
}
