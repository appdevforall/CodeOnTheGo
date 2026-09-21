plugins {
	id("com.android.library")
	id("kotlin-android")
	id("kotlin-parcelize")
	alias(libs.plugins.binary.compatibility.validator)
}

android {
	namespace = "com.itsaky.androidide.plugins.api"
	compileSdk = 36

	defaultConfig {
		minSdk = 28
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}
}

kotlin {
	compilerOptions {
		jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
		// Emit metadata every supported on-device Kotlin compiler can read (<= 2.0.0), so a
		// device still on an older bundled toolchain keeps working after a KOTLIN_VERSION bump.
		// This jar ships in the plugin-api coordinate on-device plugins compile against.
		apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
		languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
	}
}

apiValidation {
	ignoredClasses.add("com.itsaky.androidide.plugins.api.BuildConfig")
	nonPublicMarkers.add("com.itsaky.androidide.plugins.base.InternalPluginApi")
}

dependencies {
	// Only include Android context for basic Android functionality
	compileOnly("androidx.appcompat:appcompat:1.6.1")
	compileOnly("androidx.fragment:fragment-ktx:1.6.2")
	compileOnly("com.google.android.material:material:1.11.0")

	api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

	// Logging goes through SLF4J, not android.util.Log. Not `compileOnly` like the Android artifacts
	// above: these classes run in the host's process, which already resolves slf4j-api and the IDE's
	// provider via :common -> :logger, so declaring it keeps the compile and runtime views the same.
	implementation(libs.tooling.slf4j)

	// Test dependencies
	testImplementation("junit:junit:4.13.2")

	// KeystoreSecretStore reaches for android.util.Base64 and a real SharedPreferences, so its tests
	// need the framework on the JVM.
	testImplementation(libs.tests.robolectric)
	testImplementation(libs.tests.google.truth)
}

tasks.register<Copy>("createPluginApiJar") {
	dependsOn("assembleRelease")
	from(layout.buildDirectory.file("intermediates/aar_main_jar/release/syncReleaseLibJars/classes.jar"))
	into(layout.buildDirectory.dir("libs"))
	rename { "plugin-api-1.0.0.jar" }
}
