import com.itsaky.androidide.build.config.BuildConfig

plugins {
	id("com.android.library")
	id("kotlin-android")
}

android {
	namespace = "${BuildConfig.PACKAGE_NAME}.common"
}

dependencies {
	api(platform(libs.sora.bom))
	api(libs.common.editor)
	api(libs.common.lang3)
	api(libs.common.utilcode)
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

	api(projects.buildInfo)
	api(projects.eventbusAndroid)
	api(projects.eventbusEvents)
	api(projects.lexers)
	api(projects.resources)

	api(projects.shared)
	api(projects.logger)
	api(projects.resources)
	api(projects.subprojects.flashbar)
	implementation(libs.monitor)

	testImplementation(libs.tests.junit)
	testImplementation(libs.tests.google.truth)
	testImplementation(libs.tests.robolectric)

	// brotli4j
	implementation(libs.brotli4j)
}
