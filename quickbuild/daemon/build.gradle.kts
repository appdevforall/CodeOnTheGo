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

	// Fail-if-skipped switch for the toolchain-gated tests (aapt2/d8/Compose - the
	// ADFA-4128 bug 5/6/8 regression coverage). Opt in with REQUIRE_BUILD_TOOLCHAIN=1
	// (env) or -PrequireBuildToolchain: TestSdk then throws from its @EnabledIf
	// predicates when the toolchain is absent, failing the tests instead of skipping.
	// Also undo the root build's ignoreFailures=true (set for coverage collection) so
	// the failure actually fails the build - without that, CI would stay green.
	val requireToolchain =
		providers.environmentVariable("REQUIRE_BUILD_TOOLCHAIN").orNull == "1" ||
			providers.gradleProperty("requireBuildToolchain").isPresent
	systemProperty("quickbuild.test.requireToolchain", requireToolchain.toString())
	if (requireToolchain) {
		ignoreFailures = false
	}
}

// DoD coverage gate: >=90% line+branch on non-UI (domain/data) code.
// The root build applies the jacoco plugin to every subproject, which auto-creates
// jacocoTestReport for JVM modules -- but with the XML report off and no dependency
// on the test task, so the gate is never actually measured. The agent's exec lands
// at the JVM default build/jacoco/test.exec (Android modules differ - see
// :quick-build's report and the ADFA-3834 learnings on silently-SKIPped reports).
tasks.named<JacocoReport>("jacocoTestReport") {
	dependsOn(tasks.test)
	reports {
		xml.required.set(true)
		html.required.set(true)
	}
}

dependencies {
	// The wire DTOs/constants, shared with CoGo's client so both sides compile
	// against one protocol definition. api: the router/handler signatures expose them.
	api(projects.quickbuild.protocol)

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

	// Staged next to the daemon jar on device and passed as -Xplugin when the user
	// project uses Compose.
	composeCompilerPlugin(libs.kotlin.composeCompilerPluginEmbeddable)
	// The compose compile tests resolve a classpath from this; classes.jar is extracted
	// from the AAR at build time and never shipped. Names the -android artifact rather
	// than the KMP umbrella, which redirects via available-at - a redirect a
	// non-transitive configuration will not follow.
	composeTestRuntimeAar(libs.composeRuntimeDaemonTests)

	testImplementation(libs.tests.junit.jupiter)
	testImplementation(libs.tests.google.truth)
	// Shared offline-guard scanner (OfflineNetworkGuardTest).
	testImplementation(testFixtures(projects.quickbuild.protocol))
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
