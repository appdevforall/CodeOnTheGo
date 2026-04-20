import com.itsaky.androidide.build.config.BuildConfig

plugins {
	alias(libs.plugins.android.library)
	id("kotlin-android")
}

android {
	namespace = "${BuildConfig.PACKAGE_NAME}.lsp.java.indexing"
}

dependencies {
	api(libs.google.protobuf.java)
	api(libs.google.protobuf.kotlin)
	api(libs.kotlinx.coroutines.core)
	api(libs.kotlinx.metadata)

	api(projects.common)
	api(projects.logger)
	api(projects.lsp.indexing)
	api(projects.lsp.jvmSymbolModels)
	api(projects.subprojects.kotlinAnalysisApi)
	api(projects.subprojects.projects)
	api(projects.lsp.kotlinCore)
}
