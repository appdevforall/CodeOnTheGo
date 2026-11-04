import com.itsaky.androidide.build.config.BuildConfig

plugins {
	id("com.android.library")
	id("kotlin-android")
	id("kotlin-parcelize")
	id("kotlin-kapt")
}

android {
	namespace = "${BuildConfig.PACKAGE_NAME}.projects"
}

kapt {
	arguments {
		arg("eventBusIndex", "${BuildConfig.PACKAGE_NAME}.events.ProjectsApiEventsIndex")
	}
}

dependencies {
	kapt(projects.annotationProcessors)
	kapt(libs.google.auto.service)

	api(projects.eventbus)
	api(projects.eventbusEvents)
	api(projects.lsp.indexing)
	api(projects.subprojects.projectModels)
	api(projects.subprojects.toolingApi)

	implementation(projects.common)
	implementation(projects.logger)
	implementation(projects.lookup)
	implementation(projects.shared)
	implementation(projects.subprojects.javacServices)
	implementation(projects.subprojects.xmlUtils)

	implementation(libs.common.io)
	implementation(libs.common.kotlin.coroutines.android)
	implementation(libs.google.auto.service.annotations)
	implementation(libs.google.guava)

	// Test-framework deps are declared directly rather than via projects.testing.tooling:
	// that module pulls in :testing:common, which shares Gradle coordinates (group:name)
	// with :common and silently replaces it on the unit-test runtime classpath -- every
	// :common class then fails to load under Robolectric.
	testImplementation(libs.tests.junit)
	testImplementation(libs.tests.robolectric)
	testImplementation(libs.tests.google.truth)
	testImplementation(libs.tests.mockk)
}
