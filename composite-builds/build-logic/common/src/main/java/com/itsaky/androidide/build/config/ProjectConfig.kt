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

package com.itsaky.androidide.build.config

import org.gradle.api.Project
import java.util.Locale

/** @author Akash Yadav */
object ProjectConfig {

    const val REPO_HOST = "github.com"
    const val REPO_OWNER = "AndroidIDEOfficial"
    const val REPO_NAME = "AndroidIDE"
    const val REPO_URL = "https://$REPO_HOST/$REPO_OWNER/$REPO_NAME"
    const val SCM_GIT =
        "scm:git:git://$REPO_HOST/$REPO_OWNER/$REPO_NAME.git"
    const val SCM_SSH =
        "scm:git:ssh://git@$REPO_HOST/$REPO_OWNER/$REPO_NAME.git"

    const val PROJECT_SITE = "https://m.androidide.com"
}

private var shouldPrintVersionName = true

/**
 * Whether this build is being executed in the F-Droid build server.
 */
val Project.isFDroidBuild: Boolean
    get() {
        if (!com.itsaky.androidide.build.config.FDroidConfig.hasRead) {
            com.itsaky.androidide.build.config.FDroidConfig.load(this)
        }
        return com.itsaky.androidide.build.config.FDroidConfig.isFDroidBuild
    }

val Project.simpleVersionName: String
    get() {

        val version = rootProject.version.toString()
        // Format: CodeOnTheGo-{debug|release}-MMDD-HHMM
        val buildType =
            if (hasProperty("release") && property("release") == "true") "release" else "debug"
        val calendar = java.util.Calendar.getInstance()
        val month = calendar.get(java.util.Calendar.MONTH) + 1
        val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = calendar.get(java.util.Calendar.MINUTE)

        val formattedDate = String.format(Locale.getDefault(), "%02d%02d", month, day)
        val formattedTime = String.format(Locale.getDefault(), "%02d%02d", hour, minute)

        val simpleVersion = "CodeOnTheGo-$buildType-$formattedDate-$formattedTime"

        if (shouldPrintVersionName) {
            logger.warn("Simple version name is '$simpleVersion' (from version $version)")
            shouldPrintVersionName = false
        }

        return simpleVersion
    }

private var shouldPrintVersionCode = true
val Project.projectVersionCode: Int
    get() {
        val calendar = java.util.Calendar.getInstance()
        val year = calendar.get(java.util.Calendar.YEAR) % 100 // Just last two digits of year
        val month = calendar.get(java.util.Calendar.MONTH) + 1
        val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = calendar.get(java.util.Calendar.MINUTE)

        // Format: YYMMDDHHMI (Year + Month + Day + Hour + Minute) - keeps increasing over time
        val versionCode = year * 10000000 + month * 100000 + day * 1000 + hour * 100 + minute

        if (shouldPrintVersionCode) {
            logger.warn("Version code is '$versionCode' (generated from current date/time).")
            shouldPrintVersionCode = false
        }

        return versionCode
    }

val Project.publishingVersion: String
    get() {

        var publishing = simpleVersionName
        if (isFDroidBuild) {
            // when building for F-Droid, the release is already published so we should have
            // the maven dependencies already published
            // simply return the simple version name here.
            return publishing
        }

        if (com.itsaky.androidide.build.config.CI.isCiBuild && com.itsaky.androidide.build.config.CI.branchName != "main") {
            publishing += "-${com.itsaky.androidide.build.config.CI.commitHash}-SNAPSHOT"
        }

        return publishing
    }

/**
 * The version name which is used to download the artifacts at runtime.
 *
 * The value varies based on the following cases :
 * - For CI and F-Droid builds: same as [publishingVersion].
 * - For local builds: `latest.integration` to make sure that Gradle downloads the latest snapshots.
 */
val Project.downloadVersion: String
    get() {
        return if (com.itsaky.androidide.build.config.CI.isCiBuild || isFDroidBuild) {
            publishingVersion
        } else {
            // sometimes, when working locally, Gradle fails to download the latest snapshot version
            // this may cause issues while initializing the project in AndroidIDE
            com.itsaky.androidide.build.config.VersionUtils.getLatestSnapshotVersion("gradle-plugin")
        }
    }
