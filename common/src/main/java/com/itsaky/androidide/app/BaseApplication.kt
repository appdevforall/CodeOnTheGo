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
import androidx.test.platform.app.InstrumentationRegistry
import com.blankj.utilcode.util.ThrowableUtils
import com.itsaky.androidide.managers.NoopSharedPreferencesImpl
import com.itsaky.androidide.managers.PreferenceManager
import com.itsaky.androidide.managers.ToolsManager
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.FileUtil
import com.itsaky.androidide.utils.JavaCharacter
import com.itsaky.androidide.utils.UrlManager.openUrl
import com.itsaky.androidide.utils.VMUtils
import org.slf4j.LoggerFactory
import java.io.File

open class BaseApplication : Application() {
	private var _prefManager: PreferenceManager? = null
	private var _isUserUnlocked = false

	val isUserUnlocked: Boolean
		get() = _isUserUnlocked

	val prefManager: PreferenceManager
		get() = checkNotNull(_prefManager) {
			"PreferenceManager not initialized"
		}

	@JvmOverloads
	fun getSafeContext(deviceProtectedStorageContext: Boolean = false): Context {
		if (isUserUnlocked && !deviceProtectedStorageContext) {
			return this
		}

		logger.warn("Creating safe context because user is not unlocked")
		return object : ContextWrapper(createDeviceProtectedStorageContext()) {
			override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences? {
				return try {
					super.getSharedPreferences(name, mode)
				} catch (_: IllegalStateException) {
					// SharedPreferences in credential encrypted storage are not available until
					// after user is unlocked
					logger.warn("Using no-op SharedPreferences because user is probably not unlocked")
					NoopSharedPreferencesImpl()
				}
			}
		}
	}

	override fun onCreate() {
		_baseInstance = this
		_isUserUnlocked = UserManagerCompat.isUserUnlocked(this)

		Environment.init(this)
		super.onCreate()

		_prefManager = PreferenceManager(getSafeContext())
		JavaCharacter.initMap()

		if (isUserUnlocked && (!VMUtils.isJvm() || this.isInstrumentedTest)) {
			ToolsManager.init(this, null)
		}
	}

	fun writeException(th: Throwable?) {
		FileUtil.writeFile(
			File(FileUtil.getExternalStorageDir(), "idelog.txt").absolutePath,
			ThrowableUtils.getFullStackTrace(th)
		)
	}

	@JvmOverloads
	fun openUrl(url: String, pkg: String? = null) {
		openUrl(url, pkg, this)
	}

	private val isInstrumentedTest: Boolean
		get() {
			try {
				InstrumentationRegistry.getInstrumentation()
				return true
			} catch (_: IllegalStateException) {
				return false
			}
		}

	companion object {

		private val logger = LoggerFactory.getLogger(BaseApplication::class.java)

		const val NOTIFICATION_GRADLE_BUILD_SERVICE: String = "17571"


		private var _baseInstance: BaseApplication? = null

		@JvmStatic
		val baseInstance: BaseApplication
			get() = checkNotNull(_baseInstance) {
				"baseInstance is not set"
			}

	}
}
