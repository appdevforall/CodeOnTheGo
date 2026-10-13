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

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.itsaky.androidide.app.configuration.CpuArch
import com.itsaky.androidide.app.configuration.IDEBuildConfigProvider
import com.itsaky.androidide.preferences.internal.SecurityPreferences
import com.itsaky.androidide.security.CryptogramActivity
import com.itsaky.androidide.security.NeutralizationMarker
import com.itsaky.androidide.security.WipeServiceProvider
import com.itsaky.androidide.ui.showLowStorageDialog
import com.itsaky.androidide.utils.FeatureFlags
import com.itsaky.androidide.utils.hasEnoughStorageAvailable
import kotlin.system.exitProcess
import com.itsaky.androidide.resources.R as ResR

/**
 * @author Akash Yadav
 */
class SplashActivity : Activity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		// Instant Wipe neutralization (ADFA-6510): a wiped install is permanently the disguise, before
		// any IDE UI. Checked before the hidden-state redirect because a wipe blanks that flag.
		if (NeutralizationMarker.isWiped(this)) {
			startActivity(
				Intent(this, CryptogramActivity::class.java).apply {
					addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
				},
			)
			finish()
			return
		}
		// Tamper: markers stripped by clearing app data, but the stealth alias remains and prefs are
		// blank. The /sdcard workspace survives a data clear, so re-engage the wipe (which also shows
		// the disguise and finishes this activity).
		if (NeutralizationMarker.isTampered(this, getString(ResR.string.stealth_app_name))) {
			(applicationContext as? WipeServiceProvider)?.wipeService?.trigger(this)
			return
		}

		// Instant Hide (ADFA-40): if the disguise is engaged, go straight to it before any IDE UI
		// is shown. The state persists across restarts/reboots until the user performs the exit gesture.
		if (SecurityPreferences.isHiddenStateActive) {
			startActivity(
				Intent(this, CryptogramActivity::class.java).apply {
					addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
				},
			)
			finish()
			return
		}

		if (!hasEnoughStorageAvailable()) {
			showLowStorageDialog()
			return
		}

		val isX86 = Build.SUPPORTED_ABIS.firstOrNull() in listOf(CpuArch.X86_64.abi, CpuArch.X86.abi)

		if (isX86 && (!IDEBuildConfigProvider.getInstance().supportsCpuAbi() || !FeatureFlags.isEmulatorUseEnabled)) {
			finishAffinity()
			exitProcess(0)
		}

		startActivity(Intent(this, OnboardingActivity::class.java))
		finish()
	}
}
