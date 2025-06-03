@file:Suppress("UnstableApiUsage")

import ch.qos.logback.core.util.EnvUtil
import com.itsaky.androidide.build.config.BuildConfig
import com.itsaky.androidide.desugaring.ch.qos.logback.core.util.DesugarEnvUtil
import com.itsaky.androidide.desugaring.utils.JavaIOReplacements.applyJavaIOReplacements
import com.itsaky.androidide.plugins.AndroidIDEAssetsPlugin
import okio.Path.Companion.toPath
import java.nio.file.Files
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.reflect.jvm.javaMethod

import java.net.URL
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.json.JSONObject

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream


plugins {
  id("com.android.application")
  id("kotlin-android")
  id("kotlin-kapt")
  id("kotlin-parcelize")
  id("androidx.navigation.safeargs.kotlin")
//  id("io.sentry.android.gradle") version "4.2.0"

  id("com.itsaky.androidide.desugaring")
}

apply {
  plugin(AndroidIDEAssetsPlugin::class.java)
}

buildscript {
  dependencies {
    classpath(libs.logging.logback.core)
    classpath(libs.composite.desugaringCore)
    classpath(libs.org.json)
  }
}

android {
  namespace = BuildConfig.packageName

  defaultConfig {
    applicationId = BuildConfig.packageName
    vectorDrawables.useSupportLibrary = true

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    testInstrumentationRunnerArguments["androidx.test.orchestrator.ENABLE"] = "true"
  }

  testOptions {
    execution = "ANDROIDX_TEST_ORCHESTRATOR"
  }

  androidResources {
    generateLocaleConfig = true
  }

  buildTypes {
    release {
      isShrinkResources = true
    }
  }

  android {
    sourceSets {
      getByName("androidTest") {
        manifest.srcFile("src/androidTest/AndroidManifest.xml")
      }
    }
  }

  lint {
    abortOnError = false
    disable.addAll(arrayOf("VectorPath", "NestedWeights", "ContentDescription", "SmallSp"))
  }

  installation {
    //installOptions("-timeout", "420000") // 5 minutes (in milliseconds)
  }
}

kapt { arguments { arg("eventBusIndex", "${BuildConfig.packageName}.events.AppEventsIndex") } }

desugaring {
  replacements {
    includePackage(
      "org.eclipse.jgit",
      "ch.qos.logback.classic.util",
    )

    applyJavaIOReplacements()

    // EnvUtil.logbackVersion() uses newer Java APIs like Class.getModule() which is not available
    // on Android. We replace the method usage with DesugarEnvUtil.logbackVersion() which
    // always returns null
    replaceMethod(
      EnvUtil::logbackVersion.javaMethod!!,
      DesugarEnvUtil::logbackVersion.javaMethod!!
    )
  }
}

