import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
	id("java-library")
	id("org.jetbrains.kotlin.jvm")
}

description =
	"Quick Build warm compile daemon: BTA incremental Kotlin compile + d8 + aapt2, run as a CoGo child process on the bundled JDK (ADFA-4128)"

java {
	sourceCompatibility = JavaVersion.VERSION_17
	targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
	jvmToolchain(17)
}

// The Compose compiler plugin the daemon passes as -Xplugin when the user project uses
// Compose. Its own configuration (not runtimeClasspath): it is compiler INPUT, not a
// library the daemon's JVM loads. :app's quickBuildDaemonZip stages it next to the
// daemon jar under the stable name compose-compiler-plugin.jar.
val composeCompilerPlugin: Configuration by configurations.creating {
	isCanBeConsumed = false
	isTransitive = false
}

// Compose runtime for the compose compile tests' classpath. Resolved as the Android
// AAR (what a real project's compile classpath carries); classes.jar is extracted
// below. Test-only - never shipped.
val composeTestRuntimeAar: Configuration by configurations.creating {
	isCanBeConsumed = false
	isTransitive = false
	attributes {
		attribute(
			Usage.USAGE_ATTRIBUTE,
			objects.named(Usage::class.java, Usage.JAVA_RUNTIME),
		)
	}
}

val stageComposeTestRuntime =
	tasks.register<Sync>("stageComposeTestRuntime") {
		val aars = composeTestRuntimeAar
		from(provider { zipTree(aars.singleFile) }) {
			include("classes.jar")
			rename("classes.jar", "compose-runtime.jar")
		}
		into(layout.buildDirectory.dir("compose-test-runtime"))
	}

tasks.withType<Test> {
	useJUnitPlatform()
	// Real inputs, not just dependsOn: a changed plugin or runtime jar must re-run tests.
	inputs.files(stageComposeTestRuntime)
	inputs.files(composeCompilerPlugin)
	systemProperty(
		"quickbuild.test.composeRuntimeJar",
		layout.buildDirectory
			.dir("compose-test-runtime")
			.get()
			.asFile
			.resolve("compose-runtime.jar")
			.absolutePath,
	)
	jvmArgumentProviders.add(
		CommandLineArgumentProvider {
			listOf("-Dquickbuild.test.composePluginJar=${composeCompilerPlugin.singleFile.absolutePath}")
		},
	)
}

dependencies {
	implementation(libs.kotlin.buildToolsApi)
	implementation(libs.google.gson)
	// ACC_FINAL stripping on recompiled payload classes (proxies extend user classes).
	implementation(libs.ow2.asm)
	// The BTA implementation + its runtime deps are loaded from the daemon's runtime
	// classpath on device (staged alongside the jar), matched to the bundled compiler.
	// kotlin-compiler-runner exists solely to launch/talk to a separate long-lived
	// "Kotlin compile daemon" JVM over RMI, which IncrementalCompiler never does here
	// (it always calls useInProcessStrategy()) - dead weight (~17 KB of the ~62 MB
	// quickbuild-daemon.zip, ADFA-4128 size audit).
	// kotlin-daemon-client and kotlin-daemon-embeddable looked like the same kind of
	// dead weight but are NOT: BuildToolsApiBuildICReporter.reportCompileIteration (part
	// of kotlin-build-tools-impl itself, on the in-process path) references
	// org.jetbrains.kotlin.daemon.common.CompileIterationResult, which lives in
	// kotlin-daemon-client - excluding it throws NoClassDefFoundError and failed 12/52
	// :quickbuild-daemon:test cases. Keep both.
	runtimeOnly(libs.kotlin.buildToolsImpl) {
		exclude(group = "org.jetbrains.kotlin", module = "kotlin-compiler-runner")
	}

	composeCompilerPlugin(libs.kotlin.composeCompilerPluginEmbeddable)
	composeTestRuntimeAar(libs.composeRuntimeDaemonTests)

	testImplementation(libs.tests.junit.jupiter)
	testImplementation(libs.tests.google.truth)
	testRuntimeOnly(libs.tests.junit.platformLauncher)
}

