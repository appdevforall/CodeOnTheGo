

import com.itsaky.androidide.build.config.BuildConfig

plugins {
  id("com.android.library")
  id("kotlin-android")
}

android {
  namespace = "${BuildConfig.PACKAGE_NAME}.plugins.manager"

  compileSdk = BuildConfig.COMPILE_SDK

  defaultConfig {
    minSdk = BuildConfig.MIN_SDK
  }

  compileOptions {
    sourceCompatibility = BuildConfig.JAVA_VERSION
    targetCompatibility = BuildConfig.JAVA_VERSION
  }

  kotlinOptions {
    jvmTarget = BuildConfig.JAVA_VERSION.toString()
  }

  lint {
    abortOnError = false
  }
}

dependencies {
  api(projects.pluginApi)

  implementation(projects.actions)
  implementation(projects.common)
  implementation(projects.logger)
  implementation(projects.lookup)
  implementation(projects.preferences)
  implementation(projects.resources)
  implementation(projects.idetooltips)
  implementation(projects.shared)
  implementation(projects.subprojects.projects)

  implementation(libs.androidx.appcompat)
  implementation(libs.gson.v2101)
}