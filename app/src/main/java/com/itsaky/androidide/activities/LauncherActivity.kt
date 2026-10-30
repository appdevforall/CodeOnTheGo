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
import com.itsaky.androidide.security.InstantHide
import com.itsaky.androidide.security.LauncherAliasManager
import com.itsaky.androidide.ui.showLowStorageDialog
import com.itsaky.androidide.utils.FeatureFlags
import com.itsaky.androidide.utils.hasEnoughStorageAvailable
import kotlin.system.exitProcess

/**
 * @author Akash Yadav
 */
class LauncherActivity : Activity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		// Instant Hide (ADFA-40) and Instant Wipe (ADFA-6510): while the disguise is engaged, or once the
		// install is wiped -- or its markers were stripped by clearing app data while the stealth alias
		// persists (the tamper fingerprint) -- go straight to the disguise before any IDE UI. Tamper
		// deliberately does NOT re-run the wipe: the signature cannot tell a prior Instant Wipe from a
		// plain Instant Hide whose data was cleared, so re-wiping could destroy work the user never meant
		// to wipe. Staying the disguise still defeats the tamper. The checks run on the main thread by
		// design and MUST complete before any IDE UI could be shown (F08).
		if (InstantHide.redirectIfHidden(this)) return

		if (!hasEnoughStorageAvailable()) {
			showLowStorageDialog()
			return
		}

		val isX86 = Build.SUPPORTED_ABIS.firstOrNull() in listOf(CpuArch.X86_64.abi, CpuArch.X86.abi)

		if (isX86 && (!IDEBuildConfigProvider.getInstance().supportsCpuAbi() || !FeatureFlags.isEmulatorUseEnabled)) {
			finishAffinity()
			exitProcess(0)
		}

		// ADFA-6507 (wipe-aware reconcile): launching the real IDE -- not hidden, wiped, or tampered (all
		// early-returned above), so the default alias must be the only launcher icon. Heal a stealth alias
		// left stuck by a partial swap, before it could later read as the tamper fingerprint.
		LauncherAliasManager.reconcile(this, disguiseActive = false)

		startActivity(Intent(this, OnboardingActivity::class.java))
		finish()
	}
}
