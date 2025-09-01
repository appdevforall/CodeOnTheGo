plugins {
    id("com.android.application")
    id("dev.rikka.tools.refine")
}

android {
    namespace = "rikka.shizuku.shell"
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.rikka.hidden.compat)
    compileOnly(libs.rikka.hidden.stub)
}
