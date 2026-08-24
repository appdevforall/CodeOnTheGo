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
	// javac-services' (and this module's own) compileOnly androidx-heavy deps (sora-editor,
	// appcompat, material, lsp:indexing, etc.) pull androidx.annotation/kotlin-stdlib/
	// org.jetbrains:annotations at high versions onto the compile classpath, but being compileOnly
	// they are absent from the runtime classpath -- which sees viewbinding's much lower transitive
	// pins for the same artifacts instead. AGP's compile/runtime consistency check cannot reconcile
	// the two. Constraining these three (not adding a dependency: each is already reachable via
	// viewbinding, just at the wrong version) harmonizes both classpaths at the version used
	// everywhere else, without adding an edge. All three are a few KB of interfaces and annotations
	// with no resources, unlike the androidx.core duplication this compileOnly effort exists to
	// avoid -- see ADFA-5068.
	//
	// viewbinding is not AGP's doing: our own convention plugin turns buildFeatures.viewBinding on
	// for every module (AndroidModuleConf.kt). Turning it off here and in javac-services -- neither
	// has a layout -- was tried and is not sufficient: v8Release still fails with
	// androidx.annotation:{strictly 1.0.0}, because the pin arrives through the runtime graph from
	// the modules that still enable viewBinding. Removing these constraints needs that project-wide,
	// not per-module.
	constraints {
		implementation(libs.androidx.annotation)
		implementation(libs.common.kotlin.stdlib)
		implementation(libs.common.jetbrains.annotations)
	}

	kapt(projects.annotationProcessors)

	// Resident (bundled in the main app dex via editor/editor-api/lsp:java/etc.) -- like the
	// appcompat/material block below, `implementation` here would duplicate these, including
	// their native .so payloads, into the isolated carrier dex alongside the identical resident
	// copies, breaking type identity across the DexClassLoader boundary (see docs/adr/0012).
	// The carrier's DexClassLoader resolves them from its parent (the resident classloader)
	// instead.
	compileOnly(libs.androidide.ts)
	compileOnly(libs.androidide.ts.java)
	implementation(platform(libs.sora.bom))
	compileOnly(libs.common.editor)
	implementation(libs.common.javaparser)
	compileOnly(libs.androidx.annotation)
	compileOnly(libs.google.guava)
	compileOnly(libs.google.gson)
	compileOnly(libs.androidx.core.ktx)
	compileOnly(libs.common.kotlin)
	// Resident (bundled via common's `api`) -- needed to translate CancelAbort (isolated-only,
	// never classloader-identity-safe to check from resident code) into CancellationException
	// (this same, single resident copy, safe to check from either side) before it crosses back
	// out of JavaCompilerSessionImpl. compileOnly for the same reason as the block above.
	compileOnly(libs.common.kotlin.coroutines.core)

	// The actual javac fork -- this is the payload this module exists to isolate. NOT
	// libs.composite.javac (the aggregate): that also pulls in java-compiler, which must stay
	// resident-only (see docs/adr/0012) -- duplicating it here would break type identity
	// across the DexClassLoader boundary for CacheFSInfo/Context/etc.
	implementation(libs.composite.jdkCompiler)
	implementation(projects.subprojects.javacServices)

	// google-java-format uses javac's own parser internally, so -- like javac itself -- it's
	// heavy and isolated, not resident: JavaServerSettings (resident) exposes only a plain
	// code-style int, and CodeFormatProvider builds the real JavaFormatterOptions here.
	implementation(libs.composite.googleJavaFormat)

	// Resident (java-compiler: jdkx.*/zipfs2.* + the relocated fs-adjacent leaf classes) --
	// this module's own source (SourceFileObject, etc.) references these types directly.
	compileOnly(libs.composite.javaCompiler)

	// javapoet (used by JavaPoetUtils.kt's code-generation actions) is lightweight and stays
	// fully resident -- templates-api/templates-impl need it unconditionally for the "New
	// Project" wizard, unrelated to javac. compileOnly here avoids duplicating it into the
	// carrier alongside that resident copy.
	compileOnly(libs.composite.javapoet)

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
	// The moved tests construct/drive a resident JavaLanguageServer directly, and reference
	// java-compiler types (jdkx.tools.Diagnostic, etc.) directly too; compileOnly (main
	// sourceset) doesn't extend to the test compile classpath, so these need their own entries.
	testImplementation(projects.lsp.java)
	testImplementation(libs.composite.javaCompiler)
	// JavaSelectionProviderTest references sora-editor's Content directly; same compileOnly
	// test-classpath gap as above.
	testImplementation(libs.common.editor)
}
