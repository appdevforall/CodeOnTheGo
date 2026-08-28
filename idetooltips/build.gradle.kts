import com.itsaky.androidide.build.config.BuildConfig

plugins {
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.android.library)
}

android {
	namespace = "${BuildConfig.PACKAGE_NAME}.idetooltips"
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
	implementation(libs.google.gson)
	implementation(libs.google.guava)
	implementation(libs.androidx.constraintlayout)
	implementation(libs.google.material)

	implementation(projects.resources)
	implementation(projects.common)

	testImplementation(libs.tests.junit)
}
