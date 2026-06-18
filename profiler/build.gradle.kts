import com.itsaky.androidide.build.config.BuildConfig
import com.itsaky.androidide.plugins.conf.configureProtoc

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.protobuf)
}

android {
	namespace = "${BuildConfig.PACKAGE_NAME}.profiler"

    buildFeatures {
        compose = true
    }
}

// simpleperf's report-sample protobuf is parsed here; generate Java-lite from src/main/proto.
configureProtoc(protobuf = protobuf, protocVersion = libs.versions.protobuf.asProvider())

protobuf {
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                // The `java` builtin isn't pre-registered for Android library modules, so create it.
                maybeCreate("java").option("lite")
            }
        }
    }
}

dependencies {
	api(projects.actions)
    implementation(projects.logger)
	implementation(projects.subprojects.privilegedServices)

	api(libs.androidx.annotation)
	api(libs.androidx.fragment)
    api(libs.androidx.fragment.ktx)
	api(libs.androidx.lifecycle.viewmodel.ktx)
	api(libs.androidx.lifecycle.runtime.ktx)

    implementation(libs.shark.android)
    implementation(libs.shark.hprof)
    implementation(libs.shark.graph)

    implementation(libs.google.protobuf.java)

    implementation(libs.rikka.hidden.compat)
    implementation(libs.rikka.hidden.stub)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.google.material)
    implementation(libs.androidx.core.ktx)
    debugImplementation(libs.compose.ui.tooling)
}
