
import com.itsaky.androidide.build.config.BuildConfig

plugins {
  id("com.android.library")
  id("kotlin-android")
  id("kotlin-parcelize")
}

android {
  namespace = "${BuildConfig.PACKAGE_NAME}.plugins.api"
}

dependencies {
  // Only include Android context for basic Android functionality
  compileOnly("androidx.appcompat:appcompat:1.6.1")
  compileOnly("androidx.fragment:fragment-ktx:1.6.2")
  compileOnly("com.google.android.material:material:1.11.0")
}