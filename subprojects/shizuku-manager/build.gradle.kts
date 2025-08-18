@file:Suppress("UnstableApiUsage")

import com.itsaky.androidide.build.config.BuildConfig

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("dev.rikka.tools.refine")
    id("dev.rikka.tools.materialthemebuilder")
}

android {
    namespace = "moe.shizuku.manager"
    ndkVersion = BuildConfig.ndkVersion

    defaultConfig {
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
            }
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.31.0+"
        }
    }

    packaging {
        resources.excludes += "**"
    }
}

dependencies {
    implementation(libs.common.kotlin.coroutines.android)

    implementation(projects.subprojects.shizukuServer)
    implementation(projects.subprojects.shizukuRish)
    implementation(projects.subprojects.shizukuStarter)
    implementation(projects.subprojects.shizukuApi)
    implementation(projects.subprojects.shizukuProvider)

    implementation(libs.rikka.hidden.compat)
    compileOnly(libs.rikka.hidden.stub)

    implementation(libs.libsu.core)
    implementation(libs.common.hiddenApiBypass)
    implementation(libs.bcpkix.jdk18on)

    //noinspection UseTomlInstead
    implementation ("org.lsposed.libcxx:libcxx:${BuildConfig.ndkVersion}")
}
