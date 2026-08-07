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
	id("kotlin-kapt")
}

android {
	namespace = "${BuildConfig.PACKAGE_NAME}.lsp.java.impl"

	buildTypes {
		release {
			isMinifyEnabled = false
		}
	}
}

kapt {
	arguments {
		arg("eventBusIndex", "${BuildConfig.PACKAGE_NAME}.events.LspJavaImplEventsIndex")
	}
}

dependencies {
	kapt(projects.annotationProcessors)

	implementation(libs.androidide.ts)
	implementation(libs.androidide.ts.java)
	implementation(platform(libs.sora.bom))
	implementation(libs.common.editor)
	implementation(libs.common.javaparser)
	implementation(libs.androidx.annotation)
	implementation(libs.google.guava)
	implementation(libs.google.gson)
	implementation(libs.androidx.core.ktx)
	implementation(libs.common.kotlin)

	// The actual javac fork -- this is the payload this module exists to isolate.
	implementation(libs.composite.javac)
	implementation(libs.composite.javapoet)
	implementation(projects.subprojects.javacServices)

	// Resident (kept in lsp/java -- not part of javac's dex bloat); see lsp/java/build.gradle.kts.
	compileOnly(libs.composite.googleJavaFormat)

	// Resident modules, visible at compile time but never bundled into this module's own
	// output -- `implementation` here would duplicate their classes into the isolated
	// carrier dex alongside the identical resident copies, breaking type identity across
	// the DexClassLoader boundary (see docs/adr/0012).
	compileOnly(libs.androidx.appcompat)
	compileOnly(libs.google.material)
	compileOnly(projects.actions)
	compileOnly(projects.common)
	compileOnly(projects.editorApi)
	compileOnly(projects.resources)
	compileOnly(projects.idetooltips)
	compileOnly(projects.lsp.api)
	compileOnly(projects.lsp.java)
	compileOnly(projects.lsp.javaApi)
	compileOnly(projects.lsp.jvmSymbolIndex)
	compileOnly(projects.subprojects.javacFs)
	compileOnly(projects.subprojects.projects)

	testImplementation(projects.testing.lsp)
	// The moved tests construct/drive a resident JavaLanguageServer directly; compileOnly (main
	// sourceset) doesn't extend to the test compile classpath, so this needs its own entry.
	testImplementation(projects.lsp.java)
}
