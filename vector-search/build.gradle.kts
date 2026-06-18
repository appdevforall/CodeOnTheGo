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
}

android {
	namespace = "${BuildConfig.PACKAGE_NAME}.vectorsearch"
	compileSdk = BuildConfig.COMPILE_SDK

	defaultConfig {
		minSdk = BuildConfig.MIN_SDK
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}

	kotlin {
		compilerOptions {
			jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
		}
	}
}

dependencies {
	implementation(project(":llama-api"))
	implementation(project(":lsp:indexing"))
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

	// Test dependencies
	testImplementation(libs.tests.junit)
	testImplementation(libs.tests.kotlinx.coroutines)
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.20")
	testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.20")
}
