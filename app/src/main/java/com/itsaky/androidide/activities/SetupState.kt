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

import android.content.Context
import com.itsaky.androidide.app.configuration.IJdkDistributionProvider
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.PermissionsHelper

/**
 * Whether the IDE has everything it needs to open a project: a JDK, an SDK, and the permissions to
 * reach them.
 *
 * One definition, because there are now two callers with no path between them. [OnboardingActivity]
 * asks so it can hand over to [MainActivity]; [DeepLinkActivity] asks because a link arriving before
 * setup finishes would otherwise walk straight past onboarding into an editor with no toolchain
 * (ADFA-5067 review). A second copy of the rule would let the two disagree about what "ready" means,
 * and the one that disagrees silently is the one that skips a gate.
 *
 * Deliberately *not* the whole of what [SplashActivity] enforces -- free storage and the x86 exit are
 * its business, and a caller that finds this false should send the user there rather than re-deciding
 * any of it.
 */
internal fun Context.isIdeSetupComplete(): Boolean =
	IJdkDistributionProvider.getInstance().installedDistributions.isNotEmpty() &&
		Environment.ANDROID_HOME.exists() &&
		PermissionsHelper.areAllPermissionsGranted(this)
