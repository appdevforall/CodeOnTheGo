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
import com.adfa.constants.COMPOSE_GRADLE_WRAPPER_FILE_NAME
import com.adfa.constants.GRADLE_WRAPPER_FILE_NAME
import com.adfa.constants.TERMUX_DEBS_ZIP_FILENAME

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
                setupTermuxDirectlyFromDownloads()
                setupAndroidSdkDirectlyFromDownloads()
                setupMavenRepoDirectlyFromDownloads()
                setupGradleDistsDirectlyFromDownloads()

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

    private fun setupTermuxDirectlyFromDownloads() {
        // --- Part 1: Handle Termux Debs via Zip ---

        // 1a. Define the target directory for the *unzipped* .deb files
        // Note: TERMUX_DEBS_PATH is relative; combine with app's data directory.
        val debsOutputDirectory = File(application.dataDir, TERMUX_DEBS_PATH)
        // Define the expected source zip file name in Downloads
        val debsSourceFileName = TERMUX_DEBS_ZIP_FILENAME

        Log.i(TAG, "Attempting to setup Termux Debs DIRECTLY FROM Downloads using $debsSourceFileName.")
        Log.d(TAG, "Target directory for unzipped debs: ${debsOutputDirectory.absolutePath}")

        // NOTE: Permission check (READ_EXTERNAL_STORAGE) is assumed to have been
        // performed and passed in onDonePressed before this function is called.

        // 2a. Locate the public Downloads directory and the source zip file
        val downloadsDir: File? = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        if (downloadsDir == null) {
            Log.e(TAG, "Could not access public Downloads directory.")
            println("Termux Debs setup failed: Cannot find Downloads directory.")
            // Handle error - maybe skip Termux setup for dev? Or halt.
            // For safety, let's try the manifest part even if debs fail.
        } else { // Only proceed with debs if downloadsDir is not null
            val debsSourceZipFile = File(downloadsDir, debsSourceFileName)
            Log.d(TAG, "Looking for Termux Debs zip at: ${debsSourceZipFile.absolutePath}")

            // 3a. Check if the source zip file exists
            if (!debsSourceZipFile.exists() || !debsSourceZipFile.isFile) {
                Log.e(TAG, "Source zip file not found: ${debsSourceZipFile.absolutePath}")
                println("Termux Debs setup failed: ${debsSourceFileName} not found in Downloads.")
                // Handle error - Inform user, maybe skip.
            } else {
                // 4a. Create the target directory structure (e.g., dataDir/cache/apt/archives)
                if (!debsOutputDirectory.exists()) {
                    if (!debsOutputDirectory.mkdirs()) { // mkdirs creates parent dirs too
                        Log.e(TAG, "Failed to create output directory structure: ${debsOutputDirectory.absolutePath}")
                        println("Termux Debs setup failed: Could not create target directory structure.")
                        // Handle error
                    }
                }

                // 5a. Unzip DIRECTLY from Downloads into the target app directory if directory creation succeeded
                if (debsOutputDirectory.exists()) {
                    try {
                        Log.i(TAG, "Attempting to unzip ${debsSourceZipFile.absolutePath} (Termux Debs) directly into ${debsOutputDirectory.absolutePath}")
                        // Unzip source file FROM Downloads INTO the app's private cache structure
                        ZipUtils.unzipFile(debsSourceZipFile, debsOutputDirectory)
                        Log.i(TAG, "Unzip directly from Downloads appears successful for Termux Debs.")
                    } catch (e: SecurityException) {
                        Log.e(TAG, "SecurityException during Termux Debs unzip from Downloads. Access denied.", e)
                        println("Termux Debs setup failed: Could not access ${debsSourceFileName} in Downloads (Scoped Storage?).")
                    } catch (e: IOException) {
                        Log.e(TAG, "IOException during Termux Debs unzip from Downloads: ${e.message}", e)
                        println("Termux Debs setup failed during I/O: ${e.message}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Generic Exception during Termux Debs unzip from Downloads: ${e.message}", e)
                        println("Termux Debs setup failed: ${e.message}")
                    }
                }
            }
        } // End of deb handling block

        // --- Part 2: Handle Manifest File ---

        // Define manifest source and target
        val manifestFileName = MANIFEST_FILE_NAME // "manifest.json"
        // Target: app_filesDir/home/manifest.json
        val manifestTargetFile = File(application.filesDir, HOME_PATH).resolve(manifestFileName)

        Log.i(TAG, "Attempting to copy Manifest file DIRECTLY FROM Downloads folder.")

        if (downloadsDir == null) {
            // We already logged this error during deb handling.
            println("Termux Manifest setup skipped: Cannot find Downloads directory.")
            return // Can't proceed without Downloads dir
        }

        // Source: downloadsDir/manifest.json
        val manifestSourceFile = File(downloadsDir, manifestFileName)
        Log.d(TAG, "Looking for Manifest file at: ${manifestSourceFile.absolutePath}")
        Log.d(TAG, "Target file for manifest: ${manifestTargetFile.absolutePath}")

        // Check if manifest exists in Downloads
        if (!manifestSourceFile.exists() || !manifestSourceFile.isFile) {
            Log.w(TAG, "Manifest file not found in Downloads: ${manifestSourceFile.absolutePath}. Skipping manifest setup.")
            println("Termux Manifest setup skipped: ${manifestFileName} not found in Downloads.")
            // This might be acceptable depending on how crucial the manifest is.
        } else {
            // Copy manifest file directly
            try {
                // Ensure parent directory exists (app_filesDir/home)
                if (!manifestTargetFile.parentFile.exists()) {
                    if (!manifestTargetFile.parentFile.mkdirs()) {
                        Log.e(TAG, "Failed to create parent directory for manifest: ${manifestTargetFile.parentFile.absolutePath}")
                        println("Termux Manifest setup failed: Could not create target parent directory.")
                        return // Stop if parent dir creation fails
                    }
                }
                // Perform the direct copy from Downloads to app's private files/home
                manifestSourceFile.copyTo(manifestTargetFile, overwrite = true)
                Log.i(TAG, "Manifest file copy from Downloads successful.")
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException copying Manifest from Downloads.", e)
                println("Termux Manifest setup failed: Could not access ${manifestFileName} in Downloads (Scoped Storage?).")
            } catch (e: IOException) {
                Log.e(TAG, "IOException copying Manifest from Downloads: ${e.message}", e)
                println("Termux Manifest setup failed during I/O: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Generic Exception copying Manifest from Downloads: ${e.message}", e)
                println("Termux Manifest setup failed: ${e.message}")
            }
        } // End of manifest handling block
    }

    private fun setupAndroidSdkDirectlyFromDownloads() {
        // 1. Define the target directory for the *unzipped* content (in app's private storage)
        val outputDirectory = File(application.filesDir.path + File.separator + DESTINATION_ANDROID_SDK)
        // Define the expected source file name in Downloads
        val sourceFileName = ANDROID_SDK_ZIP // "android-sdk.zip"

        Log.i(TAG, "Attempting to setup Android SDK DIRECTLY FROM Downloads folder.")
        Log.d(TAG, "Target directory for unzipped files: ${outputDirectory.absolutePath}")

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

    private fun setupMavenRepoDirectlyFromDownloads() {
        // 1. Define the target directory for the *unzipped* content
        // Uses the constant for the destination Maven caches path
        val outputDirectory = File(application.filesDir.path + File.separator + LOCAL_MAVEN_CACHES_DEST)

        // Define the expected source file name in Downloads
        // Uses the constant for the Maven repo zip archive name
        val sourceFileName = LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME // "localMvnRepository.zip"

        Log.i(TAG, "Attempting to setup Local Maven Repo DIRECTLY FROM Downloads folder.")
        Log.d(TAG, "Target directory for unzipped files: ${outputDirectory.absolutePath}")

        // NOTE: Permission check (READ_EXTERNAL_STORAGE) is assumed to have been
        // performed and passed in onDonePressed before this function is called.

        // 2. Locate the public Downloads directory and the source zip file
        val downloadsDir: File? = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        if (downloadsDir == null) {
            Log.e(TAG, "Could not access public Downloads directory.")
            println("Maven Repo setup failed: Cannot find Downloads directory.")
            // TODO: Inform user or handle error
            return // Stop if directory is null
        }
        val sourceZipFile = File(downloadsDir, sourceFileName)
        Log.d(TAG, "Looking for Maven Repo zip at: ${sourceZipFile.absolutePath}")

        // 3. Check if the source file exists in Downloads
        if (!sourceZipFile.exists() || !sourceZipFile.isFile) {
            Log.e(TAG, "Source file not found or is not a file: ${sourceZipFile.absolutePath}")
            println("Maven Repo setup failed: ${sourceFileName} not found in Downloads folder.")
            // TODO: Inform user to place the file
            return // Stop if the file is missing
        }

        // 4. Create the target directory (in app's private storage) if it doesn't exist
        if (!outputDirectory.exists()) {
            if (!outputDirectory.mkdirs()) {
                Log.e(TAG, "Failed to create output directory: ${outputDirectory.absolutePath}")
                println("Maven Repo setup failed: Could not create target directory.")
                return // Stop if directory creation fails
            }
        }

        try {
            // 5. Unzip DIRECTLY from the Downloads source file into the app's private directory
            Log.i(TAG, "Attempting to unzip ${sourceZipFile.absolutePath} (Maven Repo) directly into ${outputDirectory.absolutePath}")

            // *** Use the sourceZipFile pointing to Downloads as input ***
            ZipUtils.unzipFile(sourceZipFile, outputDirectory)

            Log.i(TAG, "Unzip directly from Downloads appears successful for Maven Repo.")

        } catch (e: SecurityException) {
            // Catch potential permission/Scoped Storage issues
            Log.e(TAG, "SecurityException during Maven Repo unzip from Downloads. Access denied.", e)
            println("Maven Repo setup failed: Could not access ${sourceFileName} in Downloads (Scoped Storage?).")
            // TODO: Inform the user about storage restrictions.
        } catch (e: IOException) {
            // Catch errors during file reading/writing
            Log.e(TAG, "IOException during Maven Repo unzip from Downloads: ${e.message}", e)
            println("Maven Repo setup failed during I/O: ${e.message}")
            // TODO: Inform user about potential file corruption or storage issues.
        } catch (e: Exception) {
            // Catch other potential errors (e.g., from ZipUtils)
            Log.e(TAG, "Generic Exception during Maven Repo unzip from Downloads: ${e.message}", e)
            println("Maven Repo setup failed: ${e.message}")
            // TODO: Provide a generic error message.
        }
    }

    private fun setupGradleDistsDirectlyFromDownloads() {
        Log.i(TAG, "Attempting to copy Gradle dists DIRECTLY FROM Downloads folder.")

        // 1. Get Downloads directory
        val downloadsDir: File? = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        if (downloadsDir == null) {
            Log.e(TAG, "Could not access public Downloads directory. Skipping Gradle dists setup.")
            println("Gradle Dists setup failed: Cannot find Downloads directory.")
            return
        }

        // 2. Define and ensure the target directory exists
        // Construct the target path robustly. Assuming GRADLE_DISTS is the ABSOLUTE path string here.
        // If GRADLE_DISTS is relative, you need to combine it with app's private storage base path.
        // Let's reconstruct based on common patterns seen previously:
        val gradleDistsTargetDirPath = application.filesDir.path + File.separator +
                com.adfa.constants.HOME_PATH + File.separator +
                ".androidide" + File.separator + "gradle-dists" // Reconstruct target path
        val gradleDistsTargetDir = File(gradleDistsTargetDirPath)

        if (!gradleDistsTargetDir.exists()) {
            if (!gradleDistsTargetDir.mkdirs()) {
                Log.e(TAG, "Failed to create Gradle dists target directory: ${gradleDistsTargetDir.absolutePath}")
                println("Gradle Dists setup failed: Could not create target directory.")
                return
            } else {
                Log.i(TAG, "Created Gradle dists target directory: ${gradleDistsTargetDir.absolutePath}")
            }
        }

        // 3. List of files to copy (using constants)
        val binToCopy = arrayOf(GRADLE_WRAPPER_FILE_NAME, COMPOSE_GRADLE_WRAPPER_FILE_NAME)

        // 4. Iterate and attempt copy for each file
        for (binFileName in binToCopy) {
            if (binFileName == null || binFileName.isEmpty()) {
                Log.w(TAG, "Skipping null or empty Gradle dist filename.")
                continue
            }

            val sourceFile = File(downloadsDir, binFileName)
            val targetFile = File(gradleDistsTargetDir, binFileName)

            Log.d(TAG, "Looking for Gradle dist $binFileName at: ${sourceFile.absolutePath}")
            Log.d(TAG, "Target location for $binFileName: ${targetFile.absolutePath}")

            // 5. Check if source file exists in Downloads
            if (!sourceFile.exists() || !sourceFile.isFile) {
                Log.w(TAG, "Gradle dist source file not found in Downloads: ${sourceFile.absolutePath}")
                println("Gradle Dists setup: Skipping $binFileName - Not found in Downloads folder.")
                continue // Try the next file
            }

            // 6. Attempt the copy using Kotlin's extension function
            try {
                Log.i(TAG, "Attempting to copy ${sourceFile.name} from Downloads to ${targetFile.absolutePath}")
                sourceFile.copyTo(targetFile, true) // true = overwrite if exists
                Log.i(TAG, "Successfully copied ${sourceFile.name} to ${targetFile.getAbsolutePath()}")
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException copying ${sourceFile.name} from Downloads. Access denied.", e)
                println("Gradle Dists setup failed for ${sourceFile.name}: Could not access file in Downloads (Scoped Storage?).")
                // Consider how critical this failure is for your dev workflow
            } catch (e: IOException) {
                Log.e(TAG, "IOException copying ${sourceFile.name} from Downloads.", e)
                println("Gradle Dists setup failed for ${sourceFile.name} during I/O: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Generic Exception copying ${sourceFile.name} from Downloads.", e)
                println("Gradle Dists setup failed for ${sourceFile.name}: ${e.message}")
            }
        } // End for loop
        Log.i(TAG, "Finished attempt to copy Gradle dists from Downloads folder.")
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