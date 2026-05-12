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
val ktAndroidTag = "v${ktAndroidVersion}-ac7aa4b"
val ktAndroidJarName = "analysis-api-standalone-embeddable-for-ide-${ktAndroidVersion}-SNAPSHOT.jar"

externalAssets {
	jarDependency("kt-android") {
		configuration = "api"
		source =
			AssetSource.External(
				url = uri("$ktAndroidRepo/releases/download/$ktAndroidTag/$ktAndroidJarName"),
				sha256Checksum = "810a93e148c2fa697a7aaf6e3c2d3356146602ed8c937ba7faf7685348f988f6",
			)
	}
}
