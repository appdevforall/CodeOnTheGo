import com.itsaky.androidide.build.config.BuildConfig

plugins {
	alias(libs.plugins.android.library)
	id("com.itsaky.androidide.build.external-assets")
}

android {
	namespace = "${BuildConfig.PACKAGE_NAME}.kt.analysis"
}

//val ktAndroidRepo = "https://github.com/appdevforall/kotlin-android"
//val ktAndroidVersion = "2.3.255"
//val ktAndroidTag = "v${ktAndroidVersion}-172a7e7"
//val ktAndroidJarName = "analysis-api-standalone-embeddable-for-ide-${ktAndroidVersion}-SNAPSHOT.jar"
//
//externalAssets {
//	jarDependency("kt-android") {
//		configuration = "api"
//		source =
//			AssetSource.External(
//				url = uri("$ktAndroidRepo/releases/download/$ktAndroidTag/$ktAndroidJarName"),
//				sha256Checksum = "ee52466a893ed7261fb542a259cb469227aa8059cf7a36b5d1b41897a7e5bb08",
//			)
//	}
//}


dependencies {
	api(files("/var/mnt/data/dev/work/adfa/cogo/kt/kotlin-android/prepare/ide-plugin-dependencies/analysis-api-standalone-embeddable-for-ide/build/libs/analysis-api-standalone-embeddable-for-ide-2.3.255-SNAPSHOT.jar"))
}
