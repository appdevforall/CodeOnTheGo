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

package com.itsaky.androidide.security

import android.content.Context
import com.itsaky.androidide.preferences.internal.SecurityPreferences
import java.io.File

/**
 * The Instant Wipe (ADFA-6510) tamper tripwire / neutralization marker.
 *
 * After a wipe, two empty marker files are written: `.pip` in the app's internal files dir and `.pip2`
 * in the no-backup files dir (so a cloud restore cannot strip it). On launch, either marker means the
 * install is permanently neutralized -- it shows only the "Code Breaker" disguise and never the IDE.
 *
 * [isTampered] catches the evasion of clearing app data to delete the markers: that also blanks the
 * security prefs, but the launcher **activity-alias enabled state survives a data wipe**, so a blank
 * prefs + still-enabled stealth alias is the fingerprint of tampering. A clean fresh install never
 * matches (its default alias is enabled, not a stealth one).
 */
object NeutralizationMarker {
	private const val PIP = ".pip"
	private const val PIP2 = ".pip2"

	fun pipFile(context: Context): File = File(context.filesDir, PIP)

	fun pip2File(context: Context): File = File(context.noBackupFilesDir, PIP2)

	/** True once Instant Wipe has neutralized this install (either marker present). */
	fun isWiped(context: Context): Boolean = runCatching { pipFile(context).exists() || pip2File(context).exists() }.getOrDefault(false)

	/** Writes both markers (idempotent). */
	fun mark(context: Context) {
		runCatching { pipFile(context).apply { parentFile?.mkdirs() }.createNewFile() }
		runCatching { pip2File(context).apply { parentFile?.mkdirs() }.createNewFile() }
	}

	/**
	 * True when the markers are gone but the install still looks wiped: no marker, security prefs
	 * blank, yet the stealth launcher alias ([stealthLabel]) is still the enabled one. See the class
	 * doc for why this is the data-clear tamper fingerprint.
	 */
	fun isTampered(
		context: Context,
		stealthLabel: String,
	): Boolean =
		isTamperSignature(
			wiped = isWiped(context),
			hideArmed = SecurityPreferences.instantHideEnabled,
			wipeArmed = SecurityPreferences.instantWipeEnabled,
			currentLauncherLabel = LauncherAliasManager.getCurrentLauncherLabel(context),
			stealthLabel = stealthLabel,
		)

	/**
	 * The pure tamper decision, split out so it is unit-testable without a PackageManager or the
	 * preference store: a post-destruct look -- the stealth alias still showing [stealthLabel] -- with
	 * no marker and the security prefs blanked is the fingerprint of clearing app data to strip the
	 * markers. A clean fresh install (default alias) or a still-armed install (prefs not blank) does
	 * not match, and a present marker is handled by [isWiped] before this is reached.
	 */
	internal fun isTamperSignature(
		wiped: Boolean,
		hideArmed: Boolean,
		wipeArmed: Boolean,
		currentLauncherLabel: String,
		stealthLabel: String,
	): Boolean = !wiped && !hideArmed && !wipeArmed && currentLauncherLabel == stealthLabel
}
