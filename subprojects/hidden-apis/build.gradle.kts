import com.itsaky.androidide.build.config.BuildConfig

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "${BuildConfig.PACKAGE_NAME}.hidden_apis"
}

dependencies {
    annotationProcessor(libs.rikka.refine.annotation.processor)
    compileOnly(libs.rikka.refine.annotation)
}