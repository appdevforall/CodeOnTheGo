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
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Switches the launcher between the real IDE identity and a per-device "stealth" disguise identity,
 * by toggling `<activity-alias>` entries declared in the app manifest. The disguise variant is
 * chosen deterministically from hardware identifiers so a given device always shows the same
 * disguise icon/label.
 *
 * The aliases targeted here are declared in the app's `AndroidManifest.xml`, all pointing at the
 * launcher activity (`LauncherActivity`). The default alias is named `.activities.SplashActivity`,
 * the launcher component from before Instant Hide, so pinned home-screen icons survive the update.
 */
object LauncherAliasManager {
	private const val TAG = "LauncherAliasManager"
	private const val DEFAULT_ALIAS = "com.itsaky.androidide.activities.SplashActivity"

	private val STEALTH_ALIASES =
		listOf(
			"com.itsaky.androidide.StealthAliasActivity1",
			"com.itsaky.androidide.StealthAliasActivity2",
			"com.itsaky.androidide.StealthAliasActivity3",
			"com.itsaky.androidide.StealthAliasActivity4",
			"com.itsaky.androidide.StealthAliasActivity5",
		)

	/**
	 * Deterministic disguise index in `[0, modulo)` from an FNV-1a 32-bit hash of the device
	 * signature. Uses [Int.mod] (never negative) rather than `abs(hash) % modulo`, which would
	 * return a negative index for `hash == Int.MIN_VALUE`.
	 */
	internal fun stealthVariationIndex(
		hardwareSignature: String,
		modulo: Int,
	): Int {
		var hash = 0x811c9dc5.toInt()
		for (ch in hardwareSignature) {
			hash = hash xor ch.code
			hash *= 0x01000193
		}
		return hash.mod(modulo)
	}

	fun getStealthVariationIndex(context: Context): Int {
		val androidId =
			Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "fallback_id"
		val signature =
			"${Build.BOARD}_${Build.BRAND}_${Build.DEVICE}_${Build.HARDWARE}_${Build.MODEL}_${Build.PRODUCT}_$androidId"
		return stealthVariationIndex(signature, STEALTH_ALIASES.size)
	}

	/**
	 * The icon of this device's stealth alias, for the disguise's Recents entry; 0 when it cannot be
	 * read, which leaves the system default.
	 */
	fun stealthIcon(context: Context): Int =
		runCatching {
			val alias = ComponentName(context, STEALTH_ALIASES[getStealthVariationIndex(context)])
			context.packageManager.getActivityInfo(alias, PackageManager.MATCH_DISABLED_COMPONENTS).iconResource
		}.getOrElse {
			Log.e(TAG, "Failed to read the stealth alias icon", it)
			0
		}

	fun isStealthModeActive(context: Context): Boolean {
		val defaultComponent = ComponentName(context, DEFAULT_ALIAS)
		return context.packageManager.getComponentEnabledSetting(defaultComponent) ==
			PackageManager.COMPONENT_ENABLED_STATE_DISABLED
	}

	/** Display label of whichever launcher alias is currently enabled, or "" if none resolves. */
	fun getCurrentLauncherLabel(context: Context): String {
		val pm = context.packageManager
		for (aliasName in listOf(DEFAULT_ALIAS) + STEALTH_ALIASES) {
			val comp = ComponentName(context, aliasName)
			val state = pm.getComponentEnabledSetting(comp)
			val explicitlyEnabled = state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
			val defaultEnabled =
				state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && aliasName == DEFAULT_ALIAS
			if (explicitlyEnabled || defaultEnabled) {
				return try {
					pm.getActivityInfo(comp, PackageManager.GET_META_DATA).loadLabel(pm).toString()
				} catch (e: Exception) {
					Log.e(TAG, "Failed to resolve label for $aliasName", e)
					""
				}
			}
		}
		return ""
	}

	/** Outcome of [reconcileAliasAction]: the alias swap (if any) that heals a partial or stuck swap. */
	enum class AliasReconcileAction { ENABLE_STEALTH, RESTORE_DEFAULT, NONE }

	/**
	 * Decides the launcher-alias swap (if any) needed to make the enabled alias match whether the app
	 * is presenting the disguise, healing a partial or stuck swap (ADFA-6507). Pure and exhaustively
	 * unit-testable.
	 *
	 * Safety invariant callers rely on: when [disguiseActive] is true this never returns
	 * [AliasReconcileAction.RESTORE_DEFAULT], so a reconcile can never expose the real icon while the
	 * disguise (Instant Hide, or a post-wipe neutralized install) is on screen.
	 */
	fun reconcileAliasAction(
		disguiseActive: Boolean,
		stealthAliasActive: Boolean,
	): AliasReconcileAction =
		when {
			disguiseActive && !stealthAliasActive -> AliasReconcileAction.ENABLE_STEALTH
			!disguiseActive && stealthAliasActive -> AliasReconcileAction.RESTORE_DEFAULT
			else -> AliasReconcileAction.NONE
		}

	/** A single launcher-alias component toggle: enable or disable [aliasName]. */
	internal data class AliasOp(
		val aliasName: String,
		val enabled: Boolean,
	)

	/**
	 * The ordered component toggles that make up a launcher-alias swap. On the enable path the FIRST
	 * op always DISABLES the real [DEFAULT_ALIAS] (ADFA-6507): [setStealthMode] applies these in order,
	 * and a `PackageManager` call can throw mid-sequence on some OEM ROMs, so leading with the disable
	 * makes a partial failure fail HIDDEN (at worst briefly no icon) instead of leaving the real icon
	 * visible over the disguise. Pure, so the ordering invariant is unit-testable.
	 */
	internal fun stealthSwapOps(
		activeStealthAlias: String,
		enableStealth: Boolean,
	): List<AliasOp> =
		buildList {
			add(AliasOp(DEFAULT_ALIAS, enabled = !enableStealth))
			for (aliasName in STEALTH_ALIASES) {
				add(AliasOp(aliasName, enabled = enableStealth && aliasName == activeStealthAlias))
			}
		}

	/** Enables the per-device stealth alias (and disables the default), or restores the default. */
	fun setStealthMode(
		context: Context,
		enableStealth: Boolean,
	) {
		val pm = context.packageManager
		val activeStealthAlias = STEALTH_ALIASES[getStealthVariationIndex(context)]
		try {
			for (op in stealthSwapOps(activeStealthAlias, enableStealth)) {
				pm.setComponentEnabledSetting(
					ComponentName(context, op.aliasName),
					if (op.enabled) {
						PackageManager.COMPONENT_ENABLED_STATE_ENABLED
					} else {
						PackageManager.COMPONENT_ENABLED_STATE_DISABLED
					},
					PackageManager.DONT_KILL_APP,
				)
			}
		} catch (e: Exception) {
			Log.e(TAG, "Failed to update launcher aliases", e)
		}
	}
}
