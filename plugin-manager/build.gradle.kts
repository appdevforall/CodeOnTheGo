

import com.itsaky.androidide.build.config.BuildConfig

plugins {
  id("com.android.library")
  id("kotlin-android")
}

android {
  namespace = "${BuildConfig.PACKAGE_NAME}.plugins.manager"
  
  lint {
    abortOnError = false
  }
}

dependencies {
  api(projects.pluginApi)
  
  implementation(projects.common)
  implementation(projects.logger)
  implementation(projects.lookup)
  implementation(projects.preferences)
  implementation(projects.shared)
  implementation(projects.subprojects.projects)
  
  implementation("androidx.appcompat:appcompat:1.6.1")
  implementation("com.google.code.gson:gson:2.10.1")
}