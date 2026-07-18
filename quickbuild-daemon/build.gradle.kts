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
	runtimeOnly(libs.kotlin.buildToolsImpl)

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
