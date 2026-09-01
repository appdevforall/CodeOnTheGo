import com.itsaky.androidide.build.config.BuildConfig

plugins {
	id("com.android.library")
	id("kotlin-android")
}

android {
	namespace = "${BuildConfig.PACKAGE_NAME}.lsp.kotlin.api"

	kotlin.compilerOptions {
		freeCompilerArgs.addAll("-Xcontext-parameters")
	}
}

dependencies {
	api(projects.lsp.api)
	api(projects.lsp.indexing)
	api(projects.lsp.jvmSymbolIndex)
	api(projects.lsp.models)
	api(projects.shared)
	api(projects.subprojects.projects)

	implementation(projects.common)
	implementation(libs.common.kotlin)
	implementation(libs.common.kotlin.coroutines.core)
}