dependencies {
    //debugImplementation(libs.common.leakcanary)

  // Annotation processors
  kapt(libs.common.glide.ap)
  kapt(libs.google.auto.service)
  kapt(projects.annotationProcessors)
  kapt(libs.room.compiler)

    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))    

  implementation(libs.common.editor)
  implementation(libs.common.utilcode)
  implementation(libs.common.glide)
  implementation(libs.common.jsoup)
  implementation(libs.common.kotlin.coroutines.android)
  implementation(libs.common.retrofit)
  implementation(libs.common.retrofit.gson)
  implementation(libs.common.charts)
  implementation(libs.common.hiddenApiBypass)

  implementation(libs.google.auto.service.annotations)
  implementation(libs.google.gson)
  implementation(libs.google.guava)

  //Room
  implementation(libs.room.ktx)

  // Git
  implementation(libs.git.jgit)

  // AndroidX
  implementation(libs.androidx.splashscreen)
  implementation(libs.androidx.annotation)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.cardview)
  implementation(libs.androidx.constraintlayout)
  implementation(libs.androidx.coordinatorlayout)
  implementation(libs.androidx.drawer)
  implementation(libs.androidx.grid)
  implementation(libs.androidx.nav.fragment)
  implementation(libs.androidx.nav.ui)
  implementation(libs.androidx.preference)
  implementation(libs.androidx.recyclerview)
  implementation(libs.androidx.transition)
  implementation(libs.androidx.vectors)
  implementation(libs.androidx.animated.vectors)
  implementation(libs.androidx.work)
  implementation(libs.androidx.work.ktx)
  implementation(libs.google.material)
  implementation(libs.google.flexbox)

  // Kotlin
  implementation(libs.androidx.core.ktx)
  implementation(libs.common.kotlin)

  // Dependencies in composite build
  implementation(libs.composite.appintro)
  implementation(libs.composite.desugaringCore)
  implementation(libs.composite.javapoet)

  // Local projects here
  implementation(projects.actions)
  implementation(projects.buildInfo)
  implementation(projects.common)
  implementation(projects.editor)
  implementation(projects.termux.termuxApp)
  implementation(projects.termux.termuxView)
  implementation(projects.termux.termuxEmulator)
  implementation(projects.termux.termuxShared)
  implementation(projects.eventbus)
  implementation(projects.eventbusAndroid)
  implementation(projects.eventbusEvents)
  implementation(projects.gradlePluginConfig)
  implementation(projects.idestats)
  implementation(projects.subprojects.aaptcompiler)
  implementation(projects.subprojects.javacServices)
  implementation(projects.subprojects.libjdwp)
  implementation(projects.subprojects.xmlUtils)
  implementation(projects.subprojects.projects)
  implementation(projects.subprojects.toolingApi)
  implementation(projects.logsender)
  implementation(projects.lsp.api)
  implementation(projects.lsp.java)
  implementation(projects.lsp.xml)
  implementation(projects.lexers)
  implementation(projects.lookup)
  implementation(projects.preferences)
  implementation(projects.resources)
  implementation(projects.treeview)
  implementation(projects.templatesApi)
  implementation(projects.templatesImpl)
  implementation(projects.uidesigner)
  implementation(projects.xmlInflater)

  implementation(projects.layouteditor.layouteditorApp)
  //implementation(projects.layouteditor.vectormaster)

  //LaoutEditor
  //implementation(libs.desugar.jdk.libs)
  //implementation(libs.editor)
  //implementation(libs.textmate)
  //implementation(libs.junit)
  //implementation(libs.androidx.junit)
  //implementation(libs.androidx.espresso.core)
  //implementation(libs.androidx.lifecycle.runtime.ktx)
  //implementation(libs.androidx.activity.compose)
  //implementation(platform(libs.androidx.compose.bom))
  //implementation(libs.androidx.ui)
  //implementation(libs.androidx.ui.graphics)
  //implementation(libs.androidx.ui.tooling)
  //implementation(libs.androidx.ui.tooling.preview)
  //implementation(libs.androidx.ui.test.manifest)
  //implementation(libs.androidx.ui.test.junit4)
  //implementation(libs.androidx.material3)
  //implementation(libs.material)
  //implementation(libs.utilcodex)
  //implementation(libs.zoomage)
  //implementation(files("libs/layouteditor-release.aar"))

  // This is to build the tooling-api-impl project before the app is built
  // So we always copy the latest JAR file to assets
  compileOnly(projects.subprojects.toolingApiImpl)

  androidTestImplementation(libs.tests.kaspresso)
  androidTestImplementation(libs.tests.junit.kts)
  androidTestUtil(libs.tests.orchestrator)

  testImplementation(projects.testing.unit)
  testImplementation(libs.core.tests.anroidx.arch)
  androidTestImplementation(projects.testing.android)

  androidTestImplementation(libs.tests.androidx.test.runner)

}


