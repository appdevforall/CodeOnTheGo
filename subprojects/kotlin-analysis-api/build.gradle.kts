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
val ktAndroidTag = "v$ktAndroidVersion-b006e8c"
val ktAndroidJarName = "analysis-api-standalone-embeddable-for-ide-$ktAndroidVersion-SNAPSHOT.jar"

externalAssets {
	jarDependency("kt-android") {
		configuration = "api"
		source =
			AssetSource.External(
				url = uri("$ktAndroidRepo/releases/download/$ktAndroidTag/$ktAndroidJarName"),
				sha256Checksum = "0793c08f666157aa091055a498f9c231a42ced31ad5a019c9875cf814e719bbb",
			)
	}
}
