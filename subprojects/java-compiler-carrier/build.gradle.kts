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
