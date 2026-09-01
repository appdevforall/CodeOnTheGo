import com.android.build.api.artifact.SingleArtifact
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

// Exposes the release variant's real APK output directory as a Provider, for app's
// copyKotlinCompilerCarrierToAssets task -- consuming this via AGP's variant artifacts API
// (rather than a hardcoded path guessing the output filename) ties Gradle's dependency
// tracking to the actual producing task (packageV8Release), not just dependsOn ordering, which
// intermittently races the file's own write-to-disk on some CI runs (same defect as
// ADFA-5053's copyJavaCompilerCarrierToAssets, fixed there the same way).
androidComponents {
	onVariants(selector().withBuildType("release")) { variant ->
		extensions.extraProperties["releaseApkOutputDir"] = variant.artifacts.get(SingleArtifact.APK)
	}
}
