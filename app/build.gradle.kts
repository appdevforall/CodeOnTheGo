@file:Suppress("UnstableApiUsage")

import ch.qos.logback.core.util.EnvUtil
import com.itsaky.androidide.build.config.BuildConfig
import com.itsaky.androidide.desugaring.ch.qos.logback.core.util.DesugarEnvUtil
import com.itsaky.androidide.desugaring.utils.JavaIOReplacements.applyJavaIOReplacements
import com.itsaky.androidide.plugins.AndroidIDEAssetsPlugin
import java.nio.file.Files
import kotlin.reflect.jvm.javaMethod

import java.net.URL
import java.net.URI
import java.nio.file.StandardCopyOption
import org.json.JSONObject

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.file.attribute.FileTime
import java.util.zip.Deflater

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream


plugins {
  id("com.android.application")
  id("kotlin-android")
  id("kotlin-kapt")
  id("kotlin-parcelize")
  id("androidx.navigation.safeargs.kotlin")
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
    testInstrumentationRunnerArguments["androidide.test.mode"] = "true"
  }

  testOptions {
    execution = "ANDROIDX_TEST_ORCHESTRATOR"
    
    unitTests {
      isIncludeAndroidResources = true
      all {
        // Skip TreeSitter native library loading in tests
        it.systemProperty("java.library.path", System.getProperty("java.library.path"))
        it.systemProperty("androidide.test.mode", "true")
      }
    }
  }

  androidResources {
    generateLocaleConfig = true
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

  implementation(platform(libs.sora.bom))
  implementation(libs.common.editor)
  implementation(libs.common.utilcode)
  implementation(libs.common.glide)
  implementation(libs.common.jsoup)
  implementation(libs.common.kotlin.coroutines.android)
  implementation(libs.common.retrofit)
  implementation(libs.common.retrofit.gson)
  implementation(libs.common.charts)
  implementation(libs.common.hiddenApiBypass)
  implementation(libs.commons.compress)

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
  implementation(libs.composite.treeview)

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

  implementation(projects.layouteditor)
  implementation(projects.idetooltips)

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
  compileOnly(projects.subprojects.libjdwpRemote)

  androidTestImplementation(libs.tests.kaspresso)
  androidTestImplementation(libs.tests.junit.kts)
  androidTestUtil(libs.tests.orchestrator)

  testImplementation(projects.testing.unit)
  testImplementation(libs.core.tests.anroidx.arch)
  androidTestImplementation(projects.testing.android)

  androidTestImplementation(libs.tests.androidx.test.runner)

  // brotli4j
  implementation(libs.brotli4j)
}

