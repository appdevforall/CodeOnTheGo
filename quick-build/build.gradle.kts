import com.itsaky.androidide.build.config.BuildConfig

plugins {
	alias(libs.plugins.android.library)
	alias(libs.plugins.kotlin.android)
}

android {
	namespace = "${BuildConfig.PACKAGE_NAME}.quickbuild"

	buildFeatures.aidl = true

	sourceSets {
		named("main") {
			// The deploy-channel AIDL lives in :quickbuild-runtime (the test-app side).
			// Compile the SAME .aidl here instead of depending on that module: its
			// manifest declares the test app's appComponentFactory, which must never
			// merge into CoGo's own APK.
			aidl.srcDir("../quickbuild-runtime/src/main/aidl")
		}
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

dependencies {
	implementation(projects.logger)
	implementation(projects.eventbusEvents)

	implementation(libs.common.kotlin.coroutines.android)
	implementation(libs.google.gson)

	testImplementation(libs.tests.junit.jupiter)
	testImplementation(libs.tests.google.truth)
	testImplementation(libs.tests.kotlinx.coroutines)
	testRuntimeOnly(libs.tests.junit.platformLauncher)
}
