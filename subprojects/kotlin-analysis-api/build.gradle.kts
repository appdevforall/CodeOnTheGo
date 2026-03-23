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
val ktAndroidTag = "v${ktAndroidVersion}-f1ac8b3"
val ktAndroidJarName = "analysis-api-standalone-embeddable-for-ide-${ktAndroidVersion}-SNAPSHOT.jar"

externalAssets {
	jarDependency("kt-android") {
		configuration = "api"
		source =
			AssetSource.External(
				url = uri("$ktAndroidRepo/releases/download/$ktAndroidTag/$ktAndroidJarName"),
				sha256Checksum = "8c7cad7e0905a861048cce000c3ef22d9ad05572b4f9a0830e0c0e0060ddd3c9",
			)
	}
}
