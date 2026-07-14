package com.demo

import android.app.Activity
import android.os.Bundle
import app.payload.Main

/**
 * Thin launcher Activity so this project is a REAL, installable app for the Run button's
 * first-time FULL Gradle build. It just delegates to the same [Main.render] contract the
 * mini-stubby shell hot-loads in the fast loop — so one body of code serves both faces:
 *   - full Gradle build  -> normal APK, MainActivity -> Main.render(this)
 *   - fast loop          -> shell loads app.payload.Main.render(activity) directly
 */
class MainActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(Main.render(this))
  }
}
