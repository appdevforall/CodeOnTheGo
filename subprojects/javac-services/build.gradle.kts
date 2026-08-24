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

	// This module has no layouts, and the convention plugin turns viewBinding on for every module
	// (AndroidModuleConf.kt). Leaving it on pulled androidx.databinding:viewbinding in, whose
	// transitive androidx.annotation:1.0.0 was the only thing making the imports below compile --
	// and whose low version was what java-compiler-impl's constraints block existed to harmonize.
	buildFeatures {
		viewBinding = false
	}
}

dependencies {
	// Resident (see docs/adr/0012): kotlin-stdlib, :shared (ReflectUtils, VMUtils) and :logger
	// (ILogger) are all already loaded by the parent classloader -- implementation here would
	// duplicate their bytecode into the isolated carrier dex. compileOnly for the same reason as
	// the block below. libs.google.guava was dropped because this module's own code never
	// references it directly; it only ever arrived transitively through :common's api(guava).
	//
	// That does *not* mean guava stays out of the carrier. It is still on
	// :subprojects:java-compiler-carrier's v8ReleaseRuntimeClasspath, reached through
	// :build-deps:google-java-format and javaparser-symbol-solver-core -- neither of which goes via
	// :common, so neither was affected by this change (hal, #1643). Keeping it out for real means
	// deciding what to do about those two edges, and google-java-format needs guava at runtime, so
	// it is not a scope change. The compileOnly(libs.google.guava) in java-compiler-impl upholds an
	// invariant the build does not currently hold.
	compileOnly(libs.common.kotlin)
	compileOnly(projects.shared)
	compileOnly(projects.logger)

	// Declared, not inherited: three sources here import androidx.annotation, and dropping
	// implementation(projects.common) removed the last declared provider. It compiled anyway only
	// because viewBinding (now off, above) dragged in androidx.annotation:1.0.0 transitively --
	// an accident of an unrelated build feature.
	compileOnly(libs.androidx.annotation)

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
