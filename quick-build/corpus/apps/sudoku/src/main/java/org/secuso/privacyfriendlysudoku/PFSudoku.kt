package org.secuso.privacyfriendlysudoku

import android.app.Application

// Scaffolding, not vendored: the real PFSudoku wires the :backup-api submodule (excluded, same
// rationale as the other SecUSo entries). The vendored GeneratorService.java only reads the
// CHANNEL_ID constant to post its notification.
class PFSudoku : Application() {
    companion object {
        const val CHANNEL_ID = "org.secuso.privacyfriendlysudoku.NOTIFICATION_CHANNEL"
    }
}
