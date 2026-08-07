import com.itsaky.androidide.build.config.BuildConfig

plugins {
	id("com.android.application")
	id("kotlin-android")
}

android {
	namespace = "${BuildConfig.PACKAGE_NAME}.kotlincompilercarrier"

	// This APK is never installed -- only its classes.dex is read, via DexClassLoader, by
	// KotlinCompilerLoader in lsp:kotlin. Shrinking/obfuscating it would require porting over
	// kotlin-analysis-api's ~350 consumer-rules.pro keep rules for no benefit (see ADFA-3604 --
	// the same "ship intact rather than shrink" call already made for this dependency graph).
	buildTypes {
		release {
			isMinifyEnabled = false
			isShrinkResources = false
		}
	}
}

dependencies {
	implementation(projects.lsp.kotlinCompilerImpl)
}
