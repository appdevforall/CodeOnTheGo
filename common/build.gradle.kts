import com.itsaky.androidide.build.config.BuildConfig

plugins {
	id("com.android.library")
	id("kotlin-android")
}

android {
	namespace = "${BuildConfig.PACKAGE_NAME}.common"
}

kotlin {
	compilerOptions {
		// This module's classes ship in the plugin-api coordinate that on-device plugins
		// compile against, so emit metadata the on-device Kotlin (1.9.22) can read (<= 2.0.0).
		apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
		languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
	}
}

dependencies {
	compileOnly(libs.composite.javac)

	api(platform(libs.sora.bom))
	api(libs.common.editor)
	api(libs.common.lang3)
	api(libs.composite.constants)
	api(libs.google.guava)
	api(libs.google.material)

	api(libs.androidx.appcompat)
	api(libs.androidx.collection)
	api(libs.androidx.preference)
	api(libs.androidx.vectors)
	api(libs.androidx.animated.vectors)

	api(libs.androidx.core.ktx)
	api(libs.common.kotlin)
	api(libs.kotlinx.coroutines.core)

	api(projects.buildInfo)
	api(projects.eventbusAndroid)
	api(projects.eventbusEvents)
	api(projects.lexers)
	api(projects.pluginApi)
	api(projects.resources)

	api(projects.shared)
	api(projects.logger)
	api(projects.resources)
	api(projects.subprojects.flashbar)
	implementation(libs.monitor)

	testImplementation(projects.testing.common)
	testImplementation(libs.tests.kotlinx.coroutines)
	testImplementation(libs.tests.google.truth)
	testImplementation(libs.tests.mockk)
	androidTestImplementation(projects.testing.android)

	// brotli4j
	implementation(libs.brotli4j)

	// Documentation content: Brotli-decoded rows above, Pebble-rendered pages, gson for their
	// template context. Moved down from `app` with the shared content source (ADFA-5176), so both
	// the web server and the in-process WebView path render the same way.
	implementation(libs.pebble)
	implementation(libs.google.gson)
}
