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
package com.itsaky.androidide.managers

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.itsaky.androidide.eventbus.events.preferences.PreferenceChangeEvent
import com.itsaky.androidide.eventbus.events.preferences.PreferenceRemoveEvent
import org.greenrobot.eventbus.EventBus
import org.jetbrains.annotations.Contract


class PreferenceManager @SuppressLint("CommitPrefEdits") constructor(
	context: Context,
	preferenceName: String?,
	prefMode: Int
) {
	private val prefs: SharedPreferences = if (preferenceName == null || preferenceName.isBlank()) {
		PreferenceManager.getDefaultSharedPreferences(context)
	} else {
		context.getSharedPreferences(preferenceName, prefMode)
	}

	@JvmOverloads
	constructor(context: Context, preferenceMode: String? = null) : this(
		context,
		preferenceMode,
		Context.MODE_PRIVATE
	)

	fun remove(key: String) = apply {
		val edit = prefs.edit()
		edit.remove(key)
		applyChanges(edit)
		dispatchRemoveEvent(key)
	}

	fun putInt(key: String, `val`: Int) = apply {
		val edit = prefs.edit()
		edit.putInt(key, `val`)
		applyChanges(edit)
		dispatchChangeEvent(key, `val`)
	}

	fun putFloat(key: String, `val`: Float) = apply {
		val edit = prefs.edit()
		edit.putFloat(key, `val`)
		applyChanges(edit)
		dispatchChangeEvent(key, `val`)
	}

	fun getFloat(key: String): Float {
		return prefs.getFloat(key, 0f)
	}

	fun getFloat(key: String, def: Float): Float {
		return prefs.getFloat(key, def)
	}

	fun getString(key: String): String? {
		return prefs.getString(key, null)
	}

	@Contract("_,!null->!null")
	fun getString(key: String, defaultValue: String?): String? {
		return prefs.getString(key, defaultValue)
	}

	fun putString(key: String, value: String?) = apply {
		val edit = prefs.edit()
		edit.putString(key, value)
		applyChanges(edit)
		dispatchChangeEvent(key, value)
		return this
	}

	fun getBoolean(key: String): Boolean {
		return getBoolean(key, false)
	}

	fun getBoolean(key: String, defaultValue: Boolean): Boolean {
		return prefs.getBoolean(key, defaultValue)
	}

	fun putBoolean(key: String, value: Boolean) = apply {
		val edit = prefs.edit()
		edit.putBoolean(key, value)
		applyChanges(edit)
		dispatchChangeEvent(key, value)
	}

	fun getLong(key: String, defaultValue: Long): Long {
		return prefs.getLong(key, defaultValue)
	}

	fun putLong(key: String, value: Long) = apply {
		val edit = prefs.edit()
		edit.putLong(key, value)
		applyChanges(edit)
		dispatchChangeEvent(key, value)
	}

	fun getInt(key: String, def: Int): Int {
		return prefs.getInt(key, def)
	}

	private fun applyChanges(editor: SharedPreferences.Editor) {
		editor.apply()
	}

	private fun dispatchRemoveEvent(key: String) {
		EventBus.getDefault().post(PreferenceRemoveEvent(key))
	}

	private fun dispatchChangeEvent(key: String, value: Any?) {
		dispatchChangeEvent(PreferenceChangeEvent(key, value))
	}

	private fun dispatchChangeEvent(event: PreferenceChangeEvent?) {
		EventBus.getDefault().post(event)
	}
}
