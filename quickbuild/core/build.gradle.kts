import com.itsaky.androidide.build.config.BuildConfig

plugins {
	alias(libs.plugins.android.library)
	alias(libs.plugins.kotlin.android)
}

android {
	namespace = "${BuildConfig.PACKAGE_NAME}.quickbuild"

	buildFeatures.aidl = true

	// AndroidProjectWatcherTest constructs the real watcher on the JVM: FileObserver's
	// stubs then no-op (inotify inert) while the poll/coalesce pipeline runs for real.
	testOptions.unitTests.isReturnDefaultValues = true

	sourceSets {
		named("main") {
			// The deploy-channel AIDL lives in :quickbuild:runtime (the proxy app side).
			// Compile the SAME .aidl here instead of depending on that module: its
			// manifest declares the proxy app's appComponentFactory, which must never
			// merge into CoGo's own APK.
			aidl.srcDir("../runtime/src/main/aidl")
		}
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// DoD coverage gate: >=90% line+branch on non-UI (domain/data) code.
// The root build attaches the jacoco agent to every Test task; for Android modules
// the exec lands at build/outputs/unit_test_code_coverage/<variant>UnitTest/, NOT
// build/jacoco/ -- a JacocoReport pointed at build/jacoco/ silently SKIPs and the
// gate is never measured (see docs/process learnings, ADFA-3834).
tasks.register<JacocoReport>("jacocoTestReport") {
	group = "verification"
	description = "JaCoCo line+branch coverage for the v8Debug unit tests."
	dependsOn("testV8DebugUnitTest")

	reports {
		xml.required.set(true)
		html.required.set(true)
	}

	// The javac output holds only generated code (AIDL stubs + BuildConfig), so the
	// hand-written surface is exactly the Kotlin classes.
	classDirectories.setFrom(
		fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/v8Debug")) {
			exclude("**/BuildConfig*")
		},
	)
	sourceDirectories.setFrom(files("src/main/java"))
	executionData.setFrom(
		layout.buildDirectory.file(
			"outputs/unit_test_code_coverage/v8DebugUnitTest/testV8DebugUnitTest.exec",
		),
	)
}

dependencies {
	implementation(projects.logger)
	implementation(projects.eventbusEvents)
	// Wire DTOs/constants shared with the daemon (single protocol definition).
	implementation(projects.quickbuild.protocol)

	implementation(libs.common.kotlin.coroutines.android)
	implementation(libs.google.gson)

	testImplementation(libs.tests.junit.jupiter)
	testImplementation(libs.tests.google.truth)
	testImplementation(libs.tests.kotlinx.coroutines)
	// Shared offline-guard scanner (OfflineNetworkGuardTest).
	testImplementation(testFixtures(projects.quickbuild.protocol))
	testRuntimeOnly(libs.tests.junit.platformLauncher)
}
