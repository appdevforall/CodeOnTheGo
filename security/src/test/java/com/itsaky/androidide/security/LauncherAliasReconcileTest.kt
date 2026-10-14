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

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.security.LauncherAliasManager.AliasOp
import com.itsaky.androidide.security.LauncherAliasManager.AliasReconcileAction
import org.junit.Test

/**
 * Verification for the ADFA-6507 launcher-alias hardening folded into the ADFA-6510 stack:
 *  - [LauncherAliasManager.stealthSwapOps]: the fail-hidden ORDERING of the alias swap.
 *  - [LauncherAliasManager.reconcileAliasAction]: the self-healing reconcile DECISION.
 *
 * Both are pure, so the invariants the on-device fault-injection could not exercise are pinned here
 * instead of only reasoned about. [setStealthMode] applies [stealthSwapOps] in list order inside one
 * try/catch, so "the real icon is disabled first" is exactly what makes a mid-sequence PackageManager
 * failure fail hidden.
 */
class LauncherAliasReconcileTest {
	private val defaultAlias = "com.itsaky.androidide.DefaultAliasActivity"
	private val activeStealth = "com.itsaky.androidide.StealthAliasActivity3"

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

	@Test
	fun `reconcile - disguise shown but default still enabled - enable stealth`() {
		assertThat(
			LauncherAliasManager.reconcileAliasAction(disguiseActive = true, stealthAliasActive = false),
		).isEqualTo(AliasReconcileAction.ENABLE_STEALTH)
	}

	@Test
	fun `reconcile - IDE launching but a stealth alias is stuck - restore default`() {
		assertThat(
			LauncherAliasManager.reconcileAliasAction(disguiseActive = false, stealthAliasActive = true),
		).isEqualTo(AliasReconcileAction.RESTORE_DEFAULT)
	}

	@Test
	fun `reconcile - already consistent either way - no change`() {
		assertThat(
			LauncherAliasManager.reconcileAliasAction(disguiseActive = true, stealthAliasActive = true),
		).isEqualTo(AliasReconcileAction.NONE)
		assertThat(
			LauncherAliasManager.reconcileAliasAction(disguiseActive = false, stealthAliasActive = false),
		).isEqualTo(AliasReconcileAction.NONE)
	}

	@Test
	fun `reconcile safety invariant - while the disguise is shown it NEVER restores the default`() {
		for (stealthActive in listOf(true, false)) {
			assertThat(
				LauncherAliasManager.reconcileAliasAction(disguiseActive = true, stealthAliasActive = stealthActive),
			).isNotEqualTo(AliasReconcileAction.RESTORE_DEFAULT)
		}
	}
}
