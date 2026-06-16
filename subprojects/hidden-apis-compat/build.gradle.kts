plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.appdevforall.cotg.hidden.compat"
}

dependencies {
    compileOnly(projects.subprojects.hiddenApis)

    api(libs.rikka.hidden.compat)
}