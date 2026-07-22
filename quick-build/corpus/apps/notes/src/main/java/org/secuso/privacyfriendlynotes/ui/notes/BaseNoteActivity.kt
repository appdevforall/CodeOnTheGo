package org.secuso.privacyfriendlynotes.ui.notes

import android.app.Activity

// Scaffolding, not vendored: the real BaseNoteActivity is a large shared note-editor base class.
// The vendored subgraph (receiver/NotificationReceiver.kt) only reads its EXTRA_ID constant to
// build an Intent -- it never calls into the real class's body.
open class BaseNoteActivity : Activity() {
    companion object {
        const val EXTRA_ID = "org.secuso.privacyfriendlynotes.ID"
    }
}
