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
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.adfa.constants.ANDROID_SDK_ZIP
import com.adfa.constants.DESTINATION_ANDROID_SDK
import com.adfa.constants.HOME_PATH
import com.adfa.constants.LOCAL_MAVEN_CACHES_DEST
import com.adfa.constants.LOCAL_SOURCE_AGP_8_0_0_CACHES
import com.adfa.constants.LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME
import com.adfa.constants.LOCAL_SOURCE_ANDROID_SDK
import com.adfa.constants.LOCAL_SOURCE_TERMUX_LIB_FOLDER_NAME
import com.adfa.constants.MANIFEST_FILE_NAME
import com.adfa.constants.TERMUX_DEBS_PATH
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
import com.termux.shared.android.PackageUtils
import com.termux.shared.markdown.MarkdownUtils
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast

class OnboardingActivity : AppIntro2() {

    private val terminalActivityCallback = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        Log.d(TAG, "TerminalActivity: resultCode=${it.resultCode}")
        if (!isFinishing) {
            reloadJdkDistInfo {
                tryNavigateToMainIfSetupIsCompleted()
            }
        }
    }

    private val requestStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.i(TAG, "READ_EXTERNAL_STORAGE permission granted via direct request.")
            // Permission granted, now we can proceed with the logic that needs it.
            // Option 1: Trigger the setup action again (might need restructuring onDonePressed)
            // Option 2: Assume onDonePressed will be called again by user action (simpler)
            Toast.makeText(this, "Storage permission granted. Please press Done again.", Toast.LENGTH_SHORT).show()
        } else {
            Log.e(TAG, "READ_EXTERNAL_STORAGE permission denied via direct request.")
            Toast.makeText(this, "Storage permission is required to access Downloads.", Toast.LENGTH_LONG).show()
            // Permission denied, cannot proceed with accessing Downloads.
            // Stay on the current fragment or provide guidance.
        }
    }

    private val activityScope =
        CoroutineScope(Dispatchers.Main + CoroutineName("OnboardingActivity"))

    private var listJdkInstallationsJob: Job? = null

    companion object {

        private const val TAG = "OnboardingActivity"
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

        // --- Permission Check and Request ---
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {

            Log.w(TAG, "Storage permission missing before starting setup. Requesting...")
            // Request the permission using the launcher
            requestStoragePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            // Do NOT proceed with the rest of onDonePressed logic yet.
            // Wait for the user to grant/deny via the launcher's callback.
            // The user will likely need to press "Done" again after granting.
            return // Exit onDonePressed for now
        }
        // --- End Permission Check ---

        // If we reach here, permission is granted. Proceed with setup.
        Log.d(TAG, "Storage permission granted. Proceeding with setup task.")

        if (!checkToolsIsInstalled() && currentFragment is IdeSetupConfigurationFragment) {
            activityScope.launchAsyncWithProgress(Dispatchers.IO) { flashbar, cancelChecker ->
                runOnUiThread {
                    flashbar.flashbarView.setTitle(getString(R.string.ide_setup_in_progress))
                }
                copyTermuxDebsAndManifest()
                setupAndroidSdkDirectlyFromDownloads()
                copyMavenLocalRepoFiles()

                runOnUiThread {
                    val intent = Intent(this@OnboardingActivity, TerminalActivity::class.java)
                    if (currentFragment.isAutoInstall()) {
                        intent.putExtra(TerminalActivity.EXTRA_ONBOARDING_RUN_IDESETUP, true)
                        intent.putExtra(
                            TerminalActivity.EXTRA_ONBOARDING_RUN_IDESETUP_ARGS,
                            currentFragment.buildIdeSetupArguments()
                        )
                    }
                    terminalActivityCallback.launch(intent)
                }
            }
            return
        }

        tryNavigateToMainIfSetupIsCompleted()
    }

    private fun copyTermuxDebsAndManifest() {
        val outputDirectory = File(application.dataDir.path + File.separator + TERMUX_DEBS_PATH)
        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs()
        }

        try {
            ResourceUtils.copyFileFromAssets(
                ToolsManager.getCommonAsset(LOCAL_SOURCE_TERMUX_LIB_FOLDER_NAME),
                outputDirectory.path
            )
        } catch (e: IOException) {
            println("Termux caches copy failed + ${e.message}")
        }

        try {
            val manifestOutputDirectory =
                File(application.filesDir.path + File.separator + HOME_PATH).resolve(
                    MANIFEST_FILE_NAME
                )
            ResourceUtils.copyFileFromAssets(
                ToolsManager.getCommonAsset(MANIFEST_FILE_NAME),
                manifestOutputDirectory.path
            )
        } catch (e: IOException) {
            println("Termux manifest copy failed + ${e.message}")
        }
    }

    private fun setupAndroidSdkDirectlyFromDownloads() {
        // 1. Define the target directory for the *unzipped* content (in app's private storage)
        val outputDirectory = File(application.filesDir.path + File.separator + DESTINATION_ANDROID_SDK)
        // Define the expected source file name in Downloads
        val sourceFileName = ANDROID_SDK_ZIP // "android-sdk.zip"

        Log.i(TAG, "Attempting to setup Android SDK DIRECTLY FROM Downloads folder.")
        Log.d(TAG, "Target directory for unzipped files: ${outputDirectory.absolutePath}")

        // 2. Check for Storage Permission (Absolutely essential)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Read External Storage permission not granted. Cannot access Downloads.")
            println("Android SDK setup failed: Read permission not granted.")
            // TODO: Inform user permission is required
            return // Stop the process if permission is missing
        }

        // 3. Locate the public Downloads directory and the source zip file
        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        val sourceZipFile = File(downloadsDir, sourceFileName)
        Log.d(TAG, "Looking for SDK zip at: ${sourceZipFile.absolutePath}")

        // 4. Check if the source file exists in Downloads
        // Note: Even if it exists, Scoped Storage might prevent access later
        if (!sourceZipFile.exists() || !sourceZipFile.isFile) {
            Log.e(TAG, "Source file not found or is not a file: ${sourceZipFile.absolutePath}")
            println("Android SDK setup failed: ${sourceFileName} not found in Downloads folder.")
            // TODO: Inform user to place the file
            return // Stop the process if the file is missing
        }

        // 5. Create the target directory (in app's private storage) if it doesn't exist
        if (!outputDirectory.exists()) {
            if (!outputDirectory.mkdirs()) {
                Log.e(TAG, "Failed to create output directory: ${outputDirectory.absolutePath}")
                println("Android SDK setup failed: Could not create target directory.")
                return // Stop if directory creation fails
            }
        }

        try {
            // --- Key Change: Unzip directly FROM Downloads source ---
            Log.i(TAG, "Attempting to unzip ${sourceZipFile.absolutePath} directly into ${outputDirectory.absolutePath}")

            ZipUtils.unzipFile(sourceZipFile, outputDirectory)

            Log.i(TAG, "Unzip directly from Downloads appears successful.")

        } catch (e: SecurityException) {
            // This is the most likely error on modern Android due to Scoped Storage
            Log.e(TAG, "SecurityException during unzip from Downloads. Access denied.", e)
            println("Android SDK setup failed: Could not access ${sourceFileName} in Downloads. This is likely due to Android storage restrictions (Scoped Storage).")
            // TODO: Inform the user about storage restrictions. Suggest alternative methods (like using assets or SAF).
        } catch (e: IOException) {
            Log.e(TAG, "IOException during SDK unzip from Downloads: ${e.message}", e)
            println("Android SDK unzip failed during I/O: ${e.message}")
            // TODO: Inform user about potential file corruption or storage issues.
        } catch (e: Exception) {
            // Catch other potential errors from ZipUtils
            Log.e(TAG, "Generic Exception during SDK unzip from Downloads: ${e.message}", e)
            println("Android SDK unzip failed: ${e.message}")
            // TODO: Provide a generic error message.
        }
    }

    private fun copyMavenLocalRepoFiles() {
        val outputDirectory =
            File(application.filesDir.path + File.separator + LOCAL_MAVEN_CACHES_DEST)
        val mavenZipFile =
            File("$outputDirectory${File.separator}$LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME")
        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs()
        }

        try {
            ResourceUtils.copyFileFromAssets(
                ToolsManager.getCommonAsset(LOCAL_SOURCE_AGP_8_0_0_CACHES),
                outputDirectory.path
            )

            ZipUtils.unzipFile(mavenZipFile, outputDirectory)
            mavenZipFile.delete()
        } catch (e: IOException) {
            println("Android Gradle caches copy failed + ${e.message}")
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
}