tasks.register("downloadDocDb") {
  doLast {
    val githubRepo = "appdevforall/OfflineDocumentationTools"
    val latestReleaseApiUrl = "https://api.github.com/repos/$githubRepo/releases/latest"

    project.logger.lifecycle("Fetching latest release metadata...")
    try {
      val jsonResponse = URI(latestReleaseApiUrl).toURL().readText()
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
        val destinationPath = project.rootProject.projectDir.resolve("assets/${dbName}").toPath()


        project.logger.lifecycle("Downloading : $assetUrl as ${destinationPath}")

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

fun createAssetsZip(
  arch: String,
) {
  val outputDir = project.layout.buildDirectory.dir("outputs/assets").get().asFile
  if (!outputDir.exists()) {
    outputDir.mkdirs()
    println("Creating output directory: ${outputDir.absolutePath}")
  }

  val zipFile = outputDir.resolve("assets-$arch.zip")
  val sourceDir = project.rootDir.resolve("assets")
  val bootstrapName = "bootstrap-$arch.zip"
  val androidSdkName = "android-sdk-$arch.zip"

  ZipOutputStream(zipFile.outputStream()).use { zipOut ->
    arrayOf(
      androidSdkName,
      "localMvnRepository.zip",
      "gradle-8.14.3-bin.zip",
      "gradle-api-8.14.3.jar.zip",
      "documentation.db",
      bootstrapName,
    ).forEach { fileName ->
      val filePath = sourceDir.resolve(fileName)
      if (!filePath.exists()) {
        throw FileNotFoundException(filePath.absolutePath)
      }

      project.logger.lifecycle("Zipping $fileName from ${filePath.absolutePath}")
      val entryName = when (fileName) {
        bootstrapName -> "bootstrap.zip"
        androidSdkName -> "android-sdk.zip"
        else -> fileName
      }

      zipOut.putNextEntry(ZipEntry(entryName))
      filePath.inputStream().use { input -> input.copyTo(zipOut) }
      zipOut.closeEntry()
    }

    println("Created ${zipFile.name} successfully at ${zipFile.parentFile.absolutePath}")
  }
}

tasks.register("assembleV8Assets") {
  doLast {
    createAssetsZip("arm64-v8a")
  }
}

tasks.register("assembleV7Assets") {
  doLast {
    createAssetsZip("armeabi-v7a")
  }
}

tasks.register("assembleAssets") {
  dependsOn("assembleV8Assets", "assembleV7Assets")
}

tasks.register("recompressApk") {
  doLast {
    val abi: String = extensions.extraProperties["abi"].toString()
    val buildName: String = extensions.extraProperties["buildName"].toString()

    project.logger.lifecycle("Calling recompressApk abi:${abi} buildName:${buildName}")

    recompressApk(abi, buildName)
  }
}

afterEvaluate {
  tasks.named("assembleV8Debug").configure {
    finalizedBy("recompressApk")

    doLast {
      tasks.named("recompressApk").configure {
        extensions.extraProperties["abi"] = "v8"
        extensions.extraProperties["buildName"] = "debug"
      }
    }
  }

  tasks.named("assembleV8Release").configure {
    finalizedBy("recompressApk")

    doLast {
      tasks.named("recompressApk").configure {
        extensions.extraProperties["abi"] = "v8"
        extensions.extraProperties["buildName"] = "release"
      }
    }
  }

  tasks.named("assembleV7Debug").configure {
    finalizedBy("recompressApk")

    doLast {
      tasks.named("recompressApk").configure {
        extensions.extraProperties["abi"] = "v7"
        extensions.extraProperties["buildName"] = "debug"
      }
    }
  }

  tasks.named("assembleV7Release").configure {
    finalizedBy("recompressApk")

    doLast {
      tasks.named("recompressApk").configure {
        extensions.extraProperties["abi"] = "v7"
        extensions.extraProperties["buildName"] = "release"
      }
    }
  }

}


fun recompressApk(abi: String, buildName: String) {
  val apkDir: File = layout.buildDirectory.dir("outputs/apk/$abi/$buildName").get().asFile
  project.logger.lifecycle("Recompressing APK Dir: ${apkDir.path}")

  apkDir.walk().filter { it.extension == "apk" }.forEach { apkFile ->
    project.logger.lifecycle("Recompressing APK: ${apkFile.name}")
    val tempZipFile = File(apkFile.parentFile, "${apkFile.name}.tmp")
    recompressZip(apkFile, tempZipFile)
    signApk(tempZipFile)
    if (apkFile.delete()) {
      tempZipFile.renameTo(apkFile)
    }
  }
}

fun recompressZip( inputZip: File, outputZip: File) {
  ZipInputStream(BufferedInputStream(FileInputStream(inputZip))).use { zis ->
    ZipOutputStream(BufferedOutputStream(FileOutputStream(outputZip))).use { zos ->
      zos.setLevel(Deflater.BEST_COMPRESSION)

      var entry = zis.nextEntry
      while (entry != null) {
        if (!entry.isDirectory) {
          val newEntry = ZipEntry(entry.name)

          // Remove timestamps for deterministic output (-X)
          newEntry.time = 0L
          try {
            newEntry.creationTime = FileTime.fromMillis(0)
            newEntry.lastModifiedTime = FileTime.fromMillis(0)
            newEntry.lastAccessTime = FileTime.fromMillis(0)
          } catch (_: Throwable) {} // In case JVM doesn't support them

          zos.putNextEntry(newEntry)
          zis.copyTo(zos)
          zos.closeEntry()
        }
        entry = zis.nextEntry
      }
    }
  }
}

fun signApk(apkFile: File) {
  project.logger.lifecycle("Signing APK: ${apkFile.name}")

  val isWindows = System.getProperty("os.name").lowercase().contains("windows")
  var signerExec = "apksigner"
  if (isWindows) {
    signerExec = "apksigner.bat"
  }

  val signingConfig = android.signingConfigs.getByName("debug") // 🔥 Get existing signing config

  val keystorePath = signingConfig.storeFile?.absolutePath ?: error("Keystore not found!")
  val keystorePassword = signingConfig.storePassword ?: error("Keystore password missing!")
  val keyAlias = signingConfig.keyAlias ?: error("Key alias missing!")
  val keyPassword = signingConfig.keyPassword ?: error("Key password missing!")

  val androidSdkDir = android.sdkDirectory.absolutePath
  val apkSignerPath: File = File(androidSdkDir, "build-tools")
    .listFiles()
    ?.filter { it.isDirectory && File(it, signerExec).exists() }
    ?.maxByOrNull { it.name }  // pick the highest version
    ?.resolve(signerExec)
    ?: error("Could not find apksigner in any build-tools directory")

  //val apkSignerPath = File(android.sdkDirectory.absolutePath + File.separator +
  //        "build-tools" + File.separator + "34.0.0" + File.separator + signerExec)

  project.logger.lifecycle("APK Signer: ${apkSignerPath.absolutePath}")

  ant.withGroovyBuilder {
    "exec"(
      "executable" to apkSignerPath.absolutePath,
      "failonerror" to "true"
    ) {
      "arg"("value" to "sign")
      "arg"("value" to "--ks")
      "arg"("value" to keystorePath)
      "arg"("value" to "--ks-key-alias")
      "arg"("value" to keyAlias)
      "arg"("value" to "--ks-pass")
      "arg"("value" to "pass:$keystorePassword")
      "arg"("value" to "--key-pass")
      "arg"("value" to "pass:$keyPassword")
      "arg"("value" to apkFile.absolutePath)
    }
  }

}
