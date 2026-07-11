import com.itsaky.androidide.build.config.BuildConfig
import com.itsaky.androidide.plugins.extension.AssetSource

plugins {
	alias(libs.plugins.android.library)
	id("com.itsaky.androidide.build.external-assets")
}

android {
	namespace = "${BuildConfig.PACKAGE_NAME}.kt.analysis"
}

val ktAndroidRepo = "https://github.com/appdevforall/kotlin-android"
val ktAndroidVersion = "2.3.255"
val ktAndroidTag = "v${ktAndroidVersion}-06e90ae"
val ktAndroidJarName = "analysis-api-standalone-embeddable-for-ide-${ktAndroidVersion}-SNAPSHOT.jar"

externalAssets {
	jarDependency("kt-android") {
		configuration = "api"
		source =
			AssetSource.External(
				url = uri("$ktAndroidRepo/releases/download/$ktAndroidTag/$ktAndroidJarName"),
				sha256Checksum = "2069ed685dafd6eed36ebe242004ed5e24e28360293117323e2c988afefa6767",
			)
		excludeEntryPrefixes = listOf(
			// Kotlin/JS backend — not needed in an Android IDE
			"org/jetbrains/kotlin/js/",
			"META-INF/ir.serialization.js.kotlin_module",
			"META-INF/checkers.js.kotlin_module",
			"META-INF/compiler.common.js.kotlin_module",
			"META-INF/js.ast.kotlin_module",
			"META-INF/js.config.kotlin_module",
			"META-INF/js.frontend.common.kotlin_module",
			"META-INF/js.frontend.kotlin_module",
			"META-INF/js.parser.kotlin_module",
			"META-INF/js.serializer.kotlin_module",
			// Kotlin/Native (konan) backend — not needed in an Android IDE
			"org/jetbrains/kotlin/konan/",
			"META-INF/checkers.native.kotlin_module",
			"META-INF/compiler.common.native.kotlin_module",
			"META-INF/decompiler-native.kotlin_module",
			"META-INF/fir-native.kotlin_module",
			"META-INF/frontend.native.kotlin_module",
			"META-INF/ir.serialization.native.kotlin_module",
			"META-INF/kotlin-native-utils.kotlin_module",
		)
	}
}
