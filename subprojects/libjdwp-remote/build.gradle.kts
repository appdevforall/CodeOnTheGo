import com.itsaky.androidide.build.config.BuildConfig

plugins {
    alias(libs.plugins.android.library)
    id("com.itsaky.androidide.build.external-assets")
}

android {
    namespace = "${BuildConfig.packageName}.libjdwp.remote"

    defaultConfig {
        minSdk = BuildConfig.minSdkForAppsBuiltInCogo
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
