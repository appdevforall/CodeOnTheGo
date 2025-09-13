@file:Suppress("UnstableApiUsage")

import com.itsaky.androidide.build.config.BuildConfig
import com.itsaky.androidide.plugins.extension.AssetSource

plugins {
	id("com.android.library")
	id("kotlin-android")
	id("com.itsaky.androidide.build.external-assets")
}

android {
	namespace = "${BuildConfig.PACKAGE_NAME}.libjdwp"
	ndkVersion = BuildConfig.NDK_VERSION
}

val ojLibjdwpRepo = "https://github.com/appdevforall/oj-libjdwp"
val ojLibjdwpTag = "v2025-05-19-a582a55"

externalAssets {
	jarDependency("jdi-support") {
		configuration = "api"
		source =
			AssetSource.External(
				url = uri("$ojLibjdwpRepo/releases/download/$ojLibjdwpTag/jdi-support.jar"),
				sha256Checksum = "1ae447d08bd40b20abf270079cf59919d8575a2e8657fa936faf18678fe1ccc5",
			)
	}
}
