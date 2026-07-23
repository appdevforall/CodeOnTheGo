@file:Suppress("UnstableApiUsage")

import com.itsaky.androidide.build.config.BuildConfig
import com.itsaky.androidide.desugaring.utils.JavaIOReplacements.applyJavaIOReplacements
import com.itsaky.androidide.plugins.AndroidIDEAssetsPlugin
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
	id("com.android.application")
	id("kotlin-android")
	id("kotlin-kapt")
	id("kotlin-parcelize")
	id("androidx.navigation.safeargs.kotlin")
	id("com.itsaky.androidide.desugaring")
	// Sentry gradle plugin; the SDK it wires up reports to our GlitchTip backend.
	alias(libs.plugins.sentry)
	alias(libs.plugins.google.services)
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

val glitchtipDsn = props.getProperty("glitchtipDsn") ?: propOrEnv("GLITCHTIP_DSN")

apply {
	plugin(AndroidIDEAssetsPlugin::class.java)
}

buildscript {
	dependencies {
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
			manifestPlaceholders["sentryDsn"] = glitchtipDsn
		}
		release {
			manifestPlaceholders["sentryDsn"] = glitchtipDsn
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
			// Debug default: uncompressed libs, loaded straight from the APK. Bundled-assets
			// variants (release/instrumentation) override this to true in AndroidModuleConf.kt
			// so their libs ship deflate-compressed and extract at install time (ADFA-2306).
			useLegacyPackaging = false
		}
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
		isCoreLibraryDesugaringEnabled = true
	}
}

// Sentry gradle plugin config (crash reporting to GlitchTip).
sentry {
	includeProguardMapping = false
}

kapt { arguments { arg("eventBusIndex", "${BuildConfig.PACKAGE_NAME}.events.AppEventsIndex") } }

desugaring {
	replacements {
		includePackage("org.eclipse.jgit")

		applyJavaIOReplacements()
	}
}

configurations.matching { it.name.contains("AndroidTest") }.configureEach {
	exclude(group = "com.google.protobuf", module = "protobuf-lite")
}

