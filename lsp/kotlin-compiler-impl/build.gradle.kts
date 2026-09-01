import com.itsaky.androidide.build.config.BuildConfig

plugins {
	id("com.android.library")
	id("kotlin-android")
}

android {
	namespace = "${BuildConfig.PACKAGE_NAME}.lsp.kotlin.compilerimpl"

	kotlin.compilerOptions {
		freeCompilerArgs.addAll("-Xcontext-parameters")
	}
}

dependencies {
	// The one dependency this module actually bundles -- removing it from every resident
	// module's classpath is the whole point of ADFA-5010. Everything else below is
	// compileOnly: those types are already loaded by the parent (main app) classloader by
	// the time this module's carrier dex runs, so DexClassLoader's normal parent-first
	// delegation resolves them there -- compileOnly just avoids also bundling dead-weight
	// duplicate copies into the carrier dex.
	implementation(projects.subprojects.kotlinAnalysisApi)

	// The Kotlin LSP's tests live here, with the code they exercise (ADFA-5010 stage merge): the
	// Analysis API is in this module, so lsp/kotlin cannot compile a fixture that touches it.
	// compileOnly does not reach the test classpath, so the three bridges are repeated as
	// testImplementation rather than inherited.
	testImplementation(projects.testing.lsp)
	testImplementation(projects.lsp.api)
	testImplementation(projects.lsp.kotlin)
	testImplementation(projects.lsp.kotlinApi)
	testImplementation(libs.tests.kotlinx.coroutines)

	compileOnly(projects.lsp.api)
	compileOnly(projects.lsp.kotlin)
	compileOnly(projects.lsp.kotlinApi)
	compileOnly(projects.lsp.models)
	compileOnly(projects.lsp.jvmSymbolIndex)
	compileOnly(projects.editorApi)
	compileOnly(projects.actions)
	compileOnly(projects.shared)
	compileOnly(projects.common)
	compileOnly(projects.subprojects.projects)
	compileOnly(projects.subprojects.projectModels)
	compileOnly(projects.resources)
	compileOnly(projects.idetooltips)

	compileOnly(libs.common.kotlin)
	compileOnly(libs.common.kotlin.coroutines.core)
	compileOnly(libs.common.kotlin.coroutines.android)
	compileOnly(libs.sentry.android.core)
}
