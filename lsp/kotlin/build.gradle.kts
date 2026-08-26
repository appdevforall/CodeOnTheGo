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
	alias(libs.plugins.kotlin.compose)
}

android {
	namespace = "${BuildConfig.PACKAGE_NAME}.lsp.kotlin"

	// The refactoring bottom sheets are Compose (ADR 0009); they live here rather than in a UI
	// module because `editor` depends on this module, not the reverse (ADR 0012).
	buildFeatures {
		compose = true
	}

	kotlin.compilerOptions {
		freeCompilerArgs.addAll("-Xcontext-parameters")
	}
}

kapt {
	arguments {
		arg("eventBusIndex", "${BuildConfig.PACKAGE_NAME}.events.LspKotlinEventsIndex")
	}
}

dependencies {
	kapt(projects.annotationProcessors)

	implementation(projects.actions)
	implementation(projects.lsp.api)
	implementation(projects.lsp.jvmSymbolIndex)
	implementation(projects.lsp.kotlinApi)
	implementation(projects.lsp.models)
	implementation(projects.editorApi)
	implementation(projects.eventbusEvents)
	implementation(projects.shared)
	implementation(projects.subprojects.projects)
	implementation(projects.subprojects.projectModels)

	implementation(projects.commonCompose)

	implementation(platform(libs.compose.bom))
	implementation(libs.compose.runtime)
	implementation(libs.compose.ui)
	implementation(libs.compose.foundation)
	implementation(libs.compose.material3)
	implementation(libs.compose.ui.tooling.preview)
	debugImplementation(libs.compose.ui.tooling)

	implementation(libs.androidx.fragment.ktx)
	implementation(libs.androidx.lifecycle.runtime.ktx)
	implementation(libs.androidx.lifecycle.viewmodel.ktx)
	implementation(libs.androidx.lifecycle.runtime.compose)
	implementation(libs.google.material)

	implementation(libs.common.jsonrpc)
	implementation(libs.common.kotlin)
	implementation(libs.common.kotlin.coroutines.core)
	implementation(libs.common.kotlin.coroutines.android)
	implementation(libs.sentry.android.core)

	compileOnly(projects.common)

	testImplementation(projects.testing.lsp)
	testImplementation(libs.tests.kotlinx.coroutines)
}
