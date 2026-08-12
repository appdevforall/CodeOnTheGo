package com.itsaky.androidide.mcp

const val COGO_PACKAGE = "com.itsaky.androidide"

/** The app's default SharedPreferences document, relative to its data directory. */
internal const val COGO_PREFS_PATH = "shared_prefs/${COGO_PACKAGE}_preferences.xml"

/**
 * Wraps [command] so it runs as the app on a debuggable build.
 *
 * run-as enters the app's private data directory only. It cannot read shared
 * storage, so anything under the projects directory must run as the plain shell
 * user instead -- see [listProjectFilesCommand].
 */
internal fun runAs(command: String) = "run-as $COGO_PACKAGE sh -c \"$command\""

// The prefs file does not exist until the app first writes one, and that must read
// as empty rather than as an adb failure - hence the `|| true`.
internal fun readCogoPreferencesCommand() = runAs("cat $COGO_PREFS_PATH 2>/dev/null || true")