//sentry {
//    org.set("appdevforall-inc-pb")
//    projectName.set("android")
//
//    // this will upload your source code to Sentry to show it as part of the stack traces
//    // disable if you don't want to expose your sources
//    includeSourceContext.set(true)
//}

tasks.register("downloadDocDb") {
  doLast {
    val githubRepo = "appdevforall/OfflineDocumentationTools"
    val latestReleaseApiUrl = "https://api.github.com/repos/$githubRepo/releases/latest"

    project.logger.lifecycle("Fetching latest release metadata...")
    try {
      val jsonResponse = URL(latestReleaseApiUrl).readText()
      val jsonObject = JSONObject(jsonResponse)

      val assets = jsonObject.getJSONArray("assets")
      var assetUrl: String? = null
      var assetName: String? = null

      for (i in 0 until assets.length()) {
        val asset = assets.getJSONObject(i)
        val name = asset.getString("name")
        if (name.endsWith(".sqlite")) {
          assetUrl = asset.getString("browser_download_url")
          assetName = name
          break
        }
      }

      val dbName = "documentation.db"
      if (assetUrl != null && assetName != null) {
        val destinationPath = project.rootProject.projectDir.resolve("libs_source/${dbName}").toPath()


        project.logger.lifecycle("Downloading: $assetUrl as ${destinationPath}")

        URL(assetUrl).openStream().use { input ->
          Files.copy(input, destinationPath, StandardCopyOption.REPLACE_EXISTING)
          println("Download complete: ${destinationPath.toString()}")
        }
      } else {
        project.logger.lifecycle("No `.sqlite` asset found in the latest release.")
      }
    } catch (e: Exception) {
      project.logger.lifecycle("Failed to fetch documentation.db info: ${e.message}")
    }
  }
}

fun createAssetsZip(zipName: String, archDir: String) {
  val outputDir = project.layout.buildDirectory.dir("outputs/assets").get().asFile
  if (!outputDir.exists()) {
    outputDir.mkdirs()
    println("Creating output directory: ${outputDir.absolutePath}")
  }

  val zipFile = outputDir.resolve(zipName)
  val sourceDir = project.rootDir.resolve("libs_source")
  val pkgDir = sourceDir.resolve(archDir)


  ZipOutputStream(zipFile.outputStream()).use { zipOut ->

    mapOf(
      "android-sdk.zip" to sourceDir.resolve("androidsdk/android-sdk.zip"),
      "localMvnRepository.zip" to sourceDir.resolve("gradle/localMvnRepository.zip"),
      "gradle-8.7-bin.zip" to sourceDir.resolve("gradle-8.7-bin.zip"),
      "tooling-api-all.jar" to project.rootDir.resolve("subprojects/tooling-api-impl/build/libs/tooling-api-all.jar"),
      "documentation.db" to sourceDir.resolve("documentation.db")
    ).forEach { (fileName, filePath) ->
      if (filePath.exists()) {
        project.logger.lifecycle("Zipping ${fileName} from ${filePath.absolutePath}")
        zipOut.putNextEntry(ZipEntry(fileName))
        filePath.inputStream().copyTo(zipOut)
        zipOut.closeEntry()
      }
    }

    pkgDir.walk().filter { it.isFile }.forEach { file ->
      val relativePath = "packages/" + file.name
      zipOut.putNextEntry(ZipEntry(relativePath))
      file.inputStream().copyTo(zipOut)
      zipOut.closeEntry()
    }

    println("Created ${zipName} successfully at ${zipFile.parentFile.absolutePath}")
  }
}
tasks.register("assembleV8Assets") {
  dependsOn("assembleV8Debug")

  doLast {
    createAssetsZip("assets-v8.zip","termux/v8")
  }
}

tasks.register("assembleV7Assets") {
  dependsOn("assembleV7Debug")

  doLast {
    createAssetsZip("assets-v7.zip","termux/v7")
  }
}

tasks.register("assembleAssets") {
  dependsOn("assembleV8Assets", "assembleV7Assets")
}