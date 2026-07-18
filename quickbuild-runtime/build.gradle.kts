import com.itsaky.androidide.build.config.BuildConfig

plugins {
	id("com.android.library")
}

description =
	"Quick Build runtime embedded in generated test apps: binds to CoGo, receives payload fds, hot-reloads (ADFA-4128)"

android {
	namespace = "${BuildConfig.PACKAGE_NAME}.quickbuild.runtime"

	defaultConfig {
		// Runs inside apps BUILT WITH CoGo, not inside the IDE.
		minSdk = BuildConfig.MIN_SDK_FOR_APPS_BUILT_WITH_COGO
	}

	compileOptions {
		// Java-only and Java 8, like :logsender - the AAR is injected into user
		// projects and must not drag kotlin-stdlib or any other dependency in.
		sourceCompatibility = JavaVersion.VERSION_1_8
		targetCompatibility = JavaVersion.VERSION_1_8
	}

	buildFeatures.apply {
		aidl = true
		viewBinding = false
		buildConfig = false
	}
}

// JVM unit tests for the plain-Java payload logic (generation gate, metadata/component
// map parsing, asset extraction). Mirrors :quick-build's jupiter setup.
tasks.withType<Test> {
	useJUnitPlatform()
}

dependencies {
	testImplementation(libs.tests.junit.jupiter)
	testImplementation(libs.tests.google.truth)
	testRuntimeOnly(libs.tests.junit.platformLauncher)
}
