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

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsaky.androidide.security.LauncherAliasManager.AliasOp
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Verification for the ADFA-6507 launcher-alias hardening folded into the ADFA-6510 stack:
 *  - [LauncherAliasManager.stealthSwapOps]: the fail-hidden ORDERING of the alias swap.
 *  - [LauncherAliasManager.reconcile]: the self-healing reconcile, from each state a partial swap leaves.
 *
 * [setStealthMode] applies [stealthSwapOps] in list order inside one try/catch, so "the real icon is
 * disabled first" is exactly what makes a mid-sequence PackageManager failure fail hidden. The
 * fault-injection on a device could not exercise that, so it is pinned here.
 */
@RunWith(RobolectricTestRunner::class)
class LauncherAliasReconcileTest {
	private val defaultAlias = "com.itsaky.androidide.activities.SplashActivity"
	private val activeStealth = "com.itsaky.androidide.StealthAliasActivity3"
	private val stealthAliases = (1..5).map { "com.itsaky.androidide.StealthAliasActivity$it" }
	private val context: Context = RuntimeEnvironment.getApplication()

	@Test
	fun `enabling stealth disables the real icon FIRST (fail-hidden ordering)`() {
		val ops = LauncherAliasManager.stealthSwapOps(activeStealth, enableStealth = true)
		assertThat(ops.first()).isEqualTo(AliasOp(defaultAlias, enabled = false))
	}

	@Test
	fun `enabling stealth enables only the active alias, after the real icon is disabled`() {
		val ops = LauncherAliasManager.stealthSwapOps(activeStealth, enableStealth = true)

		// Exactly the active stealth alias ends enabled; the real icon and the other stealth aliases
		// end disabled. (6 ops: the default + 5 stealth aliases -- a 6th would trip this deliberately.)
		assertThat(ops).hasSize(6)
		assertThat(ops.filter { it.enabled }).containsExactly(AliasOp(activeStealth, enabled = true))

		val realDisabledAt = ops.indexOf(AliasOp(defaultAlias, enabled = false))
		val stealthEnabledAt = ops.indexOf(AliasOp(activeStealth, enabled = true))
		assertThat(realDisabledAt).isAtLeast(0)
		assertThat(realDisabledAt).isLessThan(stealthEnabledAt)
	}

	@Test
	fun `restoring the default enables the real icon FIRST and disables every stealth alias`() {
		val ops = LauncherAliasManager.stealthSwapOps(activeStealth, enableStealth = false)
		assertThat(ops.first()).isEqualTo(AliasOp(defaultAlias, enabled = true))
		assertThat(ops.hasRealIconThenAllStealthDisabled()).isTrue()
	}

	private fun List<AliasOp>.hasRealIconThenAllStealthDisabled(): Boolean = size == 6 && drop(1).none { it.enabled }

	private fun setAlias(
		name: String,
		enabled: Boolean,
	) = context.packageManager.setComponentEnabledSetting(
		ComponentName(context, name),
		if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
		PackageManager.DONT_KILL_APP,
	)

	private fun aliasState(name: String) = context.packageManager.getComponentEnabledSetting(ComponentName(context, name))

	/** The aliases the launcher shows; the manifest enables only the default one. */
	private fun shownAliases(): List<String> =
		(listOf(defaultAlias) + stealthAliases).filter { name ->
			when (aliasState(name)) {
				PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
				PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> name == defaultAlias
				else -> false
			}
		}

	/** What a completed swap, or one that threw midway, can leave the launcher showing. */
	private val launcherStates: Map<String, () -> Unit> =
		mapOf(
			"only the real icon" to { LauncherAliasManager.setStealthMode(context, false) },
			"only the stealth icon" to { LauncherAliasManager.setStealthMode(context, true) },
			"both icons" to {
				LauncherAliasManager.setStealthMode(context, true)
				setAlias(defaultAlias, enabled = true)
			},
			"no icon" to {
				LauncherAliasManager.setStealthMode(context, true)
				stealthAliases.forEach { setAlias(it, enabled = false) }
			},
		)

	@Test
	fun `the disguise leaves only a stealth icon, whatever the launcher showed`() {
		for ((start, setUp) in launcherStates) {
			setUp()
			LauncherAliasManager.reconcile(context, disguiseActive = true)
			val shown = shownAliases()
			assertWithMessage(start).that(shown).hasSize(1)
			assertWithMessage(start).that(shown).doesNotContain(defaultAlias)
		}
	}

	@Test
	fun `the IDE leaves only the real icon, whatever the launcher showed`() {
		for ((start, setUp) in launcherStates) {
			setUp()
			LauncherAliasManager.reconcile(context, disguiseActive = false)
			assertWithMessage(start).that(shownAliases()).containsExactly(defaultAlias)
		}
	}

	@Test
	fun `a fresh install's launcher is left untouched`() {
		LauncherAliasManager.reconcile(context, disguiseActive = false)
		for (name in listOf(defaultAlias) + stealthAliases) {
			assertWithMessage(name).that(aliasState(name)).isEqualTo(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
		}
	}
}
