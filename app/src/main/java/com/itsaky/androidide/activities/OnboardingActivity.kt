/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.blankj.utilcode.util.FileUtils
import com.blankj.utilcode.util.ResourceUtils
import com.blankj.utilcode.util.ZipUtils
import com.github.appintro.AppIntro2
import com.github.appintro.AppIntroPageTransformerType
import com.itsaky.androidide.R
import com.itsaky.androidide.R.string
import com.itsaky.androidide.app.configuration.IDEBuildConfigProvider
import com.itsaky.androidide.app.configuration.IJdkDistributionProvider
import com.itsaky.androidide.fragments.onboarding.GreetingFragment
import com.itsaky.androidide.fragments.onboarding.IdeSetupConfigurationFragment
import com.itsaky.androidide.fragments.onboarding.OnboardingInfoFragment
import com.itsaky.androidide.fragments.onboarding.PermissionsFragment
import com.itsaky.androidide.fragments.onboarding.StatisticsFragment
import com.itsaky.androidide.managers.ToolsManager
import com.itsaky.androidide.models.JdkDistribution
import com.itsaky.androidide.preferences.internal.prefManager
import com.itsaky.androidide.tasks.launchAsyncWithProgress
import com.itsaky.androidide.ui.themes.IThemeManager
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.OrientationUtilities
import com.itsaky.androidide.utils.TerminalInstaller
import com.termux.shared.android.PackageUtils
import com.termux.shared.markdown.MarkdownUtils
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.adfa.constants.ANDROID_SDK_ZIP
import org.adfa.constants.ANDROID_SDK_ZIP_BR
import org.adfa.constants.DESTINATION_ANDROID_SDK
import org.adfa.constants.DOCUMENTATION_DB
import org.adfa.constants.GRADLE_WRAPPER_FILE_NAME
import org.adfa.constants.GRADLE_WRAPPER_FILE_NAME_BR
import org.adfa.constants.LOCAL_MAVEN_CACHES_DEST
import org.adfa.constants.LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME
import org.adfa.constants.LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME_BR
import org.adfa.constants.SPLIT_ASSETS
import org.brotli.dec.BrotliInputStream
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

class OnboardingActivity : AppIntro2() {

