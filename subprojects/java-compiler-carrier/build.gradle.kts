/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

import com.android.build.api.artifact.SingleArtifact
import com.itsaky.androidide.build.config.BuildConfig

plugins {
	id("com.android.application")
	id("kotlin-android")
}

android {
	namespace = "${BuildConfig.PACKAGE_NAME}.javacompilercarrier"

	// This APK is never installed -- only its classes.dex is read, via DexClassLoader, by
	// JavaCompilerLoader in lsp:java. Shrinking/obfuscating it would require porting over the
	// javac fork's own keep rules for no benefit (see ADFA-3604 -- the same "ship intact rather
	// than shrink" call already made for the analogous Kotlin carrier, ADFA-5010).
	buildTypes {
		release {
			isMinifyEnabled = false
			isShrinkResources = false
		}
	}
}

dependencies {
	implementation(projects.lsp.javaCompilerImpl)
}

// Exposes the release variant's real APK output directory as a Provider, for app's
// copyJavaCompilerCarrierToAssets task -- consuming this via AGP's variant artifacts API (rather
// than a hardcoded path guessing the output filename) ties Gradle's dependency tracking to the
// actual producing task (packageV8Release), not just dependsOn ordering, which intermittently
// raced the file's own write-to-disk on some CI runs (ADFA-5053).
androidComponents {
	onVariants(selector().withBuildType("release")) { variant ->
		extensions.extraProperties["releaseApkOutputDir"] = variant.artifacts.get(SingleArtifact.APK)
	}
}