configurations.configureEach {
	exclude(group = "org.jetbrains.kotlin", module = "kotlin-android-extensions-runtime")
	// auto-value is an annotation processor fat jar (shaded with ASM, JavaPoet, Guava, etc.)
	// and must not appear on the runtime classpath. Each module runs it via annotationProcessor/kapt
	// during its own compilation; the app module doesn't need it at runtime.
	exclude(group = "com.google.auto.value", module = "auto-value")
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

	implementation(projects.idetooltips)
	implementation(projects.floatingWindow)
	implementation(projects.gitCore)
	implementation(projects.profiler)

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

	// Koin for Dependency Injection
	implementation(libs.koin.android)
	implementation(libs.androidx.security.crypto)

	// Sentry Android SDK (core + replay for quality configuration); our GlitchTip client.
	implementation(libs.sentry.core)
	implementation(libs.sentry.android.core)

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
		// abi/buildName are set by the assemble finalizers below; the project
		// properties allow a standalone run against an already-built APK:
		//   ./gradlew :app:recompressApk -PrecompressAbi=v8 -PrecompressBuildName=release
		val extraProps = extensions.extraProperties
		val abi: String =
			if (extraProps.has("abi")) {
				extraProps["abi"].toString()
			} else {
				project.findProperty("recompressAbi")?.toString()
					?: error("recompressApk: set -PrecompressAbi=v7|v8")
			}
		val buildName: String =
			if (extraProps.has("buildName")) {
				extraProps["buildName"].toString()
			} else {
				project.findProperty("recompressBuildName")?.toString()
					?: error("recompressApk: set -PrecompressBuildName=debug|release")
			}
		val noCompressExtensions =
			if (extraProps.has("noCompressExtensions")) {
				@Suppress("UNCHECKED_CAST")
				(extraProps["noCompressExtensions"] as? Set<String>).orEmpty()
			} else if (buildName == "debug") {
				noCompressDebug
			} else {
				noCompressRelease
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
//
// "so" stays DEFLATED in both lists (ADFA-2306 release, ADFA-4729 CI debug): the app
// manifest hard-codes extractNativeLibs="true", so the installer extracts libs to
// nativeLibraryDir at install time and AGP already packages them deflate-compressed
// (~5.9 MB smaller per APK). Re-storing them here would silently undo that, which is
// exactly what happened to CI debug APKs before ADFA-4729. Local debug builds never
// run this task and get AGP's deflated packaging as-is.
val noCompressDebug = noCompress - "jar" - "so"
val noCompressRelease = noCompress - "so"

afterEvaluate {
	tasks.named("assembleV8Release").configure {
		finalizedBy("recompressApk")

		doLast {
			tasks.named("recompressApk").configure {
				extensions.extraProperties["abi"] = "v8"
				extensions.extraProperties["buildName"] = "release"
				extensions.extraProperties["noCompressExtensions"] = noCompressRelease
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
				extensions.extraProperties["noCompressExtensions"] = noCompressRelease
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

	// advzip runs for release APKs and on CI (including CI debug); a dev
	// invoking recompressApk locally against a debug APK skips it. Local debug
	// assembles never run this task at all (ADFA-1167).
	val useAdvzip = buildName == "release" || isCiCd

	// 7z-level deflate (-3) by default; -Pzopfli (-4) buys only ~50 KB more per
	// release APK at ~4x the CPU time (ADFA-1167).
	val advzipArgs =
		if (project.hasProperty("zopfli")) {
			listOf("-4", "-i", "5")
		} else {
			listOf("-3")
		}

	apkDir.walk().filter { it.extension == "apk" }.forEach { apkFile ->
		project.logger.lifecycle("Recompressing APK: ${apkFile.name}")
		val tempZipFile = File(apkFile.parentFile, "${apkFile.name}.tmp")
		recompressZip(apkFile, tempZipFile, noCompressExtensions, if (useAdvzip) advzipArgs else null)
		signApk(tempZipFile)
		Files.move(
			tempZipFile.toPath(),
			apkFile.toPath(),
			StandardCopyOption.REPLACE_EXISTING,
		)
	}
}

// Raw (still-compressed) entry data plus the metadata needed to write it into
// a zip without recompressing it.
class RecompressedData(
	val method: Int,
	val crc: Long,
	val data: ByteArray,
)

fun findAdvzip(): File? {
	System.getenv("ADVZIP")?.let { override ->
		return File(override).takeIf { it.canExecute() }
	}
	val dirs =
		System.getenv("PATH").orEmpty().split(File.pathSeparator) +
			listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")
	return dirs.map { File(it, "advzip") }.firstOrNull { it.canExecute() }
}

// Preallocation hint for deflateBest's output buffer: assume roughly 3:1
// compression, plus slack so empty/tiny inputs don't start at zero capacity.
// The buffer grows as needed; these only avoid early re-allocations.
val assumedDeflateRatio = 3
val deflateOutputSlackBytes = 64

fun deflateBest(data: ByteArray): RecompressedData {
	val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
	deflater.setInput(data)
	deflater.finish()
	val out = ByteArrayOutputStream(data.size / assumedDeflateRatio + deflateOutputSlackBytes)
	val buf = ByteArray(DEFAULT_BUFFER_SIZE)
	while (!deflater.finished()) {
		out.write(buf, 0, deflater.deflate(buf))
	}
	deflater.end()
	val crc = CRC32().apply { update(data) }.value
	return RecompressedData(ZipEntry.DEFLATED, crc, out.toByteArray())
}

fun leU16(
	bytes: ByteArray,
	offset: Int,
): Int = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

fun leU32(
	bytes: ByteArray,
	offset: Int,
): Long = leU16(bytes, offset).toLong() or (leU16(bytes, offset + 2).toLong() shl 16)

// java.util.zip has no API to read entry data without inflating it, so walk
// the central directory by hand.
fun readRawZipEntries(zipFile: File): Map<String, RecompressedData> {
	val bytes = zipFile.readBytes()
	var eocd = bytes.size - 22
	while (eocd >= 0 && leU32(bytes, eocd) != 0x06054b50L) {
		eocd--
	}
	require(eocd >= 0) { "EOCD not found in ${zipFile.name}" }
	val count = leU16(bytes, eocd + 10)
	var pos = leU32(bytes, eocd + 16).toInt()
	val result = LinkedHashMap<String, RecompressedData>(count * 2)
	repeat(count) {
		require(leU32(bytes, pos) == 0x02014b50L) { "bad central directory record in ${zipFile.name}" }
		val method = leU16(bytes, pos + 10)
		val crc = leU32(bytes, pos + 16)
		val compressedSize = leU32(bytes, pos + 20).toInt()
		val nameLen = leU16(bytes, pos + 28)
		val extraLen = leU16(bytes, pos + 30)
		val commentLen = leU16(bytes, pos + 32)
		val headerOffset = leU32(bytes, pos + 42).toInt()
		val name = String(bytes, pos + 46, nameLen, Charsets.UTF_8)
		val dataOffset = headerOffset + 30 + leU16(bytes, headerOffset + 26) + leU16(bytes, headerOffset + 28)
		result[name] = RecompressedData(method, crc, bytes.copyOfRange(dataOffset, dataOffset + compressedSize))
		pos += 46 + nameLen + extraLen + commentLen
	}
	return result
}

// Compresses all entries in one advzip run (a per-file invocation would pay
// process startup ~2000 times). Returns null on failure so the caller can
// fall back to Deflater.
fun advzipCompress(
	entries: Map<String, ByteArray>,
	workDir: File,
	advzip: File,
	advzipArgs: List<String>,
): Map<String, RecompressedData>? {
	val workZip = File.createTempFile("advzip-work", ".zip", workDir)
	try {
		ZipOutputStream(BufferedOutputStream(FileOutputStream(workZip))).use { zos ->
			for ((name, data) in entries) {
				// STORED: advzip recompresses every entry anyway, so don't
				// waste time deflating here.
				val entry = ZipEntry(name)
				entry.method = ZipEntry.STORED
				entry.size = data.size.toLong()
				entry.crc = CRC32().apply { update(data) }.value
				entry.time = 0L
				zos.putNextEntry(entry)
				zos.write(data)
				zos.closeEntry()
			}
		}

		val process =
			ProcessBuilder(listOf(advzip.absolutePath, "-z", "-q") + advzipArgs + workZip.absolutePath)
				.redirectErrorStream(true)
				.start()
		val output = process.inputStream.bufferedReader().readText()
		if (process.waitFor() != 0) {
			project.logger.warn("advzip failed, falling back to Deflater: $output")
			return null
		}
		return readRawZipEntries(workZip)
	} finally {
		workZip.delete()
	}
}

// Minimal zip writer that can emit pre-compressed (raw deflate) entry data,
// which ZipOutputStream cannot. Timestamps are zeroed for deterministic
// output (-X); STORED entry data is 4-byte aligned via zero-padded extra
// fields (zipalign parity, so the runtime can mmap uncompressed assets).
// No zip64 support: APKs stay under 4 GiB / 65535 entries.
class RawZipWriter(
	file: File,
) : Closeable {
	private class CentralRecord(
		val nameBytes: ByteArray,
		val method: Int,
		val crc: Long,
		val compressedSize: Long,
		val size: Long,
		val headerOffset: Long,
	)

	private val out = BufferedOutputStream(FileOutputStream(file))
	private val central = mutableListOf<CentralRecord>()
	private var offset = 0L

	private fun u16(value: Int) {
		out.write(value and 0xFF)
		out.write((value ushr 8) and 0xFF)
		offset += 2
	}

	private fun u32(value: Long) {
		require(value in 0..0xFFFFFFFFL) { "zip64 required but not supported (value $value)" }
		for (shift in 0..24 step 8) {
			out.write(((value ushr shift) and 0xFF).toInt())
		}
		offset += 4
	}

	private fun raw(bytes: ByteArray) {
		out.write(bytes)
		offset += bytes.size
	}

	fun addEntry(
		name: String,
		method: Int,
		crc: Long,
		size: Long,
		compressedSize: Long,
		data: InputStream,
	) {
		require(central.size < 0xFFFF) { "too many zip entries" }
		val nameBytes = name.toByteArray(Charsets.UTF_8)
		// Zero-padded extra field so STORED data starts on a 4-byte boundary
		val padding =
			if (method == ZipEntry.STORED) {
				((4 - (offset + 30 + nameBytes.size) % 4) % 4).toInt()
			} else {
				0
			}
		central.add(CentralRecord(nameBytes, method, crc, compressedSize, size, offset))
		u32(0x04034b50L)
		u16(20) // version needed to extract
		u16(0x800) // general purpose flags: UTF-8 names
		u16(method)
		u16(0) // dos time
		u16(0) // dos date
		u32(crc)
		u32(compressedSize)
		u32(size)
		u16(nameBytes.size)
		u16(padding) // extra field length
		raw(nameBytes)
		raw(ByteArray(padding))
		var copied = 0L
		val buf = ByteArray(DEFAULT_BUFFER_SIZE)
		while (true) {
			val n = data.read(buf)
			if (n < 0) break
			out.write(buf, 0, n)
			copied += n
		}
		offset += copied
		require(copied == compressedSize) { "entry $name: wrote $copied bytes, expected $compressedSize" }
	}

	override fun close() {
		val cdStart = offset
		for (record in central) {
			u32(0x02014b50L)
			u16(20) // version made by
			u16(20) // version needed to extract
			u16(0x800)
			u16(record.method)
			u16(0) // dos time
			u16(0) // dos date
			u32(record.crc)
			u32(record.compressedSize)
			u32(record.size)
			u16(record.nameBytes.size)
			u16(0) // extra field length
			u16(0) // comment length
			u16(0) // disk number
			u16(0) // internal attributes
			u32(0L) // external attributes
			u32(record.headerOffset)
			raw(record.nameBytes)
		}
		val cdSize = offset - cdStart
		u32(0x06054b50L)
		u16(0)
		u16(0)
		u16(central.size)
		u16(central.size)
		u32(cdSize)
		u32(cdStart)
		u16(0)
		out.close()
	}
}

fun recompressZip(
	inputZip: File,
	outputZip: File,
	noCompressExtensions: Set<String>,
	advzipArgs: List<String>?,
) {
	fun extensionOf(name: String) = name.substringAfterLast('.', "").lowercase()

	ZipFile(inputZip).use { zip ->
		val entries =
			zip
				.entries()
				.asSequence()
				.filter { !it.isDirectory }
				.toList()

		val deflatable = LinkedHashMap<String, ByteArray>()
		for (entry in entries) {
			if (extensionOf(entry.name) !in noCompressExtensions) {
				deflatable[entry.name] = zip.getInputStream(entry).readBytes()
			}
		}

		// advzip's 7z/zopfli deflate shaves ~4% off the deflated payload
		// (~2 MB per release APK) over Deflater BEST_COMPRESSION (ADFA-1167).
		// Without advzip, fall back to Deflater: same output as before
		// ADFA-1167. advzipArgs == null means advzip is intentionally skipped.
		val advzip = if (advzipArgs != null) findAdvzip() else null
		val advzipped =
			if (advzip != null && advzipArgs != null) {
				project.logger.lifecycle(
					"Compressing ${deflatable.size} entries with advzip ${advzipArgs.joinToString(" ")}: ${advzip.path}",
				)
				advzipCompress(deflatable, outputZip.parentFile, advzip, advzipArgs)
			} else {
				if (advzipArgs != null) {
					project.logger.lifecycle(
						"advzip not found; using Deflater BEST_COMPRESSION " +
							"(install advancecomp or set ADVZIP for a ~2 MB smaller APK)",
					)
				}
				null
			}

		RawZipWriter(outputZip).use { writer ->
			for (entry in entries) {
				if (extensionOf(entry.name) in noCompressExtensions) {
					// Force STORE for no-compress extensions, streaming
					// straight from the source zip
					zip.getInputStream(entry).use { input ->
						writer.addEntry(entry.name, ZipEntry.STORED, entry.crc, entry.size, entry.size, input)
					}
				} else {
					val data = deflatable.getValue(entry.name)
					val compressed = advzipped?.get(entry.name) ?: deflateBest(data)
					writer.addEntry(
						entry.name,
						compressed.method,
						compressed.crc,
						data.size.toLong(),
						compressed.data.size.toLong(),
						ByteArrayInputStream(compressed.data),
					)
				}
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
		@Suppress("DEPRECATION")
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
		conn.readTimeout = 60_000

		try {
			val status = conn.responseCode
			if (status == HttpURLConnection.HTTP_OK) {
				conn.inputStream.use { input ->
					target.outputStream().use { output ->
						input.copyTo(output)
					}
				}
				project.logger.lifecycle("Downloaded ${asset.url} → ${target.absolutePath}")
			} else {
				throw GradleException("Failed to download ${asset.url} (HTTP $status: ${conn.responseMessage})")
			}
		} finally {
			conn.disconnect()
		}
	}
}

fun fileMd5(file: File): String {
	val digest = MessageDigest.getInstance("MD5")
	file.inputStream().use { input ->
		val buffer = ByteArray(8192)
		var read: Int
		while (input.read(buffer).also { read = it } > 0) {
			digest.update(buffer, 0, read)
		}
	}
	return digest.digest().joinToString("") { "%02x".format(it) }
}

fun assetsFileChecksum(asset: Asset): String {
	val checksum =
		if (isCiCd) {
			val stagedChecksum = stagedChecksumFor(asset, rootProject.projectDir)
			if (!stagedChecksum.exists()) {
				throw GradleException("Failed to find checksum in ${stagedChecksum.absolutePath}")
			}
			stagedChecksum.readText().trim()
		} else {
			val checksumUrl = asset.url + ".md5"
			val conn = URL(checksumUrl).openConnection() as HttpURLConnection
			conn.requestMethod = "GET"
			conn.setRequestProperty("User-Agent", "Mozilla/5.0")
			conn.instanceFollowRedirects = true
			conn.connectTimeout = 10_000
			conn.readTimeout = 10_000

			try {
				val status = conn.responseCode
				if (status != HttpURLConnection.HTTP_OK) {
					throw GradleException("Failed to fetch checksum from $checksumUrl (HTTP $status: ${conn.responseMessage})")
				}
				conn.inputStream.bufferedReader().use { it.readText().trim() }
			} finally {
				conn.disconnect()
			}
		}

	if (!checksum.matches(Regex("^[a-fA-F0-9]{32}$"))) {
		throw GradleException(
			"Invalid MD5 checksum for ${asset.remotePath} (got: '${checksum.take(
				50,
			)}') - the server may be returning an error page instead of the checksum",
		)
	}
	return checksum.lowercase()
}

fun assetsDownload(
	assets: List<Asset>,
	projectDir: File,
) {
	assets.forEach { asset ->
		val target = File(projectDir, asset.localPath)
		target.parentFile.mkdirs()

		val remoteChecksum = assetsFileChecksum(asset)

		if (target.exists() && fileMd5(target) == remoteChecksum) {
			project.logger.lifecycle("File ${asset.localPath} is up-to-date (checksum matches).")
			return@forEach
		}

		project.logger.lifecycle("Downloading ${asset.url} → ${asset.localPath}")
		assetsFileDownload(asset, target)

		val newChecksum = fileMd5(target)
		if (newChecksum != remoteChecksum) {
			throw GradleException(
				"Checksum mismatch for ${asset.localPath} (expected $remoteChecksum, got $newChecksum)",
			)
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
