import com.itsaky.androidide.build.config.BuildConfig

plugins {
	id("com.android.library")
	id("kotlin-android")
}

android {
	namespace = "${BuildConfig.PACKAGE_NAME}.javac.services"

	buildTypes {
		release {
			isMinifyEnabled = false
		}
	}
}

dependencies {
	// Resident (see docs/adr/0012): kotlin-stdlib, :shared (ReflectUtils, VMUtils) and :logger
	// (ILogger) are all already loaded by the parent classloader -- implementation here would
	// duplicate their bytecode (and :common's androidx/guava graph, previously reached via
	// projects.common) into the isolated carrier dex. compileOnly for the same reason as the
	// block below. libs.google.guava was dropped entirely: this module's own code never
	// references it directly -- it only ever arrived transitively through :common's api(guava).
	compileOnly(libs.common.kotlin)
	compileOnly(projects.shared)
	compileOnly(projects.logger)

	// The actual javac fork this module wraps -- bundled with this module wherever it ends up
	// (isolated carrier, per ADFA-5053).
	api(libs.composite.jdkCompiler)

	// Resident (see docs/adr/0012): must never be duplicated into the isolated carrier, so
	// these are compileOnly even though this module's own code (ReusableContext.kt, etc.)
	// references their types directly.
	compileOnly(libs.composite.javaCompiler)
	compileOnly(projects.subprojects.javacFs)

	testImplementation(libs.tests.junit)
	testImplementation(libs.tests.google.truth)
	testImplementation(libs.tests.robolectric)
}