    private val terminalActivityCallback = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        logger.debug("TerminalActivity: resultCode={}", it.resultCode)
        if (!isFinishing) {
            reloadJdkDistInfo {
                tryNavigateToMainIfSetupIsCompleted()
            }
        }
    }

    private val activityScope =
        CoroutineScope(Dispatchers.Main + CoroutineName("OnboardingActivity"))

    private var listJdkInstallationsJob: Job? = null

    companion object {
        private val logger = LoggerFactory.getLogger(OnboardingActivity::class.java)
        private const val KEY_ARCHCONFIG_WARNING_IS_SHOWN =
            "ide.archConfig.experimentalWarning.isShown"
    }

    @SuppressLint("SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        IThemeManager.getInstance().applyTheme(this)
        setOrientationFunction {
            OrientationUtilities.setOrientation {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
        super.onCreate(savedInstanceState)

        if (tryNavigateToMainIfSetupIsCompleted()) {
            return
        }

        setSwipeLock(true)
        setTransformer(AppIntroPageTransformerType.Fade)
        setProgressIndicator()
        showStatusBar(true)
        isIndicatorEnabled = true
        isWizardMode = true

        addSlide(GreetingFragment())

        if (!PackageUtils.isCurrentUserThePrimaryUser(this)) {
            val errorMessage = getString(
                string.bootstrap_error_not_primary_user_message,
                MarkdownUtils.getMarkdownCodeForString(
                    TermuxConstants.TERMUX_PREFIX_DIR_PATH,
                    false
                )
            )
            addSlide(
                OnboardingInfoFragment.newInstance(
                    getString(string.title_unsupported_user),
                    errorMessage,
                    R.drawable.ic_alert,
                    ContextCompat.getColor(this, R.color.color_error)
                )
            )
            return
        }

        if (isInstalledOnSdCard()) {
            val errorMessage = getString(
                string.bootstrap_error_installed_on_portable_sd,
                MarkdownUtils.getMarkdownCodeForString(
                    TermuxConstants.TERMUX_PREFIX_DIR_PATH,
                    false
                )
            )
            addSlide(
                OnboardingInfoFragment.newInstance(
                    getString(string.title_install_location_error),
                    errorMessage,
                    R.drawable.ic_alert,
                    ContextCompat.getColor(this, R.color.color_error)
                )
            )
            return
        }

        if (!checkDeviceSupported()) {
            return
        }

        /******** TODO JMT deleted for offline mode
        if (!StatPreferences.statConsentDialogShown) {
        addSlide(StatisticsFragment.newInstance(this))
        StatPreferences.statConsentDialogShown = true
        }
         **********/

        if (!PermissionsFragment.areAllPermissionsGranted(this)) {
            addSlide(PermissionsFragment.newInstance(this))
        }

        if (!checkToolsIsInstalled()) {
            addSlide(IdeSetupConfigurationFragment.newInstance(this))
        }
    }

    override fun onResume() {
        super.onResume()
        reloadJdkDistInfo {
            tryNavigateToMainIfSetupIsCompleted()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel("Activity is being destroyed")
    }

    override fun onNextPressed(currentFragment: Fragment?) {
        (currentFragment as? StatisticsFragment?)?.updateStatOptInStatus()
    }

    override fun onDonePressed(currentFragment: Fragment?) {
        (currentFragment as? StatisticsFragment?)?.updateStatOptInStatus()

        if (!IDEBuildConfigProvider.getInstance().supportsCpuAbi()) {
            finishAffinity()
            return
        }

        if (!checkToolsIsInstalled() && currentFragment is IdeSetupConfigurationFragment) {
            activityScope.launchAsyncWithProgress(Dispatchers.IO) { flashbar, cancelChecker ->
                runOnUiThread {
                    flashbar.flashbarView.setTitle(getString(R.string.ide_setup_in_progress))
                }

                copyAndroidSDK()
                copyMavenLocalRepoFiles()
                copyGradleDists()
                copyToolingApi()
                copyDocumentation()

                val result = TerminalInstaller.installIfNeeded(this@OnboardingActivity) {}

                logger.info("bootstrap installation result: {}", result)

                if (result !is TerminalInstaller.InstallResult.Success) {
                    return@launchAsyncWithProgress
                }

                withContext(Dispatchers.Main) {
                    reloadJdkDistInfo {
                        tryNavigateToMainIfSetupIsCompleted()
                    }
                }
            }
            return
        }

        tryNavigateToMainIfSetupIsCompleted()
    }

    private fun copyAndroidSDK() {
        val outputDirectory =
            File(application.filesDir.path + File.separator + DESTINATION_ANDROID_SDK)
        val zipFile =
            File(application.filesDir.path + File.separator + DESTINATION_ANDROID_SDK + File.separator + ANDROID_SDK_ZIP)
        val brotliFile =
            File(application.filesDir.path + File.separator + DESTINATION_ANDROID_SDK + File.separator + ANDROID_SDK_ZIP_BR)
        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs()
        }

        try {
            if (SPLIT_ASSETS) {
                ZipUtils.unzipFileByKeyword(Environment.SPLIT_ASSETS_ZIP, outputDirectory, ANDROID_SDK_ZIP) }
            else {
                ResourceUtils.copyFileFromAssets(
                    ToolsManager.getCommonAsset(ANDROID_SDK_ZIP_BR),
                    brotliFile.path
                )

                if (!brotliFile.exists()) {
                    Log.e("OnboardingActivityInstall", "Brotli file ${brotliFile.path} doesn't exist!")
                }

                decompressBrotli(brotliFile.path, zipFile.path)
                if (!zipFile.exists()) {
                    Log.e("OnboardingActivityInstall", "Brotli decompression of ${brotliFile.path} failed!")
                }

            }

            ZipUtils.unzipFile(zipFile, outputDirectory)
            zipFile.delete()

            if (!SPLIT_ASSETS) {
                brotliFile.delete()
            }

        } catch (e: IOException) {
            Log.e("OnboardingActivityInstall", "Android SDK copy failed: ${e.message}")
        }
    }

    private fun copyMavenLocalRepoFiles() {
        val outputDirectory =
            File(application.filesDir.path + File.separator + LOCAL_MAVEN_CACHES_DEST)
        val mavenZipFile =
            File("$outputDirectory${File.separator}$LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME")
        val mavenBrotliFile =
            File("$outputDirectory${File.separator}$LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME_BR")
        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs()
        }

        try {
            if (SPLIT_ASSETS) {
                ZipUtils.unzipFileByKeyword(Environment.SPLIT_ASSETS_ZIP, outputDirectory, LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME) }
            else {
                ResourceUtils.copyFileFromAssets(
                    ToolsManager.getCommonAsset(LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME_BR),
                    mavenBrotliFile.path
                )

                decompressBrotli(mavenBrotliFile.path, mavenZipFile.path)
                if (!mavenZipFile.exists()) {
                    Log.e("OnboardingActivityInstall", "Brotli decompression of ${mavenBrotliFile.path} failed!")
                }

            }

            ZipUtils.unzipFile(mavenZipFile, outputDirectory)
            mavenZipFile.delete()

            if (!SPLIT_ASSETS) {
                mavenBrotliFile.delete()
            }

        } catch (e: IOException) {
            Log.e("OnboardingActivityInstall", "Android Gradle caches copy failed: ${e.message}")
        }
    }

    private fun copyGradleDists() {

        try {
            val outputDirectory =
                File(Environment.GRADLE_DISTS.absolutePath)
            if (!outputDirectory.exists()) {
                outputDirectory.mkdirs()
            }

            val brotliFile = outputDirectory.resolve(GRADLE_WRAPPER_FILE_NAME_BR)
            val zipFile = outputDirectory.resolve(GRADLE_WRAPPER_FILE_NAME)

            if (SPLIT_ASSETS) {
                ZipUtils.unzipFileByKeyword(Environment.SPLIT_ASSETS_ZIP, outputDirectory, GRADLE_WRAPPER_FILE_NAME)
            } else {
                ResourceUtils.copyFileFromAssets(
                    ToolsManager.getCommonAsset(GRADLE_WRAPPER_FILE_NAME_BR),
                    brotliFile.absolutePath
                )

                decompressBrotli(brotliFile.absolutePath, zipFile.absolutePath)

                if (!zipFile.exists()) {
                    Log.e("OnboardingActivityInstall", "Brotli decompression of ${brotliFile.path} failed!")
                }

                // copy duplicate kotlin embeddable jar to local maven repo
                val kotlin_embed_jar = "lib/kotlin-compiler-embeddable-1.9.22.jar"
                Log.d("OnboardingActivityInstall", "Copying $kotlin_embed_jar to ${zipFile.parentFile?.path}")
                ZipUtils.unzipFileByKeyword(zipFile, zipFile.parentFile, kotlin_embed_jar)
                val jarSrc = File("${zipFile.parentFile?.path}/gradle-8.7/${kotlin_embed_jar}")
                val jarDest = File("${application.filesDir.path}/$LOCAL_MAVEN_CACHES_DEST/" +
                        "localMvnRepository/org/jetbrains/kotlin/kotlin-compiler-embeddable/1.9.22/${jarSrc.name}")
                if (jarSrc.exists()) {
                    Log.d("OnboardingActivityInstall", "Copying ${jarSrc.path} to ${jarDest.path}")
                    jarSrc.copyTo(jarDest, overwrite = true)
                    // delete source jar and parent folder
                    zipFile.parentFile.resolve("gradle-8.7").deleteRecursively()
                } else {
                    Log.e("OnboardingActivityInstall", "${jarSrc.absolutePath} does not exist!")
                }

                brotliFile.delete()
            }

            ZipUtils.unzipFile(zipFile, outputDirectory)
            zipFile.delete()

        } catch (e: IOException) {
            Log.e("OnboardingActivityInstall", "Gradle Dists copy failed: ${e.message}")
        }
    }

    private fun copyDocumentation() {
        val dbPath = getDatabasePath(DOCUMENTATION_DB)
        val dbFile = File(Environment.DOWNLOAD_DIR, DOCUMENTATION_DB)
        try {
            if (SPLIT_ASSETS) {
                if (dbFile.exists()) {
                    // first priority to copy is documentation db in /sdcard/Download
                    dbFile.copyTo(dbPath, overwrite = true)
                } else {
                    // second priority is the one contained in assets.zip
                    ZipUtils.unzipFileByKeyword(
                        Environment.SPLIT_ASSETS_ZIP,
                        dbPath.parentFile,
                        DOCUMENTATION_DB
                    )
                }
            } else {
                ResourceUtils.copyFileFromAssets(
                    ToolsManager.getDatabaseAsset(DOCUMENTATION_DB),
                    dbPath.path
                )
            }
        } catch (e: IOException) {
            println("Documentation DB copy failed + ${e.message}")
        }
    }

    private fun copyToolingApi() {
        val tooling_api_jar = "tooling-api-all.jar"
        val tooling_api_jar_br = "${tooling_api_jar}.br"

        try {
            if (Environment.TOOLING_API_JAR.exists()) {
                FileUtils.delete(Environment.TOOLING_API_JAR)
            }

            if (SPLIT_ASSETS) {
                ResourceUtils.copyFileFromAssets(
                    ToolsManager.getCommonAsset(tooling_api_jar),
                    Environment.TOOLING_API_JAR.absolutePath
                )
            } else {
                ResourceUtils.copyFileFromAssets(
                    ToolsManager.getCommonAsset(tooling_api_jar_br),
                    Environment.TOOLING_API_JAR.parentFile.resolve(tooling_api_jar_br).absolutePath
                )

                val brotliFile = Environment.TOOLING_API_JAR.parentFile.resolve(tooling_api_jar_br)
                val jarFile = Environment.TOOLING_API_JAR.parentFile.resolve(tooling_api_jar)
                if (!brotliFile.exists()) {
                    Log.e("OnboardingActivityInstall",
                        "Brotli file ${brotliFile.path} doesn't exist!")
                }

                decompressBrotli(brotliFile.absolutePath, jarFile.absolutePath)

                if (!jarFile.exists()) {
                    Log.e("OnboardingActivityInstall", "Brotli decompression of ${jarFile.path} failed!")
                }
            }

        } catch (e: IOException) {
            Log.e("OnboardingActivityInstall", "Tooling API jar copy failed: ${e.message}")
        }
    }


    private fun checkToolsIsInstalled(): Boolean {
        return IJdkDistributionProvider.getInstance().installedDistributions.isNotEmpty()
                && Environment.ANDROID_HOME.exists()
    }

    private fun isSetupCompleted(): Boolean {
        return checkToolsIsInstalled()
                /* JMT && StatPreferences.statConsentDialogShown */
                && PermissionsFragment.areAllPermissionsGranted(this)
    }

    private fun tryNavigateToMainIfSetupIsCompleted(): Boolean {
        if (isSetupCompleted()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return true
        }

        return false
    }

    private inline fun reloadJdkDistInfo(crossinline distConsumer: (List<JdkDistribution>) -> Unit) {
        listJdkInstallationsJob?.cancel("Reloading JDK distributions")

        listJdkInstallationsJob = activityScope.launchAsyncWithProgress(Dispatchers.Default,
            configureFlashbar = { builder, _ ->
                builder.message(string.please_wait)
            }) { _, _ ->
            val distributionProvider = IJdkDistributionProvider.getInstance()
            distributionProvider.loadDistributions()
            withContext(Dispatchers.Main) {
                distConsumer(distributionProvider.installedDistributions)
            }
        }.also {
            it?.invokeOnCompletion {
                listJdkInstallationsJob = null
            }
        }
    }

    private fun isInstalledOnSdCard(): Boolean {
        // noinspection SdCardPath
        return PackageUtils.isAppInstalledOnExternalStorage(this) &&
                TermuxConstants.TERMUX_FILES_DIR_PATH != filesDir.absolutePath
            .replace("^/data/user/0/".toRegex(), "/data/data/")
    }

    private fun checkDeviceSupported(): Boolean {
        val configProvider = IDEBuildConfigProvider.getInstance()

        if (!configProvider.supportsCpuAbi()) {
            //TODO JMT figure out how to build v8a and/or x64_86
//            addSlide(
//                OnboardingInfoFragment.newInstance(
//                    getString(string.title_unsupported_device),
//                    getString(
//                        string.msg_unsupported_device,
//                        configProvider.cpuArch.abi,
//                        configProvider.deviceArch.abi
//                    ),
//                    R.drawable.ic_alert,
//                    ContextCompat.getColor(this, R.color.color_error)
//                )
//            )
//            return false
            return true
        }

        if (configProvider.cpuArch != configProvider.deviceArch) {
            // IDE's build flavor is NOT the primary arch of the device
            // warn the user
            if (!archConfigExperimentalWarningIsShown()) {
                //TODO JMT get build to support v8a and/or x86_64
//                addSlide(
//                    OnboardingInfoFragment.newInstance(
//                        getString(string.title_experiment_flavor),
//                        getString(
//                            string.msg_experimental_flavor,
//                            configProvider.cpuArch.abi,
//                            configProvider.deviceArch.abi
//                        ),
//                        R.drawable.ic_alert,
//                        ContextCompat.getColor(this, R.color.color_warning)
//                    )
//                )
                prefManager.putBoolean(KEY_ARCHCONFIG_WARNING_IS_SHOWN, true)
            }
        }

        return true
    }

    private fun archConfigExperimentalWarningIsShown() =
        prefManager.getBoolean(KEY_ARCHCONFIG_WARNING_IS_SHOWN, false)

    private fun decompressBrotli(inputPath: String, outputPath: String) {
        FileInputStream(inputPath).use { input ->
            BrotliInputStream(input).use { brotliIn ->
                FileOutputStream(outputPath).use { output ->
                    val buffer = ByteArray(1024 * 1024) // 1Mb buffer
                    var bytesRead: Int

                    while (brotliIn.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }

                }
            }
        }
    }

}