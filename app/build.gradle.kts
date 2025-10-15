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
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.util.Properties
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.reflect.jvm.javaMethod

fun TaskContainer.registerD8Task(
    taskName: String,
    inputJar: File,
    outputDex: File
): org.gradle.api.tasks.TaskProvider<Exec> {
    val androidSdkDir = android.sdkDirectory.absolutePath
    val buildToolsVersion = android.buildToolsVersion // Gets the version from your project
    val d8Executable = File("$androidSdkDir/build-tools/$buildToolsVersion/d8")

    if (!d8Executable.exists()) {
        throw FileNotFoundException("D8 executable not found at: ${d8Executable.absolutePath}")
    }

    return register<Exec>(taskName) {
        inputs.file(inputJar)
        outputs.file(outputDex)

        commandLine(
            d8Executable.absolutePath,
            "--release", // Enables optimizations
            "--output", outputDex.parent, // D8 outputs to a directory
            inputJar.absolutePath
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
		execution = "ANDROIDX_TEST_ORCHESTRATOR"

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
            excludes.add("META-INF/DEPENDENCIES")
            excludes.add("META-INF/gradle/incremental.annotation.processors")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
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

dependencies {
	// debugImplementation(libs.common.leakcanary)

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

	// This is to build the tooling-api-impl project before the app is built
	// So we always copy the latest JAR file to assets
	compileOnly(projects.subprojects.toolingApiImpl)

	testImplementation(projects.testing.unit)
	testImplementation(libs.core.tests.anroidx.arch)
	androidTestImplementation(projects.common)
	androidTestImplementation(projects.testing.android)
	androidTestImplementation(libs.tests.kaspresso)
	androidTestImplementation(libs.tests.junit.kts)
	androidTestImplementation(libs.tests.androidx.test.runner)
	androidTestUtil(libs.tests.orchestrator)

	// brotli4j
	implementation(libs.brotli4j)

	implementation(libs.common.markwon.core)
	implementation(libs.common.markwon.linkify)
	implementation(libs.commons.text.v1140)

    implementation(libs.kotlinx.serialization.json)
	// Koin for Dependency Injection
    implementation(libs.koin.android)
	implementation(libs.androidx.security.crypto)

	// Firebase Analytics
	implementation(platform(libs.firebase.bom))
	implementation(libs.firebase.analytics)

	// Lifecycle Process for app lifecycle tracking
	implementation(libs.androidx.lifecycle.process)
	implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.google.genai)
    implementation(project(":llama-api"))
    coreLibraryDesugaring(libs.desugar.jdk.libs.v215)
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

fun createAssetsZip(arch: String) {
    val outputDir =
        project.layout.buildDirectory
            .dir("outputs/assets")
            .get()
            .asFile
    if (!outputDir.exists()) {
        outputDir.mkdirs()
    }
    val zipFile = outputDir.resolve("assets-$arch.zip")

    // --- Part 1: Get the classes.jar from our llama-impl AAR ---
    val llamaAarName = when (arch) {
        "arm64-v8a" -> "llama-impl-v8-release.aar"
        "armeabi-v7a" -> "llama-impl-v7-release.aar"
        else -> throw IllegalArgumentException("Unsupported architecture for Llama AAR: $arch")
    }
    val originalLlamaAarFile = project.rootDir.resolve("llama-impl/build/outputs/aar/$llamaAarName")

    val tempDir = project.layout.buildDirectory.dir("tmp/d8/$arch").get().asFile
    tempDir.deleteRecursively()
    tempDir.mkdirs()
    val tempClassesJar = File(tempDir, "classes.jar")

    // Extract just the classes.jar from our target AAR
    ZipInputStream(originalLlamaAarFile.inputStream()).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            if (entry.name == "classes.jar") {
                tempClassesJar.outputStream().use { fos -> zis.copyTo(fos) }
                break
            }
            entry = zis.nextEntry
        }
    }
    if (!tempClassesJar.exists()) {
        throw GradleException("classes.jar not found inside ${originalLlamaAarFile.name}")
    }

    val llamaImplProject = project.project(":llama-impl")
    val flavorName = if (arch == "arm64-v8a") "v8" else "v7"
    val configName = "${flavorName}ReleaseRuntimeClasspath"
    val runtimeClasspathFiles = llamaImplProject.configurations.getByName(configName).files

    val explodedAarsDir = project.layout.buildDirectory.dir("tmp/exploded-aars/$arch").get().asFile
    explodedAarsDir.mkdirs()

    val d8Classpath = mutableListOf<File>()
    runtimeClasspathFiles.forEach { file ->
        if (file.name.endsWith(".jar")) {
            d8Classpath.add(file)
        } else if (file.name.endsWith(".aar")) {
            // It's an AAR, extract its classes.jar
            project.copy {
                from(project.zipTree(file)) {
                    include("classes.jar")
                }
                into(explodedAarsDir)
                // Rename to avoid collisions
                rename { "${file.nameWithoutExtension}-classes.jar" }
            }
            d8Classpath.add(File(explodedAarsDir, "${file.nameWithoutExtension}-classes.jar"))
        }
    }

    // --- Part 3: Run D8 with the corrected command-line arguments ---
    val dexOutputFile = File(tempDir, "classes.dex")
    project.exec {
        val androidSdkDir = android.sdkDirectory.absolutePath
        val buildToolsVersion = android.buildToolsVersion
        val d8Executable = File("$androidSdkDir/build-tools/$buildToolsVersion/d8")

        // 1. Start building the command arguments list
        val d8Command = mutableListOf<String>()
        d8Command.add(d8Executable.absolutePath)
        d8Command.add("--release")
        d8Command.add("--min-api")
        d8Command.add(android.defaultConfig.minSdk.toString()) // Add minSdk for better desugaring

        // 2. Add the --classpath flag REPEATEDLY for each dependency file
        d8Classpath.forEach { file ->
            if (file.exists()) {
                d8Command.add("--classpath")
                d8Command.add(file.absolutePath)
            }
        }

        // 3. Add the final output and input arguments
        d8Command.add("--output")
        d8Command.add(tempDir.absolutePath)
        d8Command.add(tempClassesJar.absolutePath)

        // 4. Set the full command line
        commandLine(d8Command)
    }.assertNormalExitValue()

    if (!dexOutputFile.exists()) {
        throw GradleException("D8 task failed to produce classes.dex")
    }

    // --- Part 4: Repackage everything into the final assets-*.zip (Unchanged) ---
    val sourceDir = project.rootDir.resolve("assets")
    val bootstrapName = "bootstrap-$arch.zip"
    val androidSdkName = "android-sdk-$arch.zip"
    ZipOutputStream(zipFile.outputStream()).use { zipOut ->
        arrayOf(
            androidSdkName,
            "localMvnRepository.zip",
            "gradle-8.14.3-bin.zip",
            "gradle-api-8.14.3.jar.zip",
            "documentation.db",
            bootstrapName,
        ).forEach { fileName ->
			val filePath = sourceDir.resolve(fileName)
			if (!filePath.exists()) {
				throw FileNotFoundException(filePath.absolutePath)
			}

			project.logger.lifecycle("Zipping $fileName from ${filePath.absolutePath}")
			val entryName =
				when (fileName) {
					bootstrapName -> "bootstrap.zip"
					androidSdkName -> "android-sdk.zip"
					else -> fileName
				}

			zipOut.putNextEntry(ZipEntry(entryName))
			filePath.inputStream().use { input -> input.copyTo(zipOut) }
			zipOut.closeEntry()
		}
        project.logger.lifecycle("Repackaging Llama AAR with classes.dex...")

        // Create the entry for our modified AAR inside assets-*.zip
        zipOut.putNextEntry(ZipEntry("dynamic_libs/llama.aar"))

        // Use another ZipOutputStream to build the new AAR in memory and stream it
        ZipOutputStream(zipOut).use { aarZipOut ->
            // Copy all files from the original AAR *except* classes.jar
            ZipInputStream(originalLlamaAarFile.inputStream()).use { originalAarStream ->
                var entry = originalAarStream.nextEntry
                while (entry != null) {
                    if (entry.name != "classes.jar") {
                        aarZipOut.putNextEntry(ZipEntry(entry.name))
                        originalAarStream.copyTo(aarZipOut)
                        aarZipOut.closeEntry()
                    }
                    entry = originalAarStream.nextEntry
                }
            }
            aarZipOut.putNextEntry(ZipEntry("classes.dex"))
            dexOutputFile.inputStream().use { dexInput -> dexInput.copyTo(aarZipOut) }
            aarZipOut.closeEntry()
        }
        println("Created ${zipFile.name} successfully at ${zipFile.parentFile.absolutePath}")
    }
}

tasks.register("assembleV8Assets") {
    dependsOn(":llama-impl:assembleV8Release")
	doLast {
		createAssetsZip("arm64-v8a")
	}
}

tasks.register("assembleV7Assets") {
    dependsOn(":llama-impl:assembleV7Release")
	doLast {
		createAssetsZip("armeabi-v7a")
	}
}

tasks.register("assembleAssets") {
	dependsOn("assembleV8Assets", "assembleV7Assets")
}

tasks.register("recompressApk") {
	doLast {
		val abi: String = extensions.extraProperties["abi"].toString()
		val buildName: String = extensions.extraProperties["buildName"].toString()

		project.logger.lifecycle("Calling recompressApk abi:$abi buildName:$buildName")

		recompressApk(abi, buildName)
	}
}

afterEvaluate {
	tasks.named("assembleV8Release").configure {
		finalizedBy("recompressApk")

		doLast {
			tasks.named("recompressApk").configure {
				extensions.extraProperties["abi"] = "v8"
				extensions.extraProperties["buildName"] = "release"
			}
		}
	}

	tasks.named("assembleV7Release").configure {
		finalizedBy("recompressApk")

		doLast {
			tasks.named("recompressApk").configure {
				extensions.extraProperties["abi"] = "v7"
				extensions.extraProperties["buildName"] = "release"
			}
		}
	}
}

fun recompressApk(
	abi: String,
	buildName: String,
) {
	val apkDir: File =
		layout.buildDirectory
			.dir("outputs/apk/$abi/$buildName")
			.get()
			.asFile
	project.logger.lifecycle("Recompressing APK Dir: ${apkDir.path}")

	apkDir.walk().filter { it.extension == "apk" }.forEach { apkFile ->
		project.logger.lifecycle("Recompressing APK: ${apkFile.name}")
		val tempZipFile = File(apkFile.parentFile, "${apkFile.name}.tmp")
		recompressZip(apkFile, tempZipFile)
		signApk(tempZipFile)
		if (apkFile.delete()) {
			tempZipFile.renameTo(apkFile)
		}
	}
}

fun recompressZip(
	inputZip: File,
	outputZip: File,
) {
	ZipInputStream(BufferedInputStream(FileInputStream(inputZip))).use { zis ->
		ZipOutputStream(BufferedOutputStream(FileOutputStream(outputZip))).use { zos ->
			zos.setLevel(Deflater.BEST_COMPRESSION)

			var entry = zis.nextEntry
			while (entry != null) {
				if (!entry.isDirectory) {
					val newEntry = ZipEntry(entry.name)

					// Remove timestamps for deterministic output (-X)
					newEntry.time = 0L
					try {
						newEntry.creationTime = FileTime.fromMillis(0)
						newEntry.lastModifiedTime = FileTime.fromMillis(0)
						newEntry.lastAccessTime = FileTime.fromMillis(0)
					} catch (_: Throwable) {
						// In case JVM doesn't support them
					}

					zos.putNextEntry(newEntry)
					zis.copyTo(zos)
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

    val signingConfig = android.signingConfigs.findByName("common")
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
