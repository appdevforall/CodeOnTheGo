plugins {
    id("com.android.library")
    id("dev.rikka.tools.refine")
}

android {
    namespace = "rikka.shizuku.server"
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core)
    implementation(libs.rikkax.parcelablelist)

    api(projects.subprojects.shizukuAidl)
    api(projects.subprojects.shizukuShared)
    api(projects.subprojects.shizukuRish)

    implementation(libs.rikka.refine.runtime)
    implementation(libs.rikka.hidden.compat)
    compileOnly(libs.rikka.hidden.stub)
}
