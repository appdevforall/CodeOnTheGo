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
	alias(libs.plugins.android.library)
	alias(libs.plugins.legacy.kapt)
}

android {
	namespace = "${BuildConfig.PACKAGE_NAME}.lsp.kotlin"
}

// This will be removed once we complete K2 compiler integration.
//androidComponents {
//	onVariants { variant ->
//		val stdlibGeneratorProject = project(":lsp:kotlin-stdlib-generator")
//		variant.sources.resources?.addGeneratedSourceDirectory(
//			stdlibGeneratorProject.tasks.named("generateStdlibIndex")
//		) {
//			stdlibGeneratorProject.objects.directoryProperty().also {
//				it.set(stdlibGeneratorProject.layout.buildDirectory.dir("generated-resources/stdlib"))
//			}
//		}
//	}
//}
//
//afterEvaluate {
//	tasks.matching { it.name.startsWith("process") && it.name.endsWith("JavaRes") }.configureEach {
//		dependsOn(":lsp:kotlin-stdlib-generator:generateStdlibIndex")
//	}
//}

kapt {
	arguments {
		arg("eventBusIndex", "${BuildConfig.PACKAGE_NAME}.events.LspKotlinEventsIndex")
	}
}

dependencies {
	kapt(projects.annotationProcessors)

	implementation(projects.lsp.kotlinCore)
	implementation(projects.lsp.api)
	implementation(projects.lsp.models)
	implementation(projects.eventbusEvents)
	implementation(projects.subprojects.kotlinAnalysisApi)
	implementation(projects.shared)
	implementation(projects.subprojects.projects)
	implementation(projects.subprojects.projectModels)

	implementation(libs.common.lsp4j)
	implementation(libs.common.jsonrpc)
	implementation(libs.common.kotlin)
	implementation(libs.common.kotlin.coroutines.core)
	implementation(libs.common.kotlin.coroutines.android)

	compileOnly(projects.common)

	testImplementation(projects.testing.lsp)
}
