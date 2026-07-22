package org.secuso.privacyfriendlytodolist.util

import android.content.Context

// Scaffolding, not vendored: the real PreferenceMgr is a large AndroidX Preference-backed
// settings surface (PrefMetaData, ContentHome wiring, many preference keys). This stand-in
// keeps only the one call the real, vendored service/NotificationJob.kt uses.
object PreferenceMgr {
    fun getSnoozeDuration(context: Context): Long = 15 * 60L
}