/** Single runnable jar; the runtime classpath is staged next to it on device. */
val daemonJar =
	tasks.register<Jar>("daemonJar") {
		archiveBaseName.set("quickbuild-daemon")
		// Not build/libs: the default jar task also writes quickbuild-daemon.jar there,
		// and two tasks sharing one archive path trips Gradle's implicit-dependency
		// validation in any consumer (:app:quickBuildDaemonZip).
		destinationDirectory.set(layout.buildDirectory.dir("daemon-jar"))
		manifest {
			attributes["Main-Class"] = "org.appdevforall.cotg.quickbuild.daemon.DaemonMain"
			attributes["Class-Path"] =
				configurations.runtimeClasspath
					.get()
					.files
					.joinToString(" ") { it.name }
		}
		from(sourceSets.main.get().output)
	}

// The manifest Class-Path above names the runtime jars by FILE NAME, resolved
// relative to the jar's own directory. This stages a complete runnable layout
// (jar + deps side by side) so `java -jar build/daemon/quickbuild-daemon.jar`
// works with no manual copy step - what the corpus harness points --daemon-jar at.
tasks.register<Sync>("stageDaemon") {
	from(daemonJar)
	from(configurations.runtimeClasspath)
	into(layout.buildDirectory.dir("daemon"))
}

// Alternative-target codegen backends inside kotlin-compiler-embeddable that a JVM-only
// incremental compile (the daemon's ONLY mode - IncrementalCompiler always drives
// K2JVMCompiler) never loads. Compose is JVM-IR, so it's covered too. Prefixes are under
// org/jetbrains/kotlin/. Verified severable by the full host corpus matrix (13 apps / 48
// edits, output-equivalence PASS on all, including compose-kotlin, mixed-lang, and the
// real sora-editor-lib slice): quick-build/corpus/results/ (ADFA-4128 R8/shrink spike).
// This is deliberate jar surgery, NOT R8 tree-shaking: R8 --classfile shrank the compiler
// to 37 MB but the output was non-functional - every Kotlin compile died with
// NoClassDefFoundError initializing a core CLI diagnostics class, because tree-shaking cut
// a static-init dependency reached only reflectively. Removing whole never-loaded backend
// subtrees leaves every kept class byte-identical, so there is no reflection-breakage risk
// in the code that remains; the corpus is the regression gate.
val quickBuildStripPrefixes =
	listOf(
		"org/jetbrains/kotlin/backend/wasm/",
		"org/jetbrains/kotlin/ir/backend/js/",
		"org/jetbrains/kotlin/backend/konan/",
		"org/jetbrains/kotlin/cli/js/",
		"org/jetbrains/kotlin/cli/metadata/",
		"org/jetbrains/kotlin/wasm/",
		"org/jetbrains/kotlin/js/",
		"org/jetbrains/kotlin/serialization/js/",
		"org/jetbrains/kotlin/konan/",
		"org/jetbrains/kotlin/library/",
		"org/jetbrains/kotlin/native/",
	)

// Opt-in shrunk staging: same layout as stageDaemon, but the compiler jar has the
// never-loaded backend packages removed (~5.7 MB off the compressed daemon zip). Not wired
// into the APK packaging (:app:quickBuildDaemonZip) - flipping that to consume this is the
// deliberate follow-up, gated on a green corpus run. Point the corpus harness'
// --daemon-jar at build/daemon-shrunk/quickbuild-daemon.jar to re-verify.
tasks.register<Sync>("stageDaemonShrunk") {
	from(daemonJar)
	from(configurations.runtimeClasspath)
	into(layout.buildDirectory.dir("daemon-shrunk"))
	val prefixes = quickBuildStripPrefixes
	doLast {
		val dir = layout.buildDirectory.dir("daemon-shrunk").get().asFile
		val jar =
			dir.listFiles { f -> f.name.startsWith("kotlin-compiler-embeddable-") }
				?.singleOrNull()
				?: error("compiler-embeddable jar not found in $dir")
		val tmp = File(jar.parentFile, "${jar.name}.stripping")
		var kept = 0
		var dropped = 0
		ZipFile(jar).use { zip ->
			ZipOutputStream(tmp.outputStream().buffered()).use { out ->
				for (entry in zip.entries()) {
					if (prefixes.any { entry.name.startsWith(it) }) {
						dropped++
						continue
					}
					out.putNextEntry(ZipEntry(entry.name))
					zip.getInputStream(entry).use { it.copyTo(out) }
					out.closeEntry()
					kept++
				}
			}
		}
		tmp.copyTo(jar, overwrite = true)
		tmp.delete()
		logger.lifecycle("stageDaemonShrunk: kept $kept entries, dropped $dropped from ${jar.name} (${jar.length() / 1048576} MB)")
	}
}
