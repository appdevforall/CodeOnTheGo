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
package com.itsaky.androidide.app

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.core.os.UserManagerCompat
import com.itsaky.androidide.managers.NoopSharedPreferencesImpl
import com.itsaky.androidide.managers.PreferenceManager
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.JavaCharacter
import org.slf4j.LoggerFactory

open class BaseApplication : Application() {
	private var _prefManager: PreferenceManager? = null

	val isUserUnlocked: Boolean
		get() = UserManagerCompat.isUserUnlocked(this)

	val prefManager: PreferenceManager
		get() =
			checkNotNull(_prefManager) {
				"PreferenceManager not initialized"
			}

	init {
		_baseInstance = this
	}

	override fun onCreate() {
		super.onCreate()
		Environment.init(this)

		_prefManager = PreferenceManager(getSafeContext())
		JavaCharacter.initMap()
	}

	@JvmOverloads
	fun getSafeContext(deviceProtectedStorageContext: Boolean = false): Context {
		if (isUserUnlocked && !deviceProtectedStorageContext) {
			return this
		}

		logger.warn("Creating safe context because user is not unlocked")
		return object : ContextWrapper(createDeviceProtectedStorageContext()) {
			override fun getSharedPreferences(
				name: String?,
				mode: Int,
			): SharedPreferences? =
				try {
					super.getSharedPreferences(name, mode)
				} catch (_: IllegalStateException) {
					// SharedPreferences in credential encrypted storage are not available until
					// after user is unlocked
					logger.warn("Using no-op SharedPreferences because user is probably not unlocked")
					NoopSharedPreferencesImpl()
				}
		}
	}

	companion object {
		private val logger = LoggerFactory.getLogger(BaseApplication::class.java)

		const val NOTIFICATION_GRADLE_BUILD_SERVICE: String = "17571"

		private var _baseInstance: BaseApplication? = null

		@JvmStatic
		val baseInstance: BaseApplication
			get() =
				checkNotNull(_baseInstance) {
					"baseInstance is not set"
				}
	}
}
