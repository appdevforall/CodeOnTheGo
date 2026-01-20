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

plugins {
	id("com.android.library")
	id("kotlin-android")
	kotlin("plugin.serialization")
}

android {
	namespace = "me.astrocoder.ktlsp"

	kotlinOptions {
		freeCompilerArgs += listOf(
			"-opt-in=kotlin.contracts.ExperimentalContracts"
		)
	}

	sourceSets {
		getByName("main") {
			java.srcDirs(
				"../../../../astro/experimentals/android-lsp/ktlsp/src/main/java"
			)
		}
		getByName("test") {
			java.srcDirs(
				"../../../../astro/experimentals/android-lsp/ktlsp/src/test/java"
			)
		}
	}
}

dependencies {
	implementation(libs.androidide.ts)
	implementation(libs.androidide.ts.kotlin)

	implementation(libs.common.lsp4j)
	implementation(libs.common.jsonrpc)

	implementation(libs.common.kotlin.coroutines.core)
	implementation(libs.common.kotlin.coroutines.android)

	implementation(libs.kotlinx.serialization.json)

	testImplementation(libs.tests.junit.jupiter)
}
