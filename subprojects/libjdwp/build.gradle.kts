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
    namespace = "${BuildConfig.packageName}.libjdwp"
    ndkVersion = BuildConfig.ndkVersion
}

val OJ_LIBJDWP_REPO = "https://github.com/appdevforall/oj-libjdwp"
val OJ_LIBJDWP_TAG = "v2025-05-19-a582a55"
val OJ_LIBJDWP_LIBS = arrayOf(
    "dt_socket" to arrayOf(
        JniLibAbi.Aarch64 to "f6f9db19089c7124b9a7883a97d830195b15317de923ffc19e335b954514505b",
        JniLibAbi.Arm to "f1e7507fa5f59e6e99b9fe5edfe630ad051e4c8530c941d7c57aaf7b2c29c176",
    ),
    "jdwp" to arrayOf(
        JniLibAbi.Aarch64 to "ed6a0c25e8b959491fc46c01b7e5f46c7a266730f8b5bfb49d1e5cdf4ad58c50",
        JniLibAbi.Arm to "05ef7d594a384ea4ad92b653c2d1f36dab3784dca569d582cb68511216e877f2",
    ),
    "npt" to arrayOf(
        JniLibAbi.Aarch64 to "068d600ab401e607e149e60b44f812936e7379ebc41871b0d53ef490362262f5",
        JniLibAbi.Arm to "16395686d5238e9dd091003c30b7527af71808434bee092a637b8c99251edc31",
    ),
)

externalAssets {
    for ((lib, archs) in OJ_LIBJDWP_LIBS) {
        for ((arch, sha256) in archs) {
            jni("${lib}-${arch}") {
                libName = lib
                abi = arch
                source = AssetSource.External(
                    url = uri("$OJ_LIBJDWP_REPO/releases/download/$OJ_LIBJDWP_TAG/${arch.abi}-lib${lib}.so"),
                    sha256Checksum = sha256
                )
            }
        }
    }
}
