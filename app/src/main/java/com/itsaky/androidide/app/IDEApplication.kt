/*
 * This file is part of AndroidIDE.
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidIDE is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package com.itsaky.androidide.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.itsaky.androidide.BuildConfig
import com.itsaky.androidide.plugins.manager.core.PluginManager
import com.itsaky.androidide.treesitter.TreeSitter
import com.itsaky.androidide.utils.RecyclableObjectPool
import com.itsaky.androidide.utils.VMUtils
import com.itsaky.androidide.utils.isAtLeastR
import com.itsaky.androidide.utils.isTestMode
import com.topjohnwu.superuser.Shell
import io.sentry.Sentry
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.plus
import org.lsposed.hiddenapibypass.HiddenApiBypass
import org.slf4j.LoggerFactory
import java.lang.Thread.UncaughtExceptionHandler

const val EXIT_CODE_CRASH = 1

class IDEApplication : BaseApplication() {

	val coroutineScope = MainScope() + CoroutineName("ApplicationScope")

	internal var uncaughtExceptionHandler: UncaughtExceptionHandler? = null
	private var currentActivity: Activity? = null

	private val deviceUnlockReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context?, intent: Intent?) {
			if (intent?.action == Intent.ACTION_USER_UNLOCKED) {
				runCatching { unregisterReceiver(this) }

				logger.info("Device unlocked! Loading all components...")
				CredentialProtectedApplicationLoader.load(this@IDEApplication)
			}
		}
	}

	companion object {
		private val logger = LoggerFactory.getLogger(IDEApplication::class.java)

		const val SENTRY_ENV_DEV = "development"
		const val SENTRY_ENV_PROD = "production"

		@JvmStatic
		@SuppressLint("StaticFieldLeak")
		lateinit var instance: IDEApplication
			private set

		init {
			@Suppress("Deprecation")
			Shell.setDefaultBuilder(
				Shell.Builder
					.create()
					.setFlags(Shell.FLAG_REDIRECT_STDERR),
			)

			HiddenApiBypass.setHiddenApiExemptions("")

			if (!VMUtils.isJvm && !isTestMode()) {
				try {
					if (isAtLeastR()) {
						System.loadLibrary("adb")
					}

					TreeSitter.loadLibrary()
				} catch (e: UnsatisfiedLinkError) {
					Sentry.captureException(e)
					logger.warn("Failed to load native libraries", e)
				}
			}

			RecyclableObjectPool.DEBUG = BuildConfig.DEBUG
		}

		@JvmStatic
		fun getPluginManager(): PluginManager? = CredentialProtectedApplicationLoader.pluginManager
	}

	/**
	 * Gets the current active activity from AndroidIDE.
	 * This method should return the currently visible activity.
	 */
	fun getCurrentActiveActivity(): Activity? = currentActivity

	/**
	 * Called by activities when they become active/visible.
	 * This is used for plugin UI service integration.
	 */
	fun setCurrentActivity(activity: Activity?) {
		this.currentActivity = activity
		logger.debug("Current activity set to: ${activity?.javaClass?.simpleName}")
	}

	@OptIn(DelicateCoroutinesApi::class)
	override fun onCreate() {
		instance = this
		uncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
		Thread.setDefaultUncaughtExceptionHandler(::handleUncaughtException)

		super.onCreate()

		// @devs: looking to initialize a component at application startup?
		// first, decide whether the component you want to initialize can be
		// run in direct boot mode or not. If it can run in direct boot mode,
		// you can do the initialization in DeviceProtectedApplicationLoader.
		// Otherwise, you must do it in CredentialProtectedApplicationLoader.
		//
		// Components initialized in CredentialProtectedApplicationLoader may
		// not be initialized right away. This happens when the user reboots
		// their device but has not unlocked yet.
		//
		// Pay extra attention to what goes in DeviceProtectedApplicationLoader.
		// In case any of the components fail to initialize there, it may lead
		// to ANRs when the IDE is launched after device reboot.
		// https://appdevforall.atlassian.net/browse/ADFA-2026
		// https://appdevforall-inc-9p.sentry.io/issues/6860179170/events/7177c576e7b3491c9e9746c76f806d37/


		// load common stuff, which doesn't depend on access to
		// credential protected storage
		DeviceProtectedApplicationLoader.load(this)

		// if we can access credential-protected storage, then initialize
		// other components right away, otherwise wait for the user to unlock
		// the device
		if (isUserUnlocked) {
			CredentialProtectedApplicationLoader.load(this)
		} else {
			logger.info("Device in Direct Boot Mode: postponing initialization...")
			registerReceiver(deviceUnlockReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED))
		}
	}

	private fun handleUncaughtException(
		thread: Thread,
		exception: Throwable,
	) {
		if (isUserUnlocked) {
			// we can access credential protected storage, delegate the job to
			// to advanced crash handler
			CredentialProtectedApplicationLoader.handleUncaughtException(thread, exception)
			return
		}

		// we can only access device-protected storage, and are not allowed
		// to show crash handler screen
		// delegate the job to the basic crash handler
		DeviceProtectedApplicationLoader.handleUncaughtException(thread, exception)
	}
}
