@file:Suppress("UnstableApiUsage")

import com.itsaky.androidide.build.config.BuildConfig
import com.itsaky.androidide.plugins.extension.AssetSource
import com.itsaky.androidide.plugins.extension.JniLibAbi

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
val ojLibjdwpLibs =
	arrayOf(
		"dt_socket" to
			arrayOf(
				JniLibAbi.Aarch64 to "f6f9db19089c7124b9a7883a97d830195b15317de923ffc19e335b954514505b",
				JniLibAbi.Arm to "f1e7507fa5f59e6e99b9fe5edfe630ad051e4c8530c941d7c57aaf7b2c29c176",
			),
		"jdwp" to
			arrayOf(
				JniLibAbi.Aarch64 to "ed6a0c25e8b959491fc46c01b7e5f46c7a266730f8b5bfb49d1e5cdf4ad58c50",
				JniLibAbi.Arm to "05ef7d594a384ea4ad92b653c2d1f36dab3784dca569d582cb68511216e877f2",
			),
		"npt" to
			arrayOf(
				JniLibAbi.Aarch64 to "068d600ab401e607e149e60b44f812936e7379ebc41871b0d53ef490362262f5",
				JniLibAbi.Arm to "16395686d5238e9dd091003c30b7527af71808434bee092a637b8c99251edc31",
			),
	)

externalAssets {
	for ((lib, archs) in ojLibjdwpLibs) {
		for ((arch, sha256) in archs) {
			jniLib("$lib-$arch") {
				libName = lib
				abi = arch
				source =
					AssetSource.External(
						url = uri("$ojLibjdwpRepo/releases/download/$ojLibjdwpTag/${arch.abi}-lib$lib.so"),
						sha256Checksum = sha256,
					)
			}
		}
	}

	jarDependency("jdi-support") {
		configuration = "api"
		source =
			AssetSource.External(
				url = uri("$ojLibjdwpRepo/releases/download/$ojLibjdwpTag/jdi-support.jar"),
				sha256Checksum = "1ae447d08bd40b20abf270079cf59919d8575a2e8657fa936faf18678fe1ccc5",
			)
	}
}
