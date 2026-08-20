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
	id("com.android.library")
	id("kotlin-android")
	alias(libs.plugins.kotlin.compose)
}

android {
	namespace = "${BuildConfig.PACKAGE_NAME}.lsp.ui"

	buildFeatures {
		compose = true
	}
}

dependencies {

	// api, not implementation: a language server's action calls show(activity, ...) and receives a
	// selection, so it needs FragmentActivity and the contract types on its own compile classpath.
	api(libs.androidx.fragment.ktx)
	api(projects.resources)

	implementation(projects.commonCompose)
	implementation(platform(libs.compose.bom))
	implementation(libs.compose.runtime)
	implementation(libs.compose.ui)
	implementation(libs.compose.foundation)
	implementation(libs.compose.material3)
	implementation(libs.androidx.lifecycle.runtime.ktx)
	implementation(libs.androidx.lifecycle.viewmodel.ktx)
	implementation(libs.androidx.lifecycle.runtime.compose)
	implementation(libs.google.material)

	testImplementation(libs.tests.junit)
	testImplementation(libs.tests.google.truth)
}